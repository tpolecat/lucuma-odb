// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.logic

import cats.MonadThrow
import cats.data.EitherT
import cats.data.NonEmptyList
import cats.data.NonEmptySet
import cats.syntax.bifunctor.*
import cats.syntax.either.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.option.*
import io.circe.Encoder
import lucuma.core.data.Zipper
import lucuma.core.enums.Breakpoint
import lucuma.core.enums.SequenceType
import lucuma.core.model.Observation
import lucuma.core.model.Program
import lucuma.core.model.Target
import lucuma.core.model.sequence.Atom
import lucuma.core.model.sequence.DynamicConfig
import lucuma.core.model.sequence.ExecutionSequence
import lucuma.core.model.sequence.FutureExecutionConfig
import lucuma.core.model.sequence.Step
import lucuma.core.model.sequence.StepConfig
import lucuma.core.model.sequence.StepTime
import lucuma.core.util.TimeSpan
import lucuma.itc.IntegrationTime
import lucuma.itc.client.ItcClient
import lucuma.itc.client.SpectroscopyIntegrationTimeInput
import lucuma.odb.sequence.data.GeneratorParams
import lucuma.odb.sequence.data.ProtoAtom
import lucuma.odb.sequence.data.ProtoExecution
import lucuma.odb.sequence.data.ProtoSequence
import lucuma.odb.sequence.gmos
import lucuma.odb.sequence.util.CommitHash
import lucuma.odb.sequence.util.SequenceIds
import lucuma.odb.service.GeneratorParamsService
import lucuma.odb.service.Services
import lucuma.odb.service.Services.Syntax.*
import lucuma.odb.service.SmartGcalService
import skunk.Transaction

import java.io.ObjectOutputStream
import java.util.UUID

sealed trait Generator[F[_]] {

  def generate(
    programId:     Program.Id,
    observationId: Observation.Id,
    useCache:      Boolean
  )(using Transaction[F]): F[Generator.Result]

}

object Generator {

  private val SequenceLengthLimit: Int = 1000

  sealed trait Result
  sealed trait Error extends Result {
    def format: String
  }

  object Result {

    case class ObservationNotFound(
      programId:     Program.Id,
      observationId: Observation.Id
    ) extends Error {
      def format: String =
        s"Observation '$observationId' in program '$programId' not found."
    }

    case class MissingParams(
      missing: NonEmptyList[GeneratorParamsService.MissingData]
    ) extends Error {
      def format: String = {
        val params = missing.map { m =>
          s"${m.targetId.fold("") { tid => s"(target $tid) " }}${m.paramName}"
        }.intercalate(", ")
        s"ITC cannot be queried until the following parameters are defined: $params"
      }
    }

    case class ItcServiceError(
      errors: NonEmptyList[Itc.Error]
    ) extends Error {
      def format: String =
        s"ITC service errors: ${errors.map(_.format).intercalate(", ")}"
    }

    case class SequenceTooLong(
      observationId: Observation.Id,
      length:        Int
    ) extends Error {
      def format: String =
        s"Sequence length is temporarily limited to $SequenceLengthLimit steps, but observation '$observationId' would require $length."
    }

    case class InvalidData(
      message: String
    ) extends Error {
      def format: String =
        s"Could not generate a sequence from the observation: $message"
    }

    case class MissingSmartGcalDef(
      key: String
    ) extends Error {
      def format: String =
        s"Could not generate a sequence, missing Smart GCAL mapping: $key"
    }

    case class Success(
      observationId: Observation.Id,
      itc:           Itc.ResultSet,
      value:         FutureExecutionConfig
    ) extends Result
  }

  def instantiate[F[_]: MonadThrow](
    commitHash:   CommitHash,
    itcClient:    ItcClient[F],
  )(using Services[F]): Generator[F] =
    new Generator[F] {

      import Result.*

      private val exp = SmartGcalExpander.fromService(smartGcalService)

      // This is a placeholder.  I'm not sure we'll end up adding step time to
      // the generated sequence.
      private val StepTimeZero: StepTime = StepTime(
        TimeSpan.Zero,
        TimeSpan.Zero,
        TimeSpan.Zero,
        TimeSpan.Zero,
        TimeSpan.Zero
      )

      override def generate(
        programId:     Program.Id,
        observationId: Observation.Id,
        useCache:      Boolean
      )(using Transaction[F]): F[Result] =
        (for {
          params <- selectParams(programId, observationId)
          res    <- generateSequence(observationId, params, useCache)
        } yield res).merge

      private def selectParams(
        pid: Program.Id,
        oid: Observation.Id
      )(using Transaction[F]): EitherT[F, Error, GeneratorParams] =
        EitherT(
          generatorParamsService
            .select(pid, oid)
            .map {
              case None                => ObservationNotFound(pid, oid).asLeft
              case Some(Left(missing)) => MissingParams(missing).asLeft
              case Some(Right(params)) => params.asRight
            }
        )

      private def generateSequence(
        oid:       Observation.Id,
        params:    GeneratorParams,
        useCache:  Boolean
      ): EitherT[F, Error, Success] = {
        val namespace = SequenceIds.namespace(commitHash, oid, params)

        params match {
          case GeneratorParams.GmosNorthLongSlit(itcInput, config) =>
            for {
              tup <- gmosLongSlit(oid, itcInput, config, gmos.longslit.Generator.GmosNorth, useCache)
              (rs, p0) = tup
              p1 <- EitherT(exp.expandGmosNorth(p0)).leftMap { k => MissingSmartGcalDef(k.format) }
            } yield
              Success(
                oid,
                rs,
                futureExecutionConfig(
                  namespace,
                  p1,
                  FutureExecutionConfig.GmosNorth.apply,
                  ExecutionSequence.GmosNorth.apply,
                  Atom.GmosNorth.apply,
                  Step.GmosNorth.apply
                )
              )

          case GeneratorParams.GmosSouthLongSlit(itcInput, config) =>
            for {
              tup <- gmosLongSlit(oid, itcInput, config, gmos.longslit.Generator.GmosSouth, useCache)
              (rs, p0) = tup
              p1  <- EitherT(exp.expandGmosSouth(p0)).leftMap { k => MissingSmartGcalDef(k.format) }
            } yield
              Success(
                oid,
                rs,
                futureExecutionConfig(
                  namespace,
                  p1,
                  FutureExecutionConfig.GmosSouth.apply,
                  ExecutionSequence.GmosSouth.apply,
                  Atom.GmosSouth.apply,
                  Step.GmosSouth.apply
                )
              )


        }
      }


      def gmosLongSlit[S, D, G, L, U](
        oid:       Observation.Id,
        itcInput:  NonEmptyList[(Target.Id, SpectroscopyIntegrationTimeInput)],
        config:    gmos.longslit.Config[G, L, U],
        generator: gmos.longslit.Generator[S, D, G, L, U],
        useCache:  Boolean
      ): EitherT[F, Error, (Itc.ResultSet, ProtoExecution[S, D])] = {

        def generate(s: Itc.Result.Success): Either[Error, ProtoExecution[S, D]] =
          generator.generate(
            s.value,
            s.input.sourceProfile,
            s.input.constraints.imageQuality,
            config
          ) match {
            case Left(msg)    => InvalidData(msg).asLeft
            case Right(proto) => proto.asRight
          }

        EitherT(
          itc(itcClient)
            .spectroscopy(itcInput, useCache)
            .map {
              case Left(errors) =>
                ItcServiceError(errors).asLeft
              case Right(rs)    =>
                val success = rs.value.focus
                success.value.exposures.value match {
                  case v if v > SequenceLengthLimit =>
                    SequenceTooLong(oid, v).asLeft
                  case _                            =>
                    generate(success).tupleLeft(rs)
                }
            }
        )
      }

      def futureExecutionConfig[X <: ExecutionSequence, A <: Atom, T <: Step, S, D](
        namespace:  UUID,
        proto:      ProtoExecution[S, D],
        mkExec:     (S, X, X) => FutureExecutionConfig,
        mkSequence: (A, List[A]) => X,
        mkAtom:     (Atom.Id, List[T]) => A,
        mkStep:     (Step.Id, D, StepConfig, StepTime, Breakpoint) => T
      ): FutureExecutionConfig = {

        def executionSequence(
          sequence:     ProtoSequence[D],
          sequenceType: SequenceType
        ): X = {
          val NonEmptyList(h, t) =
            sequence
              .atoms
              .zipWithIndex
              .map(_.map(SequenceIds.atomId(namespace, sequenceType, _)))
              .map { case (atom, atomId) =>
                mkAtom(
                  atomId,
                  atom.steps.zipWithIndex.map { case (s, j) =>
                    mkStep(
                      SequenceIds.stepId(namespace, sequenceType, atomId, j),
                      s.instrumentConfig,
                      s.stepConfig,
                      StepTimeZero,        // Placeholder
                      Breakpoint.Disabled
                    )
                  }.toList
                )
              }
          mkSequence(h, t)
        }

        mkExec(
          proto.static,
          executionSequence(proto.acquisition, SequenceType.Acquisition),
          executionSequence(proto.science,     SequenceType.Science)
        )
      }


    }

}
