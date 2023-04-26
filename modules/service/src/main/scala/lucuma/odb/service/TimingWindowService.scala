// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.service

import lucuma.core.model.Observation
import lucuma.odb.graphql.input.TimingWindowInput
import edu.gemini.grackle.Result
import skunk.AppliedFragment
import skunk.syntax.all.*
import lucuma.odb.util.Codecs.*
import skunk.Transaction
import cats.effect.Sync
import skunk.Session
import cats.syntax.all.*
import skunk.codec.numeric.*

trait TimingWindowService[F[_]] {
  def createFunction(
    timingWindows: List[TimingWindowInput]
  ): Result[(List[Observation.Id], Transaction[F]) => F[Unit]]
}

object TimingWindowService:
  def fromSession[F[_]: Sync](
    session: Session[F]
  ): TimingWindowService[F] =
    new TimingWindowService[F] {
      private def exec(af: AppliedFragment): F[Unit] =
        session.prepareR(af.fragment.command).use { pq =>
          pq.execute(af.argument).void
        }

      override def createFunction(
        timingWindows: List[TimingWindowInput]
      ): Result[(List[Observation.Id], Transaction[F]) => F[Unit]] =
        Result( (obsIds, xa) =>
          exec(Statements.deleteObservationsTimingWindows(obsIds)) >>
            exec(Statements.createObservationsTimingWindows(obsIds, timingWindows))
        )
    }

object Statements {
  def deleteObservationsTimingWindows(
    observationIds: List[Observation.Id]
  ): AppliedFragment =
    sql"""
      DELETE FROM t_timing_window
      WHERE c_observation_id IN ${observation_id.list(observationIds.length).values}
    """.apply(observationIds)

  def createObservationsTimingWindows(
    observationIds: List[Observation.Id],
    timingWindows: List[TimingWindowInput]
  ): AppliedFragment =
    sql"""
      INSERT INTO t_timing_window(
        c_observation_id,
        c_inclusion,
        c_start,
        c_end_at,
        c_end_after,
        c_repeat_period,
        c_repeat_times
      ) VALUES ${(
        observation_id          ~
        timing_window_inclusion ~
        core_timestamp          ~
        core_timestamp.opt      ~
        time_span.opt           ~
        time_span.opt           ~
        int4.opt
      ).values.list(timingWindows.length).list(observationIds.length)}
    """
    .apply( 
      observationIds.map( obsId =>
        timingWindows.map( tw =>
          obsId ~ 
          tw.inclusion  ~ 
          tw.startUtc      ~ 
          tw.end.flatMap(_.atUtc) ~
          tw.end.flatMap(_.after.map(_.duration)) ~
          tw.end.flatMap(_.after.flatMap(_.repeat.map(_.period))) ~
          tw.end.flatMap(_.after.flatMap(_.repeat.flatMap(_.times.map(_.value))))
        )
      )
    )
}