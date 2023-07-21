// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.logic

import cats.data.EitherT
import cats.data.NonEmptyList
import cats.effect.Concurrent
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import eu.timepit.refined.api.Refined
import eu.timepit.refined.api.RefinedTypeOps
import eu.timepit.refined.numeric.Interval
import eu.timepit.refined.types.numeric.PosInt
import fs2.Pure
import fs2.Stream
import lucuma.core.enums.SequenceType
import lucuma.core.math.Offset
import lucuma.core.model.Observation
import lucuma.core.model.Program
import lucuma.core.model.sequence.Atom
import lucuma.core.model.sequence.ExecutionConfig
import lucuma.core.model.sequence.ExecutionSequence
import lucuma.core.model.sequence.InstrumentExecutionConfig
import lucuma.core.model.sequence.PlannedTime
import lucuma.core.model.sequence.SequenceDigest
import lucuma.core.model.sequence.SetupTime
import lucuma.core.model.sequence.Step
import lucuma.core.model.sequence.StepConfig
import lucuma.core.model.sequence.StepEstimate
import lucuma.itc.client.ItcClient
import lucuma.odb.sequence.data.GeneratorParams
import lucuma.odb.sequence.data.ProtoAtom
import lucuma.odb.sequence.data.ProtoExecutionConfig
import lucuma.odb.sequence.data.ProtoStep
import lucuma.odb.sequence.gmos
import lucuma.odb.sequence.util.CommitHash
import lucuma.odb.sequence.util.SequenceIds
import lucuma.odb.service.ItcService
import lucuma.odb.service.NoTransaction
import lucuma.odb.service.Services
import lucuma.odb.service.Services.Syntax.*

import java.util.UUID

import Generator.FutureLimit

sealed trait Generator[F[_]] {

  def lookupAndGenerate(
    programId:     Program.Id,
    observationId: Observation.Id,
    futureLimit:   FutureLimit = FutureLimit.Default
  )(using NoTransaction[F]): F[Either[Generator.Error, Generator.Success]]

  def generate(
    observationId: Observation.Id,
    params:        GeneratorParams,
    resultSet:     ItcService.AsterismResult,
    futureLimit:   FutureLimit = FutureLimit.Default
  )(using NoTransaction[F]): F[Either[Generator.Error, Generator.Success]]

}

object Generator {

  type FutureLimit = Int Refined Interval.Closed[0, 100]

  object FutureLimit extends RefinedTypeOps[FutureLimit, Int] {
    val Default: FutureLimit = unsafeFrom( 25)
    val Min: FutureLimit     = unsafeFrom(  0)
    val Max: FutureLimit     = unsafeFrom(100)

    val Binding: lucuma.odb.graphql.binding.Matcher[FutureLimit] =
      lucuma.odb.graphql.binding.IntBinding.emap { v =>
        from(v).leftMap { _ =>
          s"Future limit must range from ${Min.value} to ${Max.value}, but was $v."
        }
      }
  }

  sealed trait Error {
    def format: String
  }

  object Error {

    case class ItcError(error: ItcService.Error) extends Error {
      def format: String =
        error.format
    }

    case class InvalidData(message: String) extends Error {
      def format: String =
        s"Could not generate a sequence from the observation: $message"
    }

    case class MissingSmartGcalDef(key: String) extends Error {
      def format: String =
        s"Could not generate a sequence, missing Smart GCAL mapping: $key"
    }

  }

  case class Success(
    observationId: Observation.Id,
    itc:           ItcService.AsterismResult,
    config:        InstrumentExecutionConfig
  )

  def instantiate[F[_]: Concurrent](
    commitHash:   CommitHash,
    itcClient:    ItcClient[F],
    calculator:   PlannedTimeCalculator.ForInstrumentMode,
  )(using Services[F]): Generator[F] =
    new Generator[F] {

      import Error.*

      private val exp = SmartGcalExpander.fromService(smartGcalService)

      override def lookupAndGenerate(
        pid:         Program.Id,
        oid:         Observation.Id,
        futureLimit: FutureLimit = FutureLimit.Default
      )(using NoTransaction[F]): F[Either[Error, Success]] = {
        val itcResult: F[Either[Error, ItcService.Success]] =
          itcService(itcClient)
            .lookup(pid, oid)
            .map(_.leftMap(error => ItcError(error)))

        (for {
          r <- EitherT(itcResult)
          s <- EitherT(generate(oid, r.params, r.result, futureLimit))
        } yield s).value
      }

      // Generate a sequence for the observation, which will depend on the
      // observation's observing mode.
      override def generate(
        oid:         Observation.Id,
        params:      GeneratorParams,
        resultSet:   ItcService.AsterismResult,
        futureLimit: FutureLimit
      )(using NoTransaction[F]): F[Either[Error, Success]] = {
        val namespace = SequenceIds.namespace(commitHash, oid, params)

        (params match {
          case GeneratorParams.GmosNorthLongSlit(_, config) =>
            for {
              p0  <- gmosLongSlit(resultSet.value.focus, config, gmos.longslit.Generator.GmosNorth)
              p1  <- expandAndEstimate(p0, exp.gmosNorth, calculator.gmosNorth)
              res <- EitherT.right(toExecutionConfig(namespace, p1, calculator.gmosNorth.estimateSetup, futureLimit))
            } yield Success(oid, resultSet, InstrumentExecutionConfig.GmosNorth(res))

          case GeneratorParams.GmosSouthLongSlit(_, config) =>
            for {
              p0  <- gmosLongSlit(resultSet.value.focus, config, gmos.longslit.Generator.GmosSouth)
              p1  <- expandAndEstimate(p0, exp.gmosSouth, calculator.gmosSouth)
              res <- EitherT.right(toExecutionConfig(namespace, p1, calculator.gmosSouth.estimateSetup, futureLimit))
            } yield Success(oid, resultSet, InstrumentExecutionConfig.GmosSouth(res))
        }).value
      }

      // Generates the initial GMOS LongSlit sequences, without smart-gcal expansion
      // or planned time calculation.
      private def gmosLongSlit[S, D, G, L, U](
        itcResult: ItcService.TargetResult,
        config:    gmos.longslit.Config[G, L, U],
        generator: gmos.longslit.Generator[S, D, G, L, U]
      ): EitherT[F, Error, ProtoExecutionConfig[Pure, S, ProtoAtom[ProtoStep[D]]]] =
        EitherT.fromEither[F](
          generator.generate(itcResult.value, config) match {
            case Left(msg)    => InvalidData(msg).asLeft
            case Right(proto) => proto.mapSequences(_.take(1), _.take(itcResult.value.exposures.value)).asRight[Error]
          }
        )

      // Performs smart-gcal expansion and planned time calculation.
      private def expandAndEstimate[S, K, D](
        proto:    ProtoExecutionConfig[Pure, S, ProtoAtom[ProtoStep[D]]],
        expander: SmartGcalExpander[F, K, D],
        calc:     PlannedTimeCalculator[S, D]
      ): EitherT[F, Error, ProtoExecutionConfig[F, S, ProtoAtom[ProtoStep[(D, StepEstimate)]]]] =
        for {
          _ <- EitherT(expander.validate(proto.acquisition).map(_.leftMap(MissingSmartGcalDef.apply)))
          c <- EitherT(expander.validate(proto.science).map(_.leftMap(MissingSmartGcalDef.apply)))
          s <- EitherT.rightT(
                 proto.mapBothSequences[F, ProtoAtom[ProtoStep[(D, StepEstimate)]]](
                   _.through(expander.expandWithCache(c))
                    .through(calc.estimateSequence[F](proto.static))
                 )
               )
        } yield s

      private val offset = StepConfig.science.andThen(StepConfig.Science.offset)

      private case class SequenceSummary[D](
        initialAtoms: Vector[ProtoAtom[ProtoStep[(D, StepEstimate)]]],
        hasMore:      Boolean,
        atomCount:    Int,
        digest:       SequenceDigest
      ) {

        def addAtom(
          a: ProtoAtom[ProtoStep[(D, StepEstimate)]],
          futureLimit: FutureLimit
        ): SequenceSummary[D] = {
          val digestʹ    = a.steps.foldLeft(digest) { (d, s) =>
            val dʹ = d.add(s.observeClass).add(PlannedTime.fromStep(s.observeClass, s.value._2))
            offset.getOption(s.stepConfig).fold(dʹ)(dʹ.add)
          }
          val appendAtom = initialAtoms.lengthIs < (futureLimit.value + 1 /* for nextAtom */)
          copy(
            initialAtoms = if (appendAtom) initialAtoms.appended(a) else initialAtoms,
            hasMore      = !appendAtom,
            atomCount    = atomCount + 1,
            digest       = digestʹ
          )
        }
      }

      private object SequenceSummary {
        def zero[D]: SequenceSummary[D] =
          SequenceSummary(
            Vector.empty,
            false,
            0,
            SequenceDigest.Zero
          )

        def summarize[D](
          s: Stream[F, ProtoAtom[ProtoStep[(D, StepEstimate)]]],
          futureLimit: FutureLimit
        ): F[SequenceSummary[D]] =
          s.fold(zero[D]) { (sum, a) => sum.addAtom(a, futureLimit) }.compile.onlyOrError
      }

      private def toExecutionConfig[S, D](
        namespace:   UUID,
        proto:       ProtoExecutionConfig[F, S, ProtoAtom[ProtoStep[(D, StepEstimate)]]],
        setupTime:   SetupTime,
        futureLimit: FutureLimit
      ): F[ExecutionConfig[S, D]] = {

        def toExecutionSequence(
          sequence:     SequenceSummary[D],
          sequenceType: SequenceType
        ): Option[ExecutionSequence[D]] =
          NonEmptyList.fromFoldable(
            sequence
              .initialAtoms
              .zipWithIndex
              .map(_.map(SequenceIds.atomId(namespace, sequenceType, _)))
              .map { case (atom, atomId) =>
                val steps = atom.steps.zipWithIndex.map { case (ProtoStep((d, e), sc, oc, bp), j) =>
                  Step(SequenceIds.stepId(namespace, sequenceType, atomId, j), d, sc, e, oc, bp)
                }
                Atom(atomId, atom.description, steps)
              }
        ).map(nel => ExecutionSequence(nel.head, nel.tail, sequence.hasMore, PosInt.unsafeFrom(sequence.atomCount), sequence.digest))

        for {
          acq <- SequenceSummary.summarize(proto.acquisition, futureLimit)
          sci <- SequenceSummary.summarize(proto.science, futureLimit)
        } yield
          ExecutionConfig(
            proto.static,
            toExecutionSequence(acq, SequenceType.Acquisition),
            toExecutionSequence(sci, SequenceType.Science),
            setupTime
          )
      }
    }
}
