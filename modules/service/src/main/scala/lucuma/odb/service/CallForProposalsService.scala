// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.service

import cats.data.NonEmptyList
import cats.effect.Concurrent
import cats.effect.MonadCancelThrow
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.option.*
import grackle.Result
import grackle.ResultT
import grackle.syntax.*
import lucuma.core.enums.CallForProposalsType
import lucuma.core.enums.Instrument
import lucuma.core.model.CallForProposals
import lucuma.core.model.Semester
import lucuma.odb.data.Nullable
import lucuma.odb.data.OdbError
import lucuma.odb.data.OdbErrorExtensions.*
import lucuma.odb.graphql.input.CallForProposalsPartnerInput
import lucuma.odb.graphql.input.CallForProposalsPropertiesInput
import lucuma.odb.graphql.input.CreateCallForProposalsInput
import lucuma.odb.util.Codecs.*
import skunk.AppliedFragment
import skunk.Command
import skunk.Query
import skunk.SqlState
import skunk.Transaction
import skunk.codec.temporal.date
import skunk.implicits.*

import Services.Syntax.*

trait CallForProposalsService[F[_]] {

  def typeAndSemesterOf(
    cid: CallForProposals.Id
  )(using Transaction[F]): F[Option[(CallForProposalsType, Semester)]]

  def createCallForProposals(
    input: CreateCallForProposalsInput
  )(using Transaction[F], Services.StaffAccess): F[Result[CallForProposals.Id]]

  def updateCallsForProposals(
    SET:   CallForProposalsPropertiesInput.Edit,
    which: AppliedFragment
  )(using Transaction[F], Services.StaffAccess): F[Result[List[CallForProposals.Id]]]

}

object CallForProposalsService {

  def instantiate[F[_]: MonadCancelThrow: Concurrent](using Services[F]): CallForProposalsService[F] =
    new CallForProposalsService[F] {

      override def typeAndSemesterOf(
        cid: CallForProposals.Id
      )(using Transaction[F]): F[Option[(CallForProposalsType, Semester)]] =
        session.option(Statements.SelectTypeAndSemester)(cid)

      override def createCallForProposals(
        input: CreateCallForProposalsInput
      )(using Transaction[F], Services.StaffAccess): F[Result[CallForProposals.Id]] = {

        def insertPartnersDefault(cids: List[CallForProposals.Id]): F[Unit] =
          session
           .prepareR(Statements.InsertDefaultPartners(cids))
           .use(_.execute(cids))
           .void

        def insertPartners(cids: List[CallForProposals.Id]): F[Unit] =
          input.SET.partners.fold(insertPartnersDefault(cids)) { partners =>
            session
              .prepareR(Statements.InsertPartners(cids, partners))
              .use(_.execute(cids, partners))
              .whenA(partners.nonEmpty)
          }

        val instruments = input.SET.instruments

        (for {
          cid <- session.unique(Statements.InsertCallForProposals)(input.SET)
          cids = List(cid)
          _   <- insertPartners(cids)
          _   <- session
                   .prepareR(Statements.InsertInstruments(cids, instruments))
                   .use(_.execute(cids, instruments))
                   .whenA(instruments.nonEmpty)
        } yield cid).map(_.success)
      }

      private def updateCfpTable(
        SET:   CallForProposalsPropertiesInput.Edit,
        which: AppliedFragment
      ): F[Result[List[CallForProposals.Id]]] = {
        val af = Statements.UpdateCallsForProposals(SET, which)
        session.prepareR(af.fragment.query(cfp_id)).use { pq =>
          pq.stream(af.argument, chunkSize = 1024)
            .compile
            .toList
            .map(_.success)
            .recover { case SqlState.CheckViolation(_) =>
              OdbError.InvalidArgument("Requested update to the active period is invalid: activeStart must come before activeEnd".some).asFailure
            }
        }
      }

      private def updatePartners(
        cids:     List[CallForProposals.Id],
        partners: Nullable[List[CallForProposalsPartnerInput]]
      ): F[Result[Unit]] = {
        val delete =
          session.executeCommand(Statements.DeletePartners(cids)).void

        def insert(vals: List[CallForProposalsPartnerInput]) =
          session
            .prepareR(Statements.InsertPartners(cids, vals))
            .use(_.execute(cids, vals))
            .whenA(vals.nonEmpty)
            .void

        partners
          .fold(delete, Concurrent[F].unit, is => delete *> insert(is))
          .map(_.success)
      }

      private def updateInstruments(
        cids:        List[CallForProposals.Id],
        instruments: Nullable[List[Instrument]]
      ): F[Result[Unit]] = {
        val delete =
          session.executeCommand(Statements.DeleteInstruments(cids)).void

        def insert(vals: List[Instrument]) =
          session
            .prepareR(Statements.InsertInstruments(cids, vals))
            .use(_.execute(cids, vals))
            .whenA(vals.nonEmpty)
            .void

        instruments
          .fold(delete, Concurrent[F].unit, is => delete *> insert(is))
          .map(_.success)
      }

      override def updateCallsForProposals(
        SET:   CallForProposalsPropertiesInput.Edit,
        which: AppliedFragment
      )(using Transaction[F], Services.StaffAccess): F[Result[List[CallForProposals.Id]]] =
        (for {
          cids <- ResultT(updateCfpTable(SET, which))
          _    <- ResultT(updatePartners(cids, SET.partners))
          _    <- ResultT(updateInstruments(cids, SET.instruments))
        } yield cids).value

    }

  object Statements {

    val SelectTypeAndSemester: Query[CallForProposals.Id, (CallForProposalsType, Semester)] =
      sql"""
        SELECT c_type, c_semester
          FROM t_cfp
         WHERE c_cfp_id = $cfp_id AND c_existence = 'present'::e_existence
      """.query(cfp_type *: semester)

    val InsertCallForProposals: Query[CallForProposalsPropertiesInput.Create, CallForProposals.Id] =
      sql"""
        INSERT INTO t_cfp (
          c_type,
          c_semester,
          c_north_ra_start,
          c_north_ra_end,
          c_north_dec_start,
          c_north_dec_end,
          c_south_ra_start,
          c_south_ra_end,
          c_south_dec_start,
          c_south_dec_end,
          c_deadline_default,
          c_active_start,
          c_active_end,
          c_existence
        )
        SELECT
          $cfp_type,
          $semester,
          ${right_ascension},
          ${right_ascension},
          ${declination},
          ${declination},
          ${right_ascension},
          ${right_ascension},
          ${declination},
          ${declination},
          ${core_timestamp.opt},
          $date,
          $date,
          $existence
        RETURNING
          c_cfp_id
      """.query(cfp_id).contramap { input => (
        input.cfpType,
        input.semester,
        input.gnRaLimit._1,
        input.gnRaLimit._2,
        input.gnDecLimit._1,
        input.gnDecLimit._2,
        input.gsRaLimit._1,
        input.gsRaLimit._2,
        input.gsDecLimit._1,
        input.gsDecLimit._2,
        input.deadline,
        input.active.start,
        input.active.end,
        input.existence
      )}

    def InsertDefaultPartners(
      cids: List[CallForProposals.Id]
    ): Command[cids.type] =
      sql"""
        INSERT INTO t_cfp_partner (
          c_cfp_id,
          c_partner
        )
        SELECT
            cfps.id,
            p.c_tag
        FROM t_partner p CROSS JOIN (VALUES ${cfp_id.values.list(cids.length)}) AS cfps(id)
      """.command.contramap(_ => cids)

    def InsertPartners(
      cids:     List[CallForProposals.Id],
      partners: List[CallForProposalsPartnerInput]
    ): Command[(cids.type, partners.type)] =
      sql"""
        INSERT INTO t_cfp_partner (
          c_cfp_id,
          c_partner,
          c_deadline_override
        ) VALUES ${(
          cfp_id  *:
          partner *:
          core_timestamp.opt
        ).values.list(cids.length * partners.length)}
      """.command
         .contramap {
           case (cids, partners) => cids.flatMap { cid =>
             partners.map { p => (cid, p.partner, p.deadline) }
           }
         }

    def DeletePartners(cids: List[CallForProposals.Id]): AppliedFragment =
      sql"""
        DELETE FROM t_cfp_partner
          WHERE c_cfp_id IN ${cfp_id.list(cids.length).values}
      """.apply(cids)

    def InsertInstruments(
      cids:        List[CallForProposals.Id],
      instruments: List[Instrument]
    ): Command[(cids.type, instruments.type)] =
      sql"""
        INSERT INTO t_cfp_instrument (
          c_cfp_id,
          c_instrument
        ) VALUES ${(cfp_id *: instrument).values.list(cids.length * instruments.length)}
      """.command
         .contramap {
           case (cids, instruments) => cids.flatMap(instruments.tupleLeft(_))
         }

    def DeleteInstruments(cids: List[CallForProposals.Id]): AppliedFragment =
      sql"""
        DELETE FROM t_cfp_instrument
          WHERE c_cfp_id IN ${cfp_id.list(cids.length).values}
      """.apply(cids)

    def UpdateCallsForProposals(
      SET:   CallForProposalsPropertiesInput.Edit,
      which: AppliedFragment
    ): AppliedFragment = {
      val gnRaStart   = sql"c_north_ra_start  = $right_ascension"
      val gnRaEnd     = sql"c_north_ra_end    = $right_ascension"
      val gnDecStart  = sql"c_north_dec_start = $declination"
      val gnDecEnd    = sql"c_north_dec_end   = $declination"
      val gsRaStart   = sql"c_south_ra_start  = $right_ascension"
      val gsRaEnd     = sql"c_south_ra_end    = $right_ascension"
      val gsDecStart  = sql"c_south_dec_start = $declination"
      val gsDecEnd    = sql"c_south_dec_end   = $declination"
      val activeStart = sql"c_active_start    = $date"
      val activeEnd   = sql"c_active_end      = $date"

      val upExistence = sql"c_existence = $existence"
      val upSemester  = sql"c_semester  = $semester"
      val upType      = sql"c_type      = $cfp_type"

      val ups: Option[NonEmptyList[AppliedFragment]] =
        NonEmptyList.fromList(List(
          SET.gnRaLimit._1.map(gnRaStart),
          SET.gnRaLimit._2.map(gnRaEnd),
          SET.gnDecLimit._1.map(gnDecStart),
          SET.gnDecLimit._2.map(gnDecEnd),
          SET.gsRaLimit._1.map(gsRaStart),
          SET.gsRaLimit._2.map(gsRaEnd),
          SET.gsDecLimit._1.map(gsDecStart),
          SET.gsDecLimit._2.map(gsDecEnd),
          SET.deadline.foldPresent(sql"c_deadline_default = ${core_timestamp.opt}"),
          SET.active.flatMap(_.swap.toOption).map(activeStart),
          SET.active.flatMap(_.toOption).map(activeEnd),
          SET.existence.map(upExistence),
          SET.semester.map(upSemester),
          SET.cfpType.map(upType)
        ).flatten)

      def update(us: NonEmptyList[AppliedFragment]): AppliedFragment =
        void"UPDATE t_cfp "                                      |+|
          void"SET " |+| us.intercalate(void", ") |+| void" "    |+|
          void"WHERE t_cfp.c_cfp_id IN (" |+| which |+| void") " |+|
          void"RETURNING t_cfp.c_cfp_id"

       def selectOnly: AppliedFragment =
         void"SELECT c.c_cfp_id " |+|
           void"FROM t_cfp c "    |+|
           void"WHERE c.c_cfp_id IN (" |+| which |+| void")"

      ups.fold(selectOnly)(update)
    }
  }
}
