// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.logic

import cats.data.EitherT
import cats.data.OptionT
import cats.effect.Concurrent
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import eu.timepit.refined.api.Refined
import eu.timepit.refined.api.RefinedTypeOps
import eu.timepit.refined.numeric.Interval
import fs2.Pure
import fs2.Stream
import lucuma.core.enums.SequenceType
import lucuma.core.math.Offset
import lucuma.core.model.Observation
import lucuma.core.model.Program
import lucuma.core.model.sequence.Atom
import lucuma.core.model.sequence.ExecutionConfig
import lucuma.core.model.sequence.ExecutionDigest
import lucuma.core.model.sequence.ExecutionSequence
import lucuma.core.model.sequence.InstrumentExecutionConfig
import lucuma.core.model.sequence.PlannedTime
import lucuma.core.model.sequence.SequenceDigest
import lucuma.core.model.sequence.SetupTime
import lucuma.core.model.sequence.Step
import lucuma.core.model.sequence.StepConfig
import lucuma.core.model.sequence.StepEstimate
import lucuma.itc.client.ItcClient
import lucuma.odb.data.Md5Hash
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

import java.security.MessageDigest
import java.util.UUID

import Generator.Error
import Generator.FutureLimit

sealed trait Generator[F[_]] {

  /**
   * Looks up the parameters required to calculate the ExecutionDigest, performs
   * the calculation, and caches the results if the observation was found.  If
   * the observation is not completely defined (e.g., if missing the observing
    * mode), an Error is produced.
   */
  def digest(
    programId:     Program.Id,
    observationId: Observation.Id
  )(using NoTransaction[F]): F[Either[Error, Option[ExecutionDigest]]]

  /**
   * Calculates the ExecutionDigest given the AsterismResults from the ITC
   * along with the GeneratorParams. This method always performs the calculation
   * and does not attempt to use cached results nor call the ITC.  It will
   * cache the calculation once performed.
   */
  def calculateDigest(
    programId:      Program.Id,
    observationId:  Observation.Id,
    asterismResult: ItcService.AsterismResult,
    params:         GeneratorParams
  )(using NoTransaction[F]): F[Either[Error, ExecutionDigest]]

  /**
   * Generates the execution config if the observation is found and defined
   * well enough to perform the calculation.
   */
  def generate(
    programId:     Program.Id,
    observationId: Observation.Id,
    futureLimit:   FutureLimit = FutureLimit.Default
  )(using NoTransaction[F]): F[Either[Error, Option[InstrumentExecutionConfig]]]

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

    case class InvalidData(
      observationId: Observation.Id,
      message:       String
    ) extends Error {
      def format: String =
        s"Could not generate a sequence from the observation $observationId: $message"
    }

    case class MissingSmartGcalDef(key: String) extends Error {
      def format: String =
        s"Could not generate a sequence, missing Smart GCAL mapping: $key"
    }

    case object SequenceTooLong extends Error {
      def format: String =
        s"The generated sequence is too long (more than ${Int.MaxValue} atoms)"
    }

    def missingSmartGcalDef(key: String): Error =
      MissingSmartGcalDef(key)
  }


  def instantiate[F[_]: Concurrent](
    commitHash:   CommitHash,
    itcClient:    ItcClient[F],
    calculator:   PlannedTimeCalculator.ForInstrumentMode,
  )(using Services[F]): Generator[F] =
    new Generator[F] {

      import Error.*

      private val exp = SmartGcalExpander.fromService(smartGcalService)

      type OptionEitherT[F[_], Error, A] = OptionT[EitherT[F, Error, *], A]

      object OptionEitherT {
        def apply[A](value: F[Either[Error, Option[A]]]): OptionEitherT[F, Error, A] =
          OptionT(EitherT(value))

        def useOrCalc[A](a: Option[A], calc: => EitherT[F, Error, A]): OptionEitherT[F, Error, A] =
          OptionT.liftF(a.fold(calc)(EitherT.pure(_)))
      }

      private case class Context(
        pid:    Program.Id,
        oid:    Observation.Id,
        itcRes: ItcService.AsterismResult,
        params: GeneratorParams
      ) {

        def namespace: UUID =
           SequenceIds.namespace(commitHash, oid, params)

        val integrationTime: ItcService.TargetResult =
          itcRes.value.focus

        val hash: Md5Hash = {
          val zero = 0.toByte
          val md5  = MessageDigest.getInstance("MD5")

          // Observing Mode
          md5.update(params.observingMode.hashBytes)

          // Integration Time
          val ing = integrationTime.value
          md5.update(BigInt(ing.exposureTime.toMicroseconds).toByteArray.reverse.padTo(8, zero))
          md5.update(BigInt(ing.exposures.value).toByteArray.reverse.padTo(4, zero))

          // Commit Hash
          md5.update(commitHash.toByteArray)

          Md5Hash.unsafeFromByteArray(md5.digest())
        }

        def checkCache(using NoTransaction[F]): EitherT[F, Error, Option[ExecutionDigest]] =
          EitherT.right(services.transactionally {
            executionDigestService.selectOne(pid, oid, hash)
          })

        def cache(digest: ExecutionDigest)(using NoTransaction[F]): EitherT[F, Error, Unit] =
          EitherT.right(services.transactionally {
            executionDigestService.insertOrUpdate(pid, oid, hash, digest)
          })
      }

      private object Context {

        def lookup(
          pid: Program.Id,
          oid: Observation.Id
        )(using NoTransaction[F]): OptionEitherT[F, Error, Context] = {
          val itc = itcService(itcClient)

          val opc: OptionEitherT[F, Error, (GeneratorParams, Option[ItcService.AsterismResult])] =
            OptionEitherT(services.transactionally {
              for {
                p <- generatorParamsService.selectOne(pid, oid).map(_.leftMap(es => InvalidData(oid, es.map(_.format).intercalate(", "))))
                c <- p.toOption.flatten.flatTraverse(itc.selectOne(pid, oid, _))
              } yield p.map(_.tupleRight(c))
            })

          def callItc(p: GeneratorParams): EitherT[F, Error, ItcService.AsterismResult] =
            EitherT(itc.callRemote(pid, oid, p)).leftMap(ItcError(_): Error)

          for {
            pc <- opc
            (params, cached) = pc
            as <- OptionEitherT.useOrCalc(cached, callItc(params))
          } yield Context(pid, oid, as, params)

        }
      }

      override def digest(
        pid: Program.Id,
        oid: Observation.Id
      )(using NoTransaction[F]): F[Either[Error, Option[ExecutionDigest]]] =
        (for {
          c <- Context.lookup(pid, oid)
          d <- OptionT.liftF(c.checkCache.flatMap(_.fold(calcDigestThenCache(c))(EitherT.pure(_))))
        } yield d).value.value

      override def calculateDigest(
        pid:            Program.Id,
        oid:            Observation.Id,
        asterismResult: ItcService.AsterismResult,
        params:         GeneratorParams
      )(using NoTransaction[F]): F[Either[Error, ExecutionDigest]] =
        calcDigestThenCache(Context(pid, oid, asterismResult, params)).value

      private def calcDigestThenCache(
        ctx: Context
      )(using NoTransaction[F]): EitherT[F, Error, ExecutionDigest] =
        calcDigestFromContext(ctx).flatTap(ctx.cache)

      private def calcDigestFromContext(
        ctx: Context
      )(using NoTransaction[F]): EitherT[F, Error, ExecutionDigest] =
        ctx.params match {
          case GeneratorParams.GmosNorthLongSlit(_, config) =>
            for {
              p <- gmosLongSlit(ctx.oid, ctx.integrationTime, config, gmos.longslit.Generator.GmosNorth)
              r <- executionDigest(expandAndEstimate(p, exp.gmosNorth, calculator.gmosNorth), calculator.gmosNorth.estimateSetup)
            } yield r

          case GeneratorParams.GmosSouthLongSlit(_, config) =>
            for {
              p <- gmosLongSlit(ctx.oid, ctx.integrationTime, config, gmos.longslit.Generator.GmosSouth)
              r <- executionDigest(expandAndEstimate(p, exp.gmosSouth, calculator.gmosSouth), calculator.gmosSouth.estimateSetup)
            } yield r
        }

      override def generate(
        pid: Program.Id,
        oid: Observation.Id,
        lim: FutureLimit = FutureLimit.Default
      )(using NoTransaction[F]): F[Either[Error, Option[InstrumentExecutionConfig]]] =
        (for {
          c <- Context.lookup(pid, oid)
          x <- OptionT.liftF(calcExecutionConfigFromContext(c, lim))
        } yield x).value.value

      private def calcExecutionConfigFromContext(
        ctx: Context,
        lim: FutureLimit
      )(using NoTransaction[F]): EitherT[F, Error, InstrumentExecutionConfig] =
        ctx.params match {
          case GeneratorParams.GmosNorthLongSlit(_, config) =>
            for {
              p <- gmosLongSlit(ctx.oid, ctx.integrationTime, config, gmos.longslit.Generator.GmosNorth)
              r <- executionConfig(expandAndEstimate(p, exp.gmosNorth, calculator.gmosNorth), ctx.namespace, lim)
            } yield InstrumentExecutionConfig.GmosNorth(r)

          case GeneratorParams.GmosSouthLongSlit(_, config) =>
            for {
              p <- gmosLongSlit(ctx.oid, ctx.integrationTime, config, gmos.longslit.Generator.GmosSouth)
              r <- executionConfig(expandAndEstimate(p, exp.gmosSouth, calculator.gmosSouth), ctx.namespace, lim)
            } yield InstrumentExecutionConfig.GmosSouth(r)
        }


      // Generates the initial GMOS LongSlit sequences, without smart-gcal expansion
      // or planned time calculation.
      private def gmosLongSlit[S, D, G, L, U](
        oid:       Observation.Id,
        itcResult: ItcService.TargetResult,
        config:    gmos.longslit.Config[G, L, U],
        generator: gmos.longslit.Generator[S, D, G, L, U]
      ): EitherT[F, Error, ProtoExecutionConfig[Pure, S, ProtoAtom[ProtoStep[D]]]] =
        EitherT.fromEither[F](
          generator.generate(itcResult.value, config) match {
            case Left(msg)    => InvalidData(oid, msg).asLeft
            case Right(proto) => proto.mapSequences(_.take(1), _.take(itcResult.value.exposures.value)).asRight[Error]
          }
        )

      // Performs smart-gcal expansion and planned time calculation.
      private def expandAndEstimate[S, K, D](
        proto:    ProtoExecutionConfig[Pure, S, ProtoAtom[ProtoStep[D]]],
        expander: SmartGcalExpander[F, K, D],
        calc:     PlannedTimeCalculator[S, D]
      ): ProtoExecutionConfig[F, S, Either[String, ProtoAtom[ProtoStep[(D, StepEstimate)]]]] =
        proto.mapBothSequences[F, Either[String, ProtoAtom[ProtoStep[(D, StepEstimate)]]]](
          _.through(expander.expand)
           .through(calc.estimateSequence[F](proto.static))
        )


      private val offset = StepConfig.science.andThen(StepConfig.Science.offset)

      private def executionDigest[S, D](
        proto:     ProtoExecutionConfig[F, S, Either[String, ProtoAtom[ProtoStep[(D, StepEstimate)]]]],
        setupTime: SetupTime
      ): EitherT[F, Error, ExecutionDigest] = {

        // Compute the sequence digest from the stream by folding over the steps
        // if possible. Missing smart gcal definitions may prevent it.
        def sequenceDigest(
          s: Stream[F, Either[String, ProtoAtom[ProtoStep[(D, StepEstimate)]]]]
        ): F[Either[Error, SequenceDigest]] =
          s.fold(SequenceDigest.Zero.asRight[Error]) { (eDigest, eAtom) =>
            eDigest.flatMap { digest =>
              digest.incrementAtomCount.toRight(SequenceTooLong).flatMap { incDigest =>
                eAtom.bimap(
                  missingSmartGcalDef,
                  _.steps.foldLeft(incDigest) { (d, s) =>
                    val dʹ = d.add(s.observeClass).add(PlannedTime.fromStep(s.observeClass, s.value._2))
                    offset.getOption(s.stepConfig).fold(dʹ)(dʹ.add)
                  }
                )
              }
            }
          }.compile.onlyOrError

        for {
          a <- EitherT(sequenceDigest(proto.acquisition))
          s <- EitherT(sequenceDigest(proto.science))
        } yield ExecutionDigest(setupTime, a, s)

      }

      private def executionConfig[S, D](
        proto:       ProtoExecutionConfig[F, S, Either[String, ProtoAtom[ProtoStep[(D, StepEstimate)]]]],
        namespace:   UUID,
        futureLimit: FutureLimit
      ): EitherT[F, Error, ExecutionConfig[S, D]] = {

        def executionSequence(
          s: Stream[F, Either[String, ProtoAtom[ProtoStep[(D, StepEstimate)]]]],
          t: SequenceType
        ): F[Either[Error, Option[ExecutionSequence[D]]]] =
          s.zipWithIndex
           .map(_.map(SequenceIds.atomId(namespace, t, _)))
           .map { case (eAtom, atomId) =>
             eAtom.bimap(
               missingSmartGcalDef,
               atom =>
                 val steps = atom.steps.zipWithIndex.map { case (ProtoStep((d, e), sc, oc, bp), j) =>
                   Step(SequenceIds.stepId(namespace, t, atomId, j), d, sc, e, oc, bp)
                 }
                 Atom(atomId, atom.description, steps)
             )
           }
           .zipWithNext
           .map { case (e, n) => e.tupleRight(n.isDefined) } // Either[Error, (atom, has more)]
           .take(1 + futureLimit.value) // 1 (nextAtom) + futureLimit (possibleFuture)
           .compile
           .toList
           .map(_.sequence.map { atoms =>
              atoms.headOption.map { case (head, _) =>
                val future  = atoms.tail.map(_._1)
                val hasMore = atoms.last._2
                ExecutionSequence(head, future, hasMore)
              }
           })

        for {
          a <- EitherT(executionSequence(proto.acquisition, SequenceType.Acquisition))
          s <- EitherT(executionSequence(proto.science, SequenceType.Science))
        } yield ExecutionConfig(proto.static, a, s)
      }
    }
}
