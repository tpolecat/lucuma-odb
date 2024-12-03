// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.service

import cats.data.NonEmptyList
import cats.effect.Concurrent
import cats.implicits.*
import grackle.Result
import grackle.ResultT
import lucuma.core.enums.CalibrationRole
import lucuma.core.enums.ConfigurationRequestStatus
import lucuma.core.enums.ExecutionState
import lucuma.core.enums.Instrument
import lucuma.core.enums.ObservationValidationCode
import lucuma.core.enums.ObservationWorkflowState
import lucuma.core.enums.ScienceBand
import lucuma.core.enums.Site
import lucuma.core.enums.Site.GN
import lucuma.core.enums.Site.GS
import lucuma.core.math.Coordinates
import lucuma.core.math.Declination
import lucuma.core.math.RightAscension
import lucuma.core.model.CallForProposals
import lucuma.core.model.ObjectTracking
import lucuma.core.model.Observation
import lucuma.core.model.ObservationValidation
import lucuma.core.model.ObservationWorkflow
import lucuma.core.model.ObservingNight
import lucuma.core.model.Program
import lucuma.core.model.StandardRole.*
import lucuma.core.model.Target
import lucuma.core.syntax.string.*
import lucuma.core.util.DateInterval
import lucuma.core.util.Enumerated
import lucuma.itc.client.ItcClient
import lucuma.odb.data.ObservationValidationMap
import lucuma.odb.data.OdbError
import lucuma.odb.data.OdbErrorExtensions.*
import lucuma.odb.data.Tag
import lucuma.odb.graphql.enums.Enums
import lucuma.odb.logic.TimeEstimateCalculatorImplementation
import lucuma.odb.sequence.data.GeneratorParams
import lucuma.odb.sequence.data.MissingParamSet
import lucuma.odb.sequence.util.CommitHash
import lucuma.odb.service.GeneratorParamsService.Error as GenParamsError
import lucuma.odb.syntax.instrument.*
import lucuma.odb.util.Codecs.*
import natchez.Trace
import skunk.*
import skunk.implicits.*

import java.time.Duration
import java.time.Instant

import Services.Syntax.*
import lucuma.odb.service.Services.SuperUserAccess

sealed trait ObservationWorkflowService[F[_]] {

  def getWorkflows(
    oids: List[Observation.Id],
    commitHash: CommitHash,
    itcClient: ItcClient[F],
    ptc: TimeEstimateCalculatorImplementation.ForInstrumentMode
  )(using NoTransaction[F]): F[Result[Map[Observation.Id, ObservationWorkflow]]]

  def setWorkflowState(
    oid: Observation.Id,
    state: ObservationWorkflowState,
    commitHash: CommitHash,
    itcClient: ItcClient[F],
    ptc: TimeEstimateCalculatorImplementation.ForInstrumentMode
  )(using NoTransaction[F]): F[Result[ObservationWorkflow]]

}

/* Validation Info Record */
case class ObservationValidationInfo(
  pid: Program.Id,
  oid: Observation.Id,
  instrument: Option[Instrument],
  ra: Option[RightAscension],
  dec: Option[Declination],
  role: Option[CalibrationRole],
  userState: Option[ObservationWorkflowService.UserState],
  proposalStatus: Tag,
  cfpid: Option[CallForProposals.Id],
  scienceBand: Option[ScienceBand],
  asterism: List[Target],
) {

  /* Has the proposal been accepted? */
  def isAccepted(using enums: Enums): Result[Boolean] =
    Result.fromOption(
      Enumerated[enums.ProposalStatus].fromTag(proposalStatus.value).map(_ === enums.ProposalStatus.Accepted),
      s"Unexpected enum value for ProposalStatus: ${proposalStatus.value}"
    )

  def explicitBase: Option[Coordinates] =
    (ra, dec).mapN(Coordinates.apply)

  def site: Set[Site] =
    instrument.foldMap(_.site)

  def coordinatesAt(when: Instant): Option[Coordinates] =
    explicitBase.orElse:
      for
        ast <- NonEmptyList.fromList(asterism)
        tracking  = ObjectTracking.fromAsterism(ast)
        coordsAt <- tracking.at(when)
      yield coordsAt.value

}

case class CfpInfo(
  cfpid: CallForProposals.Id,
  raStartNorth: RightAscension,
  raEndNorth: RightAscension,
  decStartNorth: Declination,
  decEndNorth: Declination,
  raStartSouth: RightAscension,
  raEndSouth: RightAscension,
  decStartSouth: Declination,
  decEndSouth: Declination,
  active: DateInterval,
  instruments: List[Instrument]
) {

  def raStart(at: Site): RightAscension =
    at match
      case GN => raStartNorth
      case GS => raStartSouth

  def raEnd(at: Site): RightAscension =
    at match
      case GN => raEndNorth
      case GS => raEndSouth

  def decStart(at: Site): Declination =
    at match
      case GN => decStartNorth
      case GS => decStartSouth

  def decEnd(at: Site): Declination =
    at match
      case GN => decEndNorth
      case GS => decEndSouth

  def midpoint(at: Site): Instant =
    val start    = ObservingNight.fromSiteAndLocalDate(at, active.start).start
    val end      = ObservingNight.fromSiteAndLocalDate(at, active.end).end
    val duration = Duration.between(start, end)
    start.plus(duration.dividedBy(2L))

  def addInstrument(insts: Option[Instrument]): CfpInfo =
    insts.fold(this)(inst => copy(instruments = inst :: instruments))
}


object ObservationWorkflowService {

  // Construct some finer-grained types to make it harder to do something dumb in the status computation.
  import ObservationWorkflowState.*
  type UserState       = Inactive.type  | Ready.type
  type ExecutionState  = Ongoing.type   | Completed.type
  type ValidationState = Undefined.type | Unapproved.type | Defined.type

  /* Validation Messages */
  object Messages {

    val CoordinatesOutOfRange = "Base coordinates out of Call for Proposals limits."

    def invalidInstrument(instr: Instrument): String =
      s"Instrument $instr not part of Call for Proposals."

    def invalidScienceBand(b: ScienceBand): String =
      s"Science Band ${b.tag.toScreamingSnakeCase} has no time allocation."
  }

  extension (ws: ObservationWorkflowState) def asUserState: Option[UserState] =
    ws match
      case Inactive => Some(Inactive)
      case Ready    => Some(Ready)
      case _        => None

  extension [A,B,C](m: Map[A, Either[B, C]]) def separateValues: (Map[A, B], Map[A, C]) =
    m.foldLeft((Map.empty[A,B], Map.empty[A,C])):
      case ((ls, rs), (a, Left(b)))  => (ls + (a -> b), rs)
      case ((ls, rs), (a, Right(c))) => (ls, rs + (a -> c))

  extension (ra: RightAscension)
    private def isInInterval(raStart: RightAscension, raEnd: RightAscension): Boolean =
      if (raStart > raEnd) raStart <= ra || ra <= raEnd
      else raStart <= ra && ra <= raEnd

  extension (dec: Declination)
    private def isInInterval(decStart: Declination, decEnd: Declination): Boolean =
      decStart <= dec && dec <= decEnd

  extension (mp: MissingParamSet)
    def toObsValidation: ObservationValidation =
      ObservationValidation.configuration(s"Missing ${mp.params.map(_.name).toList.intercalate(", ")}")

  extension (ge: GeneratorParamsService.Error)
    private def toObsValidation: ObservationValidation = ge match
      case GenParamsError.MissingData(p) => p.toObsValidation
      case _                             => ObservationValidation.configuration(ge.format)

  /* Construct an instance. */
  def instantiate[F[_]: Concurrent: Trace](using Services[F]): ObservationWorkflowService[F] =
    new ObservationWorkflowService[F] {

      // Make the enums available in a stable and implicit way
      given Enums = enums

      /** Retrieve the generator params/errors for many observations. */
      private def generatorParamss(
        infos: List[ObservationValidationInfo]
      )(using Transaction[F], SuperUserAccess): F[Map[Observation.Id, Either[ObservationValidationMap, GeneratorParams]]] =
        Services.asSuperUser: // TODO: 
          generatorParamsService
            .selectMany(infos.map(_.oid))
            .map: results =>
              results
                .view
                .mapValues:
                  case Left(error)                           => ObservationValidationMap.singleton(error.toObsValidation).asLeft
                  case Right(GeneratorParams(Left(m), _, _)) => ObservationValidationMap.singleton(m.toObsValidation).asLeft
                  case Right(ps)                             => ps.asRight
                .toMap

        // infos
        //   .groupBy(_.pid)
        //   .view
        //   .mapValues(_.map(_.oid))
        //   .toList
        //   .traverse: (pid, oids) =>
        //     generatorParamsService
        //       .selectMany(pid, oids) // TODO: this still ends up going program by program … need a more general batch method here
        //       .map: results =>
        //         results
        //           .view
        //           .mapValues:
        //             case Left(error)                           => ObservationValidationMap.singleton(error.toObsValidation).asLeft
        //             case Right(GeneratorParams(Left(m), _, _)) => ObservationValidationMap.singleton(m.toObsValidation).asLeft
        //             case Right(ps)                             => ps.asRight
        //           .toList
        //   .map(_.combineAll.toMap)

      /* Validate that an observation is compatible with its program's CFP. */
      private def cfpValidationss(infos: List[ObservationValidationInfo])(using Transaction[F]): F[Map[Observation.Id, ObservationValidationMap]] = {

        def inst(info: ObservationValidationInfo, cfp: CfpInfo): ObservationValidationMap =
          if cfp.instruments.isEmpty then ObservationValidationMap.empty // weird but original logic does this
          else info.instrument match
            case None       => ObservationValidationMap.empty
            case Some(inst) =>
              if cfp.instruments.contains(inst) then ObservationValidationMap.empty
              else ObservationValidationMap.singleton(ObservationValidation.callForProposals(Messages.invalidInstrument(inst)))

        def radec(info: ObservationValidationInfo, cfp: CfpInfo): ObservationValidationMap =
          info.site.headOption.foldMap: site =>
            info.coordinatesAt(cfp.midpoint(site)).foldMap: coords =>
              val ok = coords.ra.isInInterval(cfp.raStart(site), cfp.raEnd(site)) && coords.dec.isInInterval(cfp.decStart(site), cfp.decEnd(site))
              if ok then ObservationValidationMap.empty
              else ObservationValidationMap.singleton(ObservationValidation.callForProposals(Messages.CoordinatesOutOfRange))

        cfpInfos(infos.flatMap(_.cfpid).distinct).map: cfpInfos =>
          infos.foldMap: info =>
            info.cfpid.map(cfpInfos) match
              case None      => Map(info.oid -> ObservationValidationMap.empty)
              case Some(cfp) => Map(info.oid -> (inst(info, cfp) |+| radec(info, cfp)))

      }

      private def obsInfos(oids: List[Observation.Id])(using Transaction[F]): ResultT[F, Map[Observation.Id, ObservationValidationInfo]] =
        NonEmptyList.fromList(oids).fold(ResultT.success(Map.empty)): oids =>
          ResultT.liftF:
            asterismService.getAsterisms(oids.toList).flatMap: asterisms =>
              val enc = observation_id.nel(oids)
              session
                .stream(Statements.ObservationValidationInfosWithoutAsterisms(enc))(oids, 1024)
                .compile
                .toList
                .map: infos =>
                  infos
                    .map: info =>
                      val oid = info.oid
                      val ast = asterisms.get(info.oid).foldMap(_.map(_._2))
                      oid -> info.copy(asterism = ast)
                    .toMap

      private def cfpInfos(ids: List[CallForProposals.Id])(using Transaction[F]): F[Map[CallForProposals.Id, CfpInfo]] =
        NonEmptyList.fromList(ids).fold(Map.empty.pure[F]): nel =>
          val enc = cfp_id.nel(nel)
          session
            .stream(Statements.CfpInfos(enc))(nel, 1024)
            .compile
            // We get a row per instrument, so we need to group by CFP ID and fold the instruments
            .fold(Map.empty[CallForProposals.Id, CfpInfo]) {
              case (m, (cfp, oinst)) =>
                m.updatedWith(cfp.cfpid) {
                  case None    => cfp.addInstrument(oinst).some
                  case Some(c) => c.addInstrument(oinst).some
                }
            }

      private def itcValidationss(
        params: List[(ObservationValidationInfo, GeneratorParams)],
        itcClient: ItcClient[F],
      )(using Transaction[F]): F[Map[Observation.Id, ObservationValidationMap]] =
          params
            .groupMap((info, _) => info.pid)((info, params) => (info.oid, params))
            .view
            .mapValues(_.toMap)
            .toList
            .traverse: (pid, map) =>
              itcService(itcClient).selectAll(pid, map).map(_.keySet) // TODO: generalize so we don't have to go program by program
            .map: list =>
              val set = list.combineAll
              params.foldMap: (info, _) =>
                val key = info.oid
                val value =
                  if set(key) then ObservationValidationMap.empty
                  else ObservationValidationMap.singleton(ObservationValidation.itc("ITC results are not present."))
                Map(key -> value)

      private def programAllocations(
        pids: List[Program.Id]
      )(using Transaction[F]): F[Map[Program.Id, NonEmptyList[ScienceBand]]] =
        NonEmptyList.fromList(pids) match
          case None => Map.empty.pure[F]
          case Some(nel) =>
            val enc = program_id.nel(nel)
            session
              .stream(Statements.ProgramAllocations(enc))(nel, 1024)
              .compile
              .toList
              .map: list =>
                list.foldMap: (pid, band) =>
                  Map(pid -> NonEmptyList.one(band))

      private def validateScienceBands(
        infos: List[ObservationValidationInfo]
      )(using Transaction[F]): F[Map[Observation.Id, ObservationValidationMap]] =
        programAllocations(infos.map(_.pid).distinct).map: allocs =>
          infos.foldMap: info =>
            val key = info.oid
            val value: ObservationValidationMap =
              (info.scienceBand, allocs.get(info.pid)).tupled.foldMap: (b, bs) =>
                if bs.toList.contains(b) then ObservationValidationMap.empty
                else ObservationValidationMap.singleton(ObservationValidation.configuration(Messages.invalidScienceBand(b)))
            Map(key -> value)

      private def validateConfigurations(infos: List[ObservationValidationInfo])(using Transaction[F]): ResultT[F, Map[Observation.Id, ObservationValidationMap]] =
        ResultT(configurationService.selectRequests(infos.map(i => (i.pid, i.oid)))).map: rs =>
          rs.view
            .map:
              case ((_, oid), lst) =>
                oid -> {
                  val m = ObservationValidationMap.empty
                  if lst.isEmpty then m.add(ObservationValidation.configurationRequestNotRequested)
                  else if lst.exists(_.status === ConfigurationRequestStatus.Approved) then m
                  else if lst.forall(_.status === ConfigurationRequestStatus.Denied) then m.add(ObservationValidation.configurationRequestDenied)
                  else m.add(ObservationValidation.configurationRequestPending)
                }
            .toMap

      private def executionState(
        info: ObservationValidationInfo,
        commitHash: CommitHash,
        itcClient: ItcClient[F],
        ptc: TimeEstimateCalculatorImplementation.ForInstrumentMode
      )(using NoTransaction[F]): F[Option[ExecutionState]] =
        generator(commitHash, itcClient, ptc).executionState(info.pid, info.oid).map:
          case ExecutionState.NotDefined => None
          case ExecutionState.NotStarted => None
          case ExecutionState.Ongoing    => Some(Ongoing)
          case ExecutionState.Completed  => Some(Completed)

      private def executionStates(
        infos: List[ObservationValidationInfo],
        commitHash: CommitHash,
        itcClient: ItcClient[F],
        ptc: TimeEstimateCalculatorImplementation.ForInstrumentMode
      )(using NoTransaction[F]): F[Map[Observation.Id, ExecutionState]] =
        infos
          .traverse(info => executionState(info, commitHash, itcClient, ptc).tupleLeft(info.oid)) // TODO: this is per-obervation :-(, need to generalize
          .map: list =>
            list
              .collect[(Observation.Id, ExecutionState)]:
                case (oid, Some(state)) => oid -> state
              .toMap

      // Compute the observation status, as well as a list of legal transitions,
      private def workflowStateAndTransitions(
        info: ObservationValidationInfo,
        executionState: Option[ExecutionState],
        codes: List[ObservationValidationCode]
      )(using Enums): Result[(ObservationWorkflowState, List[ObservationWorkflowState])] =
        info.isAccepted.map { isAccepted =>

          // A special ordering where codes are ordered as they would occur in a typical lifecycle.
          given Ordering[ObservationValidationCode] =
            Ordering.by:
              case ObservationValidationCode.CallForProposalsError => 1
              case ObservationValidationCode.ConfigurationError => 2
              case ObservationValidationCode.ItcError => 3
              case ObservationValidationCode.ConfigurationRequestUnavailable => 4
              case ObservationValidationCode.ConfigurationRequestNotRequested => 5
              case ObservationValidationCode.ConfigurationRequestDenied => 6
              case ObservationValidationCode.ConfigurationRequestPending => 7

          val validationStatus: ValidationState =
            codes.minOption.fold(Defined):
              case ObservationValidationCode.CallForProposalsError            |
                   ObservationValidationCode.ConfigurationError               |
                   ObservationValidationCode.ItcError                         => Undefined
              case ObservationValidationCode.ConfigurationRequestUnavailable  |
                   ObservationValidationCode.ConfigurationRequestNotRequested |
                   ObservationValidationCode.ConfigurationRequestDenied       |
                   ObservationValidationCode.ConfigurationRequestPending      => Unapproved

          def userStatus(validationStatus: ValidationState): Option[UserState] =
            if info.role.isDefined then Some(Ready) // Calibrations are immediately ready
            else info.userState

          // Our final state is the execution state (if any), else the user state (if any), else the validation state,
          // with the one exception that user state Inactive overrides execution state Ongoing
          val state: ObservationWorkflowState =
            (executionState, userStatus(validationStatus)) match
              case (None, None)     => validationStatus
              case (None, Some(us)) => us
              case (Some(es), None) => es
              case (Some(Ongoing), Some(Inactive)) => Inactive
              case (Some(es), _)    => es

          val allowedTransitions: List[ObservationWorkflowState] =
            if info.role.isDefined then Nil // User can't set the state for calibrations
            else state match
              case Inactive   => List(executionState.getOrElse(validationStatus))
              case Undefined  => List(Inactive)
              case Unapproved => List(Inactive)
              case Defined    => List(Inactive) ++ Option.when(isAccepted)(Ready)
              case Ready      => List(Inactive, validationStatus)
              case Ongoing    => List(Inactive)
              case Completed  => Nil

          (state, allowedTransitions)

        }

      private def observationValidationsImpls(
        infos: Map[Observation.Id, ObservationValidationInfo],
        itcClient: ItcClient[F],
      )(using Transaction[F]): ResultT[F, Map[Observation.Id, ObservationValidationMap]] = {

        val (itCalibs, itScience) = infos.values.partition(_.role.isDefined)
        val (calibs, science) = (itCalibs.toList, itScience.toList)

        val calibrationValidations: F[Map[Observation.Id, ObservationValidationMap]] =
          calibs.map(_.oid -> ObservationValidationMap.empty).toMap.pure[F]

        val generatorValidations: F[(Map[Observation.Id, ObservationValidationMap], Map[Observation.Id, GeneratorParams])] =
          Services.asSuperUser: // TODO
            generatorParamss(science).map(_.separateValues)

        val cfpValidations: F[Map[Observation.Id, ObservationValidationMap]] =
          cfpValidationss(science)

        val bandValidations: F[Map[Observation.Id, ObservationValidationMap]] =
          validateScienceBands(science)

        def itcValidations(generatorParams: Map[Observation.Id, GeneratorParams]): F[Map[Observation.Id, ObservationValidationMap]] =
          val params = generatorParams.toList.map((oid, ps) => (infos(oid), ps))
          itcValidationss(params, itcClient)

        val preliminaryValidations: F[Map[Observation.Id, ObservationValidationMap]] =
          for
            calV         <- calibrationValidations
            (genV, gens) <- generatorValidations
            cfpV         <- cfpValidations
            bandV        <- bandValidations
            stepOne       = calV |+| genV |+| cfpV |+| bandV
            itcV         <- itcValidations(gens.view.filterKeys(stepOne.get(_).forall(_.isEmpty)).toMap) // skip if already errors
          yield stepOne |+| itcV

        // Final validations
        ResultT.liftF(preliminaryValidations).flatMap { prelimV =>

          val toCheck: List[ObservationValidationInfo] =
            science.filter: info =>
              info.isAccepted.toOption.forall(_ === true) && prelimV.get(info.oid).forall(_.isEmpty)

          val configValidations: ResultT[F, Map[Observation.Id, ObservationValidationMap]] =
            validateConfigurations(toCheck)

          configValidations.map(prelimV |+| _)

        }

      }

      private def getInfoAndValidations(
        oids: List[Observation.Id],
        itcClient: ItcClient[F]
      )(using NoTransaction[F]): ResultT[F, Map[Observation.Id, (ObservationValidationInfo, ObservationValidationMap)]] =
        services.transactionallyT:
          for
            infos <- obsInfos(oids)
            ress  <- observationValidationsImpls(infos, itcClient)
          yield (infos, ress).tupled

      private def getWorkflowImpl(
        oid: Observation.Id,
        commitHash: CommitHash,
        itcClient: ItcClient[F],
        ptc: TimeEstimateCalculatorImplementation.ForInstrumentMode
      )(using NoTransaction[F]): ResultT[F, ObservationWorkflow] =
        getWorkflowsImpl(List(oid), commitHash, itcClient, ptc).map(_(oid))

      private def getWorkflowsImpl(
        oids: List[Observation.Id],
        commitHash: CommitHash,
        itcClient: ItcClient[F],
        ptc: TimeEstimateCalculatorImplementation.ForInstrumentMode
      )(using NoTransaction[F]): ResultT[F, Map[Observation.Id, ObservationWorkflow]] =
        getInfoAndValidations(oids, itcClient).flatMap: map =>
          val infos = map.values.map(_._1).toList
          ResultT.liftF(executionStates(infos, commitHash, itcClient, ptc)).flatMap: exs =>
            ResultT.fromResult:
              map
                .toList
                .traverse:
                  case (oid, (info, errMap)) =>
                    val errs = errMap.toList
                    workflowStateAndTransitions(info, exs.get(oid), errs.map(_.code))
                      .map: (s, ss) =>
                        oid -> ObservationWorkflow(s, ss, errs)
                .map(_.toMap)

      override def getWorkflows(
        oids: List[Observation.Id],
        commitHash: CommitHash,
        itcClient: ItcClient[F],
        ptc: TimeEstimateCalculatorImplementation.ForInstrumentMode
      )(using NoTransaction[F]): F[Result[Map[Observation.Id, ObservationWorkflow]]] =
        getWorkflowsImpl(oids, commitHash, itcClient, ptc).value

      private def setWorkflowStateImpl(
        oid: Observation.Id,
        state: ObservationWorkflowState,
        commitHash: CommitHash,
        itcClient: ItcClient[F],
        ptc: TimeEstimateCalculatorImplementation.ForInstrumentMode
      )(using NoTransaction[F]): ResultT[F, ObservationWorkflow] =
        getWorkflowImpl(oid, commitHash, itcClient, ptc).flatMap: w =>
          if w.state === state then ResultT.success(w)
          else if !w.validTransitions.contains(state)
          then ResultT.failure(OdbError.InvalidWorkflowTransition(w.state, state).asProblem)
          else ResultT:
            services.transactionally:
              session.prepareR(Statements.UpdateUserState).use: pc =>
                pc.execute(state.asUserState, oid)
                  .as(Result(w.copy(state = state)))

      override def setWorkflowState(
        oid: Observation.Id,
        state: ObservationWorkflowState,
        commitHash: CommitHash,
        itcClient: ItcClient[F],
        ptc: TimeEstimateCalculatorImplementation.ForInstrumentMode
      )(using NoTransaction[F]): F[Result[ObservationWorkflow]] =
        setWorkflowStateImpl(oid, state, commitHash, itcClient, ptc).value

  }

  object Statements {

    def ObservationValidationInfosWithoutAsterisms[A <: NonEmptyList[Observation.Id]](enc: Encoder[A]): Query[A, ObservationValidationInfo] =
      sql"""
        SELECT
          o.c_program_id,
          o.c_observation_id,
          o.c_instrument,
          o.c_explicit_ra,
          o.c_explicit_dec,
          o.c_calibration_role,
          o.c_workflow_user_state,
          p.c_proposal_status,
          x.c_cfp_id,
          o.c_science_band
        FROM t_observation o
        JOIN t_program p on p.c_program_id = o.c_program_id
        LEFT JOIN t_proposal x ON o.c_program_id = x.c_program_id
        WHERE c_observation_id IN ($enc)
      """
      .query(program_id *: observation_id *: instrument.opt *: right_ascension.opt *: declination.opt *: calibration_role.opt *: user_state.opt *: tag *: cfp_id.opt *: science_band.opt)
      .map:
        case (pid, oid, inst, ra, dec, cal, state, tag, cfp, sci) =>
          ObservationValidationInfo(pid, oid, inst, ra, dec, cal, state, tag, cfp, sci, Nil)

    def ProgramAllocations[A <: NonEmptyList[Program.Id]](enc: Encoder[A]): Query[A, (Program.Id, ScienceBand)] =
      sql"""
        SELECT DISTINCT
          c_program_id,
          c_science_band
        FROM
          t_allocation
        WHERE
          c_program_id IN ($enc)
      """.query(program_id *: science_band)

    def CfpInfos[A <: NonEmptyList[CallForProposals.Id]](enc: Encoder[A]): Query[A, (CfpInfo, Option[Instrument])] =
      sql"""
        SELECT
          c.c_cfp_id,
          c.c_north_ra_start,
          c.c_north_ra_end,
          c.c_north_dec_start,
          c.c_north_dec_end,
          c.c_south_ra_start,
          c.c_south_ra_end,
          c.c_south_dec_start,
          c.c_south_dec_end,
          c.c_active_start,
          c.c_active_end,
          i.c_instrument
        FROM t_cfp c
        LEFT JOIN t_cfp_instrument i
        ON c.c_cfp_id = i.c_cfp_id
        WHERE c.c_cfp_id in ($enc)
      """.query(cfp_id *: right_ascension *: right_ascension *: declination *: declination *: right_ascension *: right_ascension *: declination *: declination *: date_interval *: instrument.opt)
        .map:
          case (id, n_ra_s, n_ra_e, n_dec_s, n_dec_e, s_ra_s, s_ra_e, s_dec_s, s_dec_e, active, oinst) =>
            (CfpInfo(id, n_ra_s, n_ra_e, n_dec_s, n_dec_e, s_ra_s, s_ra_e, s_dec_s, s_dec_e, active, Nil), oinst)

    val UpdateUserState: Command[(Option[UserState], Observation.Id)] =
      sql"""
        UPDATE t_observation
        SET c_workflow_user_state = ${user_state.opt}
        WHERE c_observation_id = $observation_id
      """.command

  }

}
