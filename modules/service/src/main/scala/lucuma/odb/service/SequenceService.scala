// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.service

import cats.Applicative
import cats.data.EitherT
import cats.data.OptionT
import cats.effect.Concurrent
import cats.effect.std.UUIDGen
import cats.syntax.apply.*
import cats.syntax.either.*
import cats.syntax.eq.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.option.*
import cats.syntax.traverse.*
import eu.timepit.refined.types.numeric.NonNegShort
import fs2.Stream
import grackle.Result
import lucuma.core.enums.AtomStage
import lucuma.core.enums.Instrument
import lucuma.core.enums.ObserveClass
import lucuma.core.enums.SequenceType
import lucuma.core.enums.StepStage
import lucuma.core.enums.StepType
import lucuma.core.model.Observation
import lucuma.core.model.Visit
import lucuma.core.model.sequence.Atom
import lucuma.core.model.sequence.Step
import lucuma.core.model.sequence.StepConfig
import lucuma.core.model.sequence.StepEstimate
import lucuma.core.model.sequence.gmos.DynamicConfig.GmosNorth
import lucuma.core.model.sequence.gmos.DynamicConfig.GmosSouth
import lucuma.core.model.sequence.gmos.StaticConfig.GmosNorth as GmosNorthStatic
import lucuma.core.model.sequence.gmos.StaticConfig.GmosSouth as GmosSouthStatic
import lucuma.core.util.TimeSpan
import lucuma.core.util.Timestamp
import lucuma.odb.data.AtomExecutionState
import lucuma.odb.data.OdbError
import lucuma.odb.data.OdbErrorExtensions.*
import lucuma.odb.data.StepExecutionState
import lucuma.odb.logic.EstimatorState
import lucuma.odb.logic.TimeEstimateCalculator
import lucuma.odb.sequence.data.ProtoStep
import lucuma.odb.sequence.data.StepRecord
import lucuma.odb.util.Codecs.*
import skunk.*
import skunk.implicits.*

import Services.Syntax.*

trait SequenceService[F[_]] {

  def selectGmosNorthStepRecords(
    observationId: Observation.Id
  ): Stream[F, StepRecord[GmosNorth]]

  def selectGmosSouthStepRecords(
    observationId: Observation.Id
  ): Stream[F, StepRecord[GmosSouth]]

  def abandonAtomsAndStepsForObservation(
    observationId: Observation.Id
  )(using Transaction[F], Services.ServiceAccess): F[Unit]

  def setAtomExecutionState(
    atomId: Atom.Id,
    stage:  AtomStage
  )(using Transaction[F], Services.ServiceAccess): F[Unit]

  def abandonOngoingAtomsExcept(
    observationId: Observation.Id,
    atomId:        Atom.Id
  )(using Transaction[F], Services.ServiceAccess): F[Unit]

  def setStepExecutionState(
    stepId: Step.Id,
    stage:  StepStage,
    time:   Timestamp
  )(using Transaction[F]): F[Unit]

  def abandonOngoingStepsExcept(
    observationId: Observation.Id,
    atomId:        Atom.Id,
    stepId:        Step.Id
  )(using Transaction[F], Services.ServiceAccess): F[Unit]

  def insertAtomRecord(
    visitId:      Visit.Id,
    instrument:   Instrument,
    stepCount:    NonNegShort,
    sequenceType: SequenceType,
    generatedId:  Option[Atom.Id]
  )(using Transaction[F], Services.ServiceAccess): F[Result[Atom.Id]]

  def insertGmosNorthStepRecord(
    atomId:         Atom.Id,
    instrument:     GmosNorth,
    step:           StepConfig,
    observeClass:   ObserveClass,
    generatedId:    Option[Step.Id],
    timeCalculator: TimeEstimateCalculator[GmosNorthStatic, GmosNorth]
  )(using Transaction[F], Services.ServiceAccess): F[Result[Step.Id]]

  def insertGmosSouthStepRecord(
    atomId:         Atom.Id,
    instrument:     GmosSouth,
    step:           StepConfig,
    observeClass:   ObserveClass,
    generatedId:    Option[Step.Id],
    timeCalculator: TimeEstimateCalculator[GmosSouthStatic, GmosSouth]
  )(using Transaction[F], Services.ServiceAccess): F[Result[Step.Id]]

}

object SequenceService {

  sealed trait InsertAtomResponse extends Product with Serializable

  object InsertAtomResponse {

    case class VisitNotFound(
      vid:        Visit.Id,
      instrument: Instrument
    ) extends InsertAtomResponse

    case class Success(
      aid: Atom.Id
    ) extends InsertAtomResponse

  }

  sealed trait InsertStepResponse extends Product with Serializable

  object InsertStepResponse {

    case class AtomNotFound(
      aid:        Atom.Id,
      instrument: Instrument
    ) extends InsertStepResponse

    case class Success(
      sid: Step.Id
    ) extends InsertStepResponse
  }

  def instantiate[F[_]: Concurrent: UUIDGen](using Services[F]): SequenceService[F] =
    new SequenceService[F] {

      override def selectGmosNorthStepRecords(
        observationId: Observation.Id
      ): Stream[F, StepRecord[GmosNorth]] =
        gmosSequenceService.selectGmosNorthStepRecords(observationId)

      override def selectGmosSouthStepRecords(
        observationId: Observation.Id
      ): Stream[F, StepRecord[GmosSouth]] =
        gmosSequenceService.selectGmosSouthStepRecords(observationId)

      /**
       * We'll need to estimate the cost of executing the next step.  For that
       * we have to find the static config, the last gcal step (if any), the
       * last science step (if any) and the last step in general (if any).
       * This will serve as an input to the time estimate calculator so that it
       * can compare a new step being recorded with the previous state and
       * determine the cost of making the prescribed changes.
       */
      private def selectEstimatorState[S, D](
        observationId: Observation.Id,
        staticConfig:  Visit.Id => F[Option[S]],
        dynamicConfig: Step.Id => F[Option[D]]
      )(using Transaction[F]): F[Option[(S, EstimatorState[D])]] =
        for {
          vid     <- session.option(Statements.SelectLastVisit)(observationId)
          static  <- vid.flatTraverse(staticConfig)
          gcal    <- session.option(Statements.SelectLastGcalConfig)(observationId)
          science <- session.option(Statements.SelectLastScienceConfig)(observationId)
          step    <- session.option(Statements.SelectLastStepConfig)(observationId)
          dynamic <- step.flatTraverse { case (id, _, _) => dynamicConfig(id) }
        } yield static.tupleRight(
          EstimatorState(
            gcal,
            science,
            (step, dynamic).mapN { case ((_, stepConfig, observeClass), d) =>
              ProtoStep(d, stepConfig, observeClass)
            }
          )
        )

      private def selectGmosNorthEstimatorState(
        observationId: Observation.Id
      )(using Transaction[F]): F[Option[(GmosNorthStatic, EstimatorState[GmosNorth])]] =
        selectEstimatorState(
          observationId,
          services.gmosSequenceService.selectGmosNorthStatic,
          services.gmosSequenceService.selectGmosNorthDynamicForStep
        )

      private def selectGmosSouthEstimatorState(
        observationId: Observation.Id
      )(using Transaction[F]): F[Option[(GmosSouthStatic, EstimatorState[GmosSouth])]] =
        selectEstimatorState(
          observationId,
          services.gmosSequenceService.selectGmosSouthStatic,
          services.gmosSequenceService.selectGmosSouthDynamicForStep
        )

      override def abandonAtomsAndStepsForObservation(
        observationId: Observation.Id
      )(using Transaction[F], Services.ServiceAccess): F[Unit] =
        for {
          _ <- session.execute(Statements.AbandonAllNonTerminalAtomsForObservation)(observationId)
          _ <- session.execute(Statements.AbandonAllNonTerminalStepsForObservation)(observationId)
        } yield ()

      override def setAtomExecutionState(
        atomId: Atom.Id,
        stage:  AtomStage
      )(using Transaction[F], Services.ServiceAccess): F[Unit] = {
        val state = stage match {
          case AtomStage.StartAtom => AtomExecutionState.Ongoing
          case AtomStage.EndAtom   => AtomExecutionState.Completed
        }
        session.execute(Statements.SetAtomExecutionState)(state, atomId).void
      }

      override def abandonOngoingAtomsExcept(
        observationId: Observation.Id,
        atomId:        Atom.Id
      )(using Transaction[F], Services.ServiceAccess): F[Unit] =
        for {
          _ <- session.execute(Statements.AbandonOngoingAtomsWithoutAtomId)(observationId, atomId)
          _ <- session.execute(Statements.AbandonOngoingStepsWithoutAtomId)(observationId, atomId)
        } yield ()

      override def setStepExecutionState(
        stepId: Step.Id,
        stage:  StepStage,
        time:   Timestamp
      )(using Transaction[F]): F[Unit] = {
        val state = stage match {
          case StepStage.EndStep => StepExecutionState.Completed
          case StepStage.Abort   => StepExecutionState.Aborted
          case StepStage.Stop    => StepExecutionState.Stopped
          case _                 => StepExecutionState.Ongoing
        }
        val completedTime = Option.when(stage === StepStage.EndStep)(time)
        session.execute(Statements.SetStepExecutionState)(state, completedTime, stepId).void
      }

      override def abandonOngoingStepsExcept(
        observationId: Observation.Id,
        atomId:        Atom.Id,
        stepId:        Step.Id
      )(using Transaction[F], Services.ServiceAccess): F[Unit] =
        for {
          _ <- session.execute(Statements.AbandonOngoingAtomsWithoutAtomId)(observationId, atomId)
          _ <- session.execute(Statements.AbandonOngoingStepsWithoutStepId)(observationId, stepId)
        } yield ()

      def insertAtomRecordImpl(
        visitId:      Visit.Id,
        instrument:   Instrument,
        stepCount:    NonNegShort,
        sequenceType: SequenceType,
        generatedId:  Option[Atom.Id]
      )(using Transaction[F], Services.ServiceAccess): F[InsertAtomResponse] =
        val v = visitService.select(visitId).map(_.filter(_.instrument === instrument))
        (for {
          inv <- EitherT.fromOptionF(v, InsertAtomResponse.VisitNotFound(visitId, instrument))
          aid <- EitherT.right[InsertAtomResponse](UUIDGen[F].randomUUID.map(Atom.Id.fromUuid))
          _   <- EitherT.right[InsertAtomResponse](session.execute(Statements.InsertAtom)(aid, inv.observationId, visitId, instrument, stepCount, sequenceType, generatedId))
        } yield InsertAtomResponse.Success(aid)).merge

      override def insertAtomRecord(
        visitId:      Visit.Id,
        instrument:   Instrument,
        stepCount:    NonNegShort,
        sequenceType: SequenceType,
        generatedId:  Option[Atom.Id]
      )(using Transaction[F], Services.ServiceAccess): F[Result[Atom.Id]] =
        insertAtomRecordImpl(visitId, instrument, stepCount, sequenceType, generatedId).map:
          case InsertAtomResponse.VisitNotFound(id, instrument) => OdbError.InvalidVisit(id, Some(s"Visit '$id' not found or is not a ${instrument.longName} visit")).asFailure
          case InsertAtomResponse.Success(aid)                  => Result.success(aid)

      import InsertStepResponse.*

      private def insertStepConfig(
        stepId:     Step.Id,
        stepConfig: StepConfig
      ): F[Unit] =
        stepConfig match {
          case StepConfig.Bias | StepConfig.Dark => Applicative[F].unit
          case s @ StepConfig.Gcal(_, _, _, _)   => session.execute(Statements.InsertStepConfigGcal)(stepId, s).void
          case s @ StepConfig.Science(_, _)      => session.execute(Statements.InsertStepConfigScience)(stepId, s).void
          case s @ StepConfig.SmartGcal(_)       => session.execute(Statements.InsertStepConfigSmartGcal)(stepId, s).void
        }

      private def insertStepRecord[S, D](
        atomId:              Atom.Id,
        instrument:          Instrument,
        stepConfig:          StepConfig,
        observeClass:        ObserveClass,
        generatedId:         Option[Step.Id],
        timeEstimate:        (S, EstimatorState[D]) => StepEstimate,
        estimatorState:      Observation.Id => F[Option[(S, EstimatorState[D])]],
        insertDynamicConfig: Step.Id => F[Unit]
      )(using Transaction[F], Services.ServiceAccess): F[Result[Step.Id]] =
        val foo = session.option(Statements.SelectObservationId)((atomId, instrument))
        val fos = OptionT(foo).flatMap(o => OptionT(estimatorState(o))).value
        (for {
          sid <- EitherT.right(UUIDGen[F].randomUUID.map(Step.Id.fromUuid))
          es  <- EitherT.fromOptionF(fos, AtomNotFound(atomId, instrument))
          _   <- EitherT.right(session.execute(Statements.InsertStep)(
                   sid, atomId, instrument, stepConfig.stepType, observeClass, generatedId, timeEstimate.tupled(es).total
                 )).void
          _   <- EitherT.right(insertStepConfig(sid, stepConfig))
          _   <- EitherT.right(insertDynamicConfig(sid))
        } yield Success(sid)).merge.map:
          case AtomNotFound(id, instrument)  => OdbError.InvalidAtom(id, Some(s"Atom '$id' not found or is not a ${instrument.longName} atom")).asFailure
          case Success(sid)                  => Result(sid)

      override def insertGmosNorthStepRecord(
        atomId:         Atom.Id,
        dynamicConfig:  GmosNorth,
        stepConfig:     StepConfig,
        observeClass:   ObserveClass,
        generatedId:    Option[Step.Id],
        timeCalculator: TimeEstimateCalculator[GmosNorthStatic, GmosNorth]
      )(using Transaction[F], Services.ServiceAccess): F[Result[Step.Id]] =
        insertStepRecord(
          atomId,
          Instrument.GmosNorth,
          stepConfig,
          observeClass,
          generatedId,
          timeCalculator.estimateStep(_, _, ProtoStep(dynamicConfig, stepConfig, observeClass)),
          selectGmosNorthEstimatorState,
          sid => gmosSequenceService.insertGmosNorthDynamic(sid, dynamicConfig)
        )

      override def insertGmosSouthStepRecord(
        atomId:         Atom.Id,
        dynamicConfig:  GmosSouth,
        stepConfig:     StepConfig,
        observeClass:   ObserveClass,
        generatedId:    Option[Step.Id],
        timeCalculator: TimeEstimateCalculator[GmosSouthStatic, GmosSouth]
      )(using Transaction[F], Services.ServiceAccess): F[Result[Step.Id]] =
        insertStepRecord(
          atomId,
          Instrument.GmosSouth,
          stepConfig,
          observeClass,
          generatedId,
          timeCalculator.estimateStep(_, _, ProtoStep(dynamicConfig, stepConfig, observeClass)),
          selectGmosSouthEstimatorState,
          sid => gmosSequenceService.insertGmosSouthDynamic(sid, dynamicConfig)
        )
    }

  object Statements {

    val SelectObservationId: Query[(Atom.Id, Instrument), Observation.Id] =
      sql"""
        SELECT c_observation_id
          FROM t_atom_record
         WHERE c_atom_id = $atom_id AND c_instrument = $instrument
      """.query(observation_id)

    val InsertAtom: Command[(
      Atom.Id,
      Observation.Id,
      Visit.Id,
      Instrument,
      NonNegShort,
      SequenceType,
      Option[Atom.Id]
    )] =
      sql"""
        INSERT INTO t_atom_record (
          c_atom_id,
          c_observation_id,
          c_visit_id,
          c_instrument,
          c_step_count,
          c_sequence_type,
          c_generated_id
        ) SELECT
          $atom_id,
          $observation_id,
          $visit_id,
          $instrument,
          $int2_nonneg,
          $sequence_type,
          ${atom_id.opt}
      """.command

    val InsertStep: Command[(
      Step.Id,
      Atom.Id,
      Instrument,
      StepType,
      ObserveClass,
      Option[Step.Id],
      TimeSpan,
    )] =
      sql"""
        INSERT INTO t_step_record (
          c_step_id,
          c_step_index,
          c_atom_id,
          c_instrument,
          c_step_type,
          c_observe_class,
          c_generated_id,
          c_time_estimate
        ) SELECT
          $step_id,
          COALESCE(
            (SELECT MAX(c_step_index) + 1
             FROM t_step_record AS s
             INNER JOIN t_atom_record AS a ON a.c_atom_id = s.c_atom_id
             WHERE a.c_observation_id = (SELECT c_observation_id FROM t_atom_record WHERE c_atom_id = $atom_id)
            ),
            1
          ),
          $atom_id,
          $instrument,
          $step_type,
          $obs_class,
          ${step_id.opt},
          $time_span
      """.command.contramap { (s, a, i, t, c, g, d) => (s, a, a, i, t, c, g, d) }

    def encodeColumns(prefix: Option[String], columns: List[String]): String =
      columns.map(c => s"${prefix.foldMap(_ + ".")}$c").intercalate(",\n")

    private def insertStepConfigFragment(table: String, columns: List[String]): Fragment[Void] =
      sql"""
        INSERT INTO #$table (
          c_step_id,
          #${encodeColumns(none, columns)}
        )
      """

    private val StepConfigGcalColumns: List[String] =
      List(
        "c_gcal_continuum",
        "c_gcal_ar_arc",
        "c_gcal_cuar_arc",
        "c_gcal_thar_arc",
        "c_gcal_xe_arc",
        "c_gcal_filter",
        "c_gcal_diffuser",
        "c_gcal_shutter"
      )

    val InsertStepConfigGcal: Command[(Step.Id, StepConfig.Gcal)] =
      sql"""
        ${insertStepConfigFragment("t_step_config_gcal", StepConfigGcalColumns)} SELECT
          $step_id,
          $step_config_gcal
      """.command

    private val StepConfigScienceColumns: List[String] =
      List(
        "c_offset_p",
        "c_offset_q",
        "c_guide_state"
      )

    val InsertStepConfigScience: Command[(Step.Id, StepConfig.Science)] =
      sql"""
        ${insertStepConfigFragment("t_step_config_science", StepConfigScienceColumns)} SELECT
          $step_id,
          $step_config_science
      """.command

    private val StepConfigSmartGcalColumns: List[String] =
      List(
        "c_smart_gcal_type"
      )

    val InsertStepConfigSmartGcal: Command[(Step.Id, StepConfig.SmartGcal)] =
      sql"""
        ${insertStepConfigFragment("t_step_config_smart_gcal", StepConfigSmartGcalColumns)} SELECT
          $step_id,
          $step_config_smart_gcal
      """.command

    private val step_config: Codec[StepConfig] =
      (
        step_type               *:
        step_config_gcal.opt    *:
        step_config_science.opt *:
        step_config_smart_gcal.opt
      ).eimap { case (stepType, oGcal, oScience, oSmart) =>
        stepType match {
          case StepType.Bias      => StepConfig.Bias.asRight
          case StepType.Dark      => StepConfig.Dark.asRight
          case StepType.Gcal      => oGcal.toRight("Missing gcal step config definition")
          case StepType.Science   => oScience.toRight("Missing science step config definition")
          case StepType.SmartGcal => oSmart.toRight("Missing smart gcal step config definition")
        }
      } { stepConfig =>
        (stepConfig.stepType,
         StepConfig.gcal.getOption(stepConfig),
         StepConfig.science.getOption(stepConfig),
         StepConfig.smartGcal.getOption(stepConfig)
        )
      }

    val SelectStepConfigForObs: Query[Observation.Id, (Step.Id, StepConfig)] =
      (sql"""
        SELECT
          v.c_step_id,
          v.c_step_type,
          #${encodeColumns("v".some, StepConfigGcalColumns)},
          #${encodeColumns("v".some, StepConfigScienceColumns)},
          #${encodeColumns("v".some, StepConfigSmartGcalColumns)}
        FROM v_step_record v
        INNER JOIN t_atom_record a ON a.c_atom_id = v.c_atom_id
        WHERE """ ~> sql"""a.c_observation_id = $observation_id"""
      ).query(step_id *: step_config)

    private def step_record[D](dynamic_config: Decoder[D]): Decoder[StepRecord[D]] =
      (
        step_id              *:
        atom_id              *:
        visit_id             *:
        int4_pos             *:
        step_config          *:
        instrument           *:
        dynamic_config       *:
        core_timestamp       *:
        sequence_type        *:
        obs_class            *:
        step_execution_state *:
        dataset_qa_state.opt
      ).to[StepRecord[D]]

    def selectStepRecord[D](
      instTable:   String,
      instAlias:   String,
      instColumns: List[String],
      instDecoder: Decoder[D]
    ): Query[Observation.Id, StepRecord[D]] =
      (sql"""
        SELECT
          v.c_step_id,
          v.c_atom_id,
          v.c_visit_id,
          v.c_step_index,
          v.c_step_type,
          #${encodeColumns("v".some, StepConfigGcalColumns)},
          #${encodeColumns("v".some, StepConfigScienceColumns)},
          #${encodeColumns("v".some, StepConfigSmartGcalColumns)},
          v.c_instrument,
          #${encodeColumns(instAlias.some, instColumns)},
          v.c_created,
          a.c_sequence_type,
          v.c_observe_class,
          v.c_execution_state,
          v.c_qa_state
        FROM v_step_record v
        INNER JOIN #$instTable #$instAlias ON #$instAlias.c_step_id = v.c_step_id
        INNER JOIN t_atom_record a ON a.c_atom_id = v.c_atom_id
        WHERE """ ~> sql"""a.c_observation_id = $observation_id"""
      ).query(step_record(instDecoder)) //id *: step_config *: instDecoder *: core_timestamp)

    val SetStepExecutionState: Command[(StepExecutionState, Option[Timestamp], Step.Id)] =
      sql"""
        UPDATE t_step_record s
           SET c_execution_state = $step_execution_state,
               c_completed       = ${core_timestamp.opt}
          FROM t_step_execution_state e
         WHERE s.c_execution_state = e.c_tag
           AND e.c_terminal = FALSE
           AND s.c_step_id = $step_id
      """.command

    val AbandonAllNonTerminalStepsForObservation: Command[Observation.Id] =
      sql"""
        UPDATE t_step_record s
           SET c_execution_state = 'abandoned'
          FROM t_atom_record a, t_step_execution_state e
         WHERE s.c_atom_id = a.c_atom_id
           AND s.c_execution_state = e.c_tag
           AND a.c_observation_id = $observation_id
           AND e.c_terminal = FALSE
      """.command

    val AbandonOngoingStepsWithoutStepId: Command[(Observation.Id, Step.Id)] =
      sql"""
        UPDATE t_step_record s
           SET c_execution_state = 'abandoned'
          FROM t_atom_record a
         WHERE s.c_atom_id = a.c_atom_id
           AND a.c_observation_id = $observation_id
           AND s.c_step_id != $step_id
           AND s.c_execution_state = 'ongoing';
      """.command

    val AbandonOngoingStepsWithoutAtomId: Command[(Observation.Id, Atom.Id)] =
      sql"""
        UPDATE t_step_record s
           SET c_execution_state = 'abandoned'
          FROM t_atom_record a
         WHERE s.c_atom_id = a.c_atom_id
           AND a.c_observation_id = $observation_id
           AND a.c_atom_id != $atom_id
           AND s.c_execution_state = 'ongoing';
      """.command

    val SetAtomExecutionState: Command[(AtomExecutionState, Atom.Id)] =
      sql"""
        UPDATE t_atom_record a
           SET c_execution_state = $atom_execution_state
          FROM t_atom_execution_state e
         WHERE a.c_execution_state = e.c_tag
           AND e.c_terminal = FALSE
           AND a.c_atom_id = $atom_id
      """.command

    val AbandonAllNonTerminalAtomsForObservation: Command[Observation.Id] =
      sql"""
        UPDATE t_atom_record a
           SET c_execution_state = 'abandoned'
          FROM t_atom_execution_state e
         WHERE a.c_execution_state = e.c_tag
           AND a.c_observation_id = $observation_id
           AND e.c_terminal = FALSE
      """.command

    val AbandonOngoingAtomsWithoutAtomId: Command[(Observation.Id, Atom.Id)] =
      sql"""
        UPDATE t_atom_record
           SET c_execution_state = 'abandoned'
         WHERE c_observation_id = $observation_id
           AND c_atom_id != $atom_id
           AND c_execution_state = 'ongoing';
      """.command

    val SelectLastVisit: Query[Observation.Id, Visit.Id] =
      sql"""
        SELECT c_visit_id
          FROM t_visit
         WHERE c_observation_id = $observation_id
      ORDER BY c_created DESC
         LIMIT 1
      """.query(visit_id)

    val SelectLastGcalConfig: Query[Observation.Id, StepConfig.Gcal] =
      sql"""
        SELECT
          #${encodeColumns("s".some, StepConfigGcalColumns)}
        FROM v_step_record s
        INNER JOIN t_atom_record a ON a.c_atom_id = s.c_atom_id
        WHERE
          a.c_observation_id = $observation_id AND
          s.c_step_type      = 'gcal'
        ORDER BY s.c_created DESC
        LIMIT 1
      """.query(step_config_gcal)

    val SelectLastScienceConfig: Query[Observation.Id, StepConfig.Science] =
      sql"""
        SELECT
          #${encodeColumns("s".some, StepConfigScienceColumns)}
        FROM v_step_record s
        INNER JOIN t_atom_record a ON a.c_atom_id = s.c_atom_id
        WHERE
          a.c_observation_id = $observation_id AND
          s.c_step_type      = 'science'
        ORDER BY s.c_created DESC
        LIMIT 1
      """.query(step_config_science)

    val SelectLastStepConfig: Query[Observation.Id, (Step.Id, StepConfig, ObserveClass)] =
      sql"""
        SELECT
          s.c_step_id,
          s.c_step_type,
          #${encodeColumns("s".some, StepConfigGcalColumns)},
          #${encodeColumns("s".some, StepConfigScienceColumns)},
          #${encodeColumns("s".some, StepConfigSmartGcalColumns)},
          s.c_observe_class
        FROM v_step_record s
        INNER JOIN t_atom_record a ON a.c_atom_id = s.c_atom_id
        WHERE
          a.c_observation_id = $observation_id
        ORDER BY s.c_created DESC
        LIMIT 1
      """.query(step_id *: step_config *: obs_class)

  }
}
