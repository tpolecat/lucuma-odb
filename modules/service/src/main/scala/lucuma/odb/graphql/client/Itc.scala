// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql.client

import cats.Order
import cats.Order.catsKernelOrderingForOrder
import cats.data.EitherNel
import cats.data.NonEmptyList
import cats.data.NonEmptySet
import cats.effect.MonadCancel
import cats.effect.Resource
import cats.syntax.applicative.*
import cats.syntax.bifunctor.*
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.option.*
import cats.syntax.order.*
import cats.syntax.traverse.*
import eu.timepit.refined.types.numeric.NonNegInt
import eu.timepit.refined.types.numeric.PosBigDecimal
import lucuma.core.data.Zipper
import lucuma.core.model.NonNegDuration
import lucuma.core.model.Observation
import lucuma.core.model.Program
import lucuma.core.model.Target
import lucuma.itc.client.ItcClient
import lucuma.itc.client.ItcResult
import lucuma.itc.client.SpectroscopyModeInput
import lucuma.odb.service.ItcInputService
import lucuma.odb.util.NonEmptyListExtensions.*

/**
 * The ITC client combines the input parameter lookup and the actual remote
 * call to the ITC service in a single method.
 */
sealed trait Itc[F[_]] {
  import Itc.ResultSet

  def queryOne(
    programId:     Program.Id,
    observationId: Observation.Id,
    useCache:      Boolean
  ): F[Option[ResultSet]]

  def queryAll(
    programId:      Program.Id,
    observationIds: List[Observation.Id],
    useCache:       Boolean
  ): F[List[ResultSet]]

}


object Itc {

  opaque type Param = String

  extension (p: Param) {
    def stringValue: String =
      p
  }

  sealed trait Result extends Product with Serializable {

    val missing: Option[Result.Missing] =
      fold(_.some, _ => none, _ => none)

    val serviceError: Option[Result.ServiceError] =
      fold(_ => none, _.some, _ => none)

    val success: Option[Result.Success] =
      fold(_ => none, _ => none, _.some)

    def fold[A](
      mf: Result.Missing      => A,
      ef: Result.ServiceError => A,
      sf: Result.Success      => A
    ): A =
      this match {
        case m @ Result.Missing(_, _)          => mf(m)
        case e @ Result.ServiceError(_, _, _)  => ef(e)
        case s @ Result.Success(_, _, _, _, _) => sf(s)
      }

  }

  object Result {
    final case class Missing(
      targetId: Option[Target.Id],
      params:   NonEmptySet[Param]
    ) extends Result

    final case class ServiceError(
      targetId: Target.Id,
      input:    SpectroscopyModeInput,
      message:  String
    ) extends Result

    def serviceError(
      targetId: Target.Id,
      input:    SpectroscopyModeInput,
      message:  String
    ): Result =
      ServiceError(targetId, input, message)

    final case class Success(
      targetId:      Target.Id,
      input:         SpectroscopyModeInput,
      exposureTime:  NonNegDuration,
      exposures:     NonNegInt,
      signalToNoise: PosBigDecimal
    ) extends Result

    def success(
      targetId:      Target.Id,
      input:         SpectroscopyModeInput,
      exposureTime:  NonNegDuration,
      exposures:     NonNegInt,
      signalToNoise: PosBigDecimal
    ): Result =
      Success(targetId, input, exposureTime, exposures, signalToNoise)

    val SelectionOrder: Order[Result] =
      Order.from {
        case (Missing(_, _), _)                                   => -1
        case (_, Missing(_, _))                                   =>  1
        case (ServiceError(_, _, _), _)                           => -1
        case (_, ServiceError(_, _, _))                           =>  1
        case (Success(_, _, at, ac, _), Success(_, _, bt, bc, _)) =>
          at.value
            .multipliedBy(ac.value)
            .compareTo(bt.value.multipliedBy(bc.value))
      }
  }

  /**
   * Results for all the targets in an observation's asterism.  The summary or
   * selected result for the set as a whole is the focus of the `value` zipper.
   */
  final case class ResultSet(
    programId:     Program.Id,
    observationId: Observation.Id,
    value:         Either[Result.Missing, Zipper[Result]]
  )

  object ResultSet {

    def missing(
      pid: Program.Id,
      oid: Observation.Id,
      ps:  NonEmptyList[Param]
    ): ResultSet =
      ResultSet(pid, oid, Result.Missing(none, ps.toNes).asLeft)

    def fromResults(
      pid: Program.Id,
      oid: Observation.Id,
      rs:  NonEmptyList[Result]
    ): ResultSet =
      ResultSet(pid, oid, rs.focusMax(Result.SelectionOrder).asRight)
  }

  def fromClientAndService[F[_]](
    client:  ItcClient[F],
    service: Resource[F, ItcInputService[F]]
  )(implicit ev: MonadCancel[F, Throwable]): Itc[F] =
    new Itc[F] {

      def queryOne(
        programId:     Program.Id,
        observationId: Observation.Id,
        useCache:      Boolean
      ): F[Option[ResultSet]] =
        queryAll(programId, List(observationId), useCache)
          .map(_.find(_.observationId === observationId))

      def queryAll(
        programId:      Program.Id,
        observationIds: List[Observation.Id],
        useCache:       Boolean
      ): F[List[ResultSet]] =

        service.use { s =>
          s.selectSpectroscopyInput(programId, observationIds).flatMap { m =>
             m.toList.traverse { case (oid, e) => // (Observation.Id, EitherNel[String, NonEmptyList[(Target.Id, SpectroscopyModeInput)]])
               callForObservation(programId, oid, e, useCache)
             }
          }
        }

      def callForObservation(
        pid:      Program.Id,
        oid:      Observation.Id,
        e:        EitherNel[String, NonEmptyList[(Target.Id, SpectroscopyModeInput)]],
        useCache: Boolean
      ): F[ResultSet] =

        e.fold(
          ps => ResultSet.missing(pid, oid, ps).pure[F],
          _.traverse { case (tid, si) => // (Target.Id, SpectroscopyModeInput)
            callForTarget(tid, si, useCache)
          }.map(ResultSet.fromResults(pid, oid, _))
        )


      def callForTarget(
        tid:      Target.Id,
        input:    SpectroscopyModeInput,
        useCache: Boolean
      ): F[Result] =
        client.spectroscopy(input, useCache).map { sr =>
          sr.result.fold(Result.serviceError(tid, input, "ITC Service returned nothing.")) {
            case ItcResult.Error(msg)       => Result.serviceError(tid, input, msg)
            case ItcResult.Success(t, c, s) => Result.success(tid, input, t, c, s)
          }
        }

    }

}
