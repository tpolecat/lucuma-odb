// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.service

import cats.Monad
import cats.Semigroup
import cats.data.NonEmptyList
import cats.effect.Concurrent
import cats.syntax.all._
import eu.timepit.refined.types.string.NonEmptyString
import lucuma.core.enums.ToOActivation
import lucuma.core.model.GuestRole
import lucuma.core.model.IntPercent
import lucuma.core.model.Program
import lucuma.core.model.ServiceRole
import lucuma.core.model.ServiceUser
import lucuma.core.model.StandardRole
import lucuma.core.model.StandardRole.Ngo
import lucuma.core.model.StandardRole.Pi
import lucuma.core.model.User
import lucuma.odb.data._
import lucuma.odb.graphql.input.ProgramPropertiesInput
import lucuma.odb.graphql.input.ProposalInput
import lucuma.odb.service.ProgramService.LinkUserRequest.PartnerSupport
import lucuma.odb.service.ProgramService.LinkUserRequest.StaffSupport
import lucuma.odb.service.ProgramService.UpdateProgramResponse
import lucuma.odb.service.ProgramService.UpdateProgramResponse.ProposalUpdateFailed
import lucuma.odb.service.ProposalService.UpdateProposalResponse
import lucuma.odb.util.Codecs._
import natchez.Trace
import skunk._
import skunk.codec.all._
import skunk.syntax.all._

trait ProgramService[F[_]] {

  /**
   * Insert a new program, where the calling user becomes PI (unless it's a Service user, in which
   * case the PI is left empty.
   */
  def insertProgram(SET: Option[ProgramPropertiesInput.Create]): F[Program.Id]

  /**
   * Perform the requested program <-> user link, yielding the linked ids if successful, or None
   * if the user was not authorized to perform the action.
   */
  def linkUser(req: ProgramService.LinkUserRequest): F[ProgramService.LinkUserResponse]

  /** Update the properies for programs with ids given by the supplied fragment, yielding a list of affected ids. */
  def updatePrograms(SET: ProgramPropertiesInput.Edit, where: AppliedFragment): F[UpdateProgramResponse]

}

object ProgramService {

  sealed class UpdateProgramResponse
  object UpdateProgramResponse {

    case class Success(pids: List[Program.Id]) extends UpdateProgramResponse
    case class ProposalUpdateFailed(failure: ProposalService.UpdateProposalResponse.Failure) extends UpdateProgramResponse

    given Semigroup[UpdateProgramResponse] with
      def combine(a: UpdateProgramResponse, b: UpdateProgramResponse): UpdateProgramResponse =
        (a, b) match {
          case (Success(a), Success(b)) => Success((a ++ b).distinct)
          case (Success(_), failure)    => failure
          case (failure, _)             => failure
        }

  }

  sealed abstract class LinkUserRequest(val role: ProgramUserRole, val supportType: Option[ProgramUserSupportType] = None, val supportPartner: Option[Tag] = None) {
    def programId: Program.Id
    def userId: User.Id
  }
  object LinkUserRequest {

    case class Coi(programId: Program.Id, userId: User.Id) extends LinkUserRequest(ProgramUserRole.Coi)
    case class Observer(programId: Program.Id, userId: User.Id) extends LinkUserRequest(ProgramUserRole.Observer)
    case class StaffSupport(programId: Program.Id, userId: User.Id) extends LinkUserRequest(ProgramUserRole.Support, Some(ProgramUserSupportType.Staff))
    case class PartnerSupport(programId: Program.Id, userId: User.Id, partnerTag: Tag) extends LinkUserRequest(ProgramUserRole.Support, Some(ProgramUserSupportType.Partner), Some(partnerTag))

    /** Construct a LinkedUserRequest from unvalidated inputs, if possible. */
    def validate(
      programId: Program.Id,
      userId: User.Id,
      role: ProgramUserRole,
      supportType: Option[ProgramUserSupportType],
      supportPartner: Option[Tag]
    ): Either[String, LinkUserRequest] =
      role match {
        case ProgramUserRole.Coi =>
          (supportType orElse supportPartner)
            .as("Support type/partner must not be specified for COI role.")
            .toLeft(Coi(programId, userId))
        case ProgramUserRole.Observer =>
          (supportType orElse supportPartner)
            .as("Support type/partner must not be specified for OBSERVER role.")
            .toLeft(Observer(programId, userId))
        case ProgramUserRole.Support =>
          (supportType, supportPartner) match {
            case (Some(ProgramUserSupportType.Staff), None)        => Right(StaffSupport(programId, userId))
            case (Some(ProgramUserSupportType.Staff), _)           => Left("Support partner must not be specified if support type is STAFF.")
            case (Some(ProgramUserSupportType.Partner), Some(tag)) => Right(PartnerSupport(programId, userId, tag))
            case (Some(ProgramUserSupportType.Partner), _)         => Left("Support partner must be specified if support type is PARTNER.")
            case (None, _)                                         => Left("Support type must be specifed if role is SUPPORT.")
          }
      }

  }

  sealed trait LinkUserResponse extends Product with Serializable
  object LinkUserResponse {
    case class NotAuthorized(user: User)                     extends LinkUserResponse
    case class AlreadyLinked(pid: Program.Id, user: User.Id) extends LinkUserResponse
    case class Success(pis: Program.Id, user: User.Id)       extends LinkUserResponse
    case class InvalidUser(user: User.Id)                    extends LinkUserResponse
  }

  /**
   * Construct a `ProgramService` using the specified `Session`, for the specified `User`. All
   * operations will be performed on behalf of `user`.
   */
  def fromSessionAndUser[F[_]: Concurrent: Trace](s: Session[F], user: User): ProgramService[F] =
    new ProgramService[F] {

      lazy val proposalService = ProposalService.fromSession(s)

      def insertProgram(SET: Option[ProgramPropertiesInput.Create]): F[Program.Id] =
        Trace[F].span("insertProgram") {
          s.transaction.use { xa =>
            val SETʹ = SET.getOrElse(ProgramPropertiesInput.Create(None, None, None))
            s.prepare(Statements.InsertProgram).use(_.unique(SETʹ.name ~ user)).flatTap { pid =>
              SETʹ.proposal.traverse { proposalInput =>
                proposalService.insertProposal(proposalInput, pid, xa)
              }
            }
          }
        }

      def linkUser(req: ProgramService.LinkUserRequest): F[LinkUserResponse] = {
        val af: Option[AppliedFragment] =
          req match {
            case LinkUserRequest.Coi(programId, userId) => Statements.linkCoi(programId, userId, user)
            case LinkUserRequest.Observer(programId, userId) => Statements.linkObserver(programId, userId, user)
            case StaffSupport(programId, userId) => Statements.linkStaffSupport(programId, userId, user)
            case PartnerSupport(programId, userId, partnerTag) => Statements.linkPartnerSupport(programId, userId, user, partnerTag)
          }
        af match {
          case None     =>  Monad[F].pure(LinkUserResponse.NotAuthorized(user))
          case Some(af) =>
            val stmt = sql"${af.fragment} RETURNING c_program_id, c_user_id".query(program_id ~ user_id)
            s.prepare(stmt).use { pq =>
              pq.option(af.argument).map {
                case Some(pid ~ uid) => LinkUserResponse.Success(pid, uid)
                case None            => LinkUserResponse.NotAuthorized(user)
              } .recover {
                case SqlState.UniqueViolation(_) => LinkUserResponse.AlreadyLinked(req.programId, req.userId)
                case SqlState.ForeignKeyViolation(_) => LinkUserResponse.InvalidUser(req.userId)
              }
            }
        }
      }

      def updatePrograms(SET: ProgramPropertiesInput.Edit, where: AppliedFragment): F[UpdateProgramResponse] =
        s.transaction.use { xa =>

          // Update the program itself
          val a: F[UpdateProgramResponse] =
            Statements.updatePrograms(SET, where).fold(UpdateProgramResponse.Success(Nil).pure[F]) { af =>
              s.prepare(af.fragment.query(program_id)).use { ps =>
                ps.stream(af.argument, 1024)
                  .compile
                  .toList
                  .map(UpdateProgramResponse.Success(_))
              }
            }

          // Update the proposal
          val b: F[UpdateProgramResponse] =
            SET.proposal.fold(UpdateProgramResponse.Success(Nil).pure[F]) {
              proposalService.updateProposals(_, where, xa).map {
                case UpdateProposalResponse.Success(pids)      => UpdateProgramResponse.Success(pids)
                case UpdateProposalResponse.CreationFailed     => UpdateProgramResponse.ProposalUpdateFailed(UpdateProposalResponse.CreationFailed)
                case UpdateProposalResponse.InconsistentUpdate => UpdateProgramResponse.ProposalUpdateFailed(UpdateProposalResponse.InconsistentUpdate)
              }
            }

          // Combie the results
          (a, b).mapN(_ |+| _)

        }

    }


  object Statements {

    def updates(SET: ProgramPropertiesInput.Edit): Option[NonEmptyList[AppliedFragment]] =
      NonEmptyList.fromList(
        List(
          SET.existence.map(sql"c_existence = $existence"),
          SET.name.map(sql"c_name = $text_nonempty"),
        ).flatten
      )

    def updatePrograms(SET: ProgramPropertiesInput.Edit, which: AppliedFragment): Option[AppliedFragment] =
      updates(SET).map { us =>
        void"UPDATE t_program " |+|
        void"SET " |+| us.intercalate(void", ") |+| void" " |+|
        void"WHERE t_program.c_program_id IN (" |+| which |+| void") " |+|
        void"RETURNING t_program.c_program_id"
      }

    def existsUserAsPi(
      programId: Program.Id,
      userId: User.Id,
    ): AppliedFragment =
      sql"""
        EXISTS (select c_program_id from t_program where c_program_id = $program_id and c_pi_user_id = $user_id)
      """.apply(programId ~ userId)

    def existsUserAsCoi(
      programId: Program.Id,
      userId: User.Id,
    ): AppliedFragment =
      sql"""
        EXISTS (select c_role from t_program_user where  c_program_id = $program_id and c_user_id = $user_id and c_role = 'coi')
      """.apply(programId ~ userId)

    def existsAllocationForPartner(
      programId: Program.Id,
      partner: Tag
    ): AppliedFragment =
      sql"""
        EXISTS (select c_duration from t_allocation where c_program_id = $program_id and c_partner=$tag and c_duration > 'PT')
        """.apply(programId ~ partner)


    def whereUserAccess(
      user:      User,
      programId: Program.Id
    ): AppliedFragment =
      user.role match {
        case GuestRole       => void"WHERE " |+| existsUserAsPi(programId, user.id)
        case Pi(_)           => void"WHERE " |+| existsUserAsPi(programId, user.id) |+| void" OR " |+| existsUserAsCoi(programId, user.id)
        case Ngo(_, partner) => void"WHERE " |+| existsAllocationForPartner(programId, Tag(partner.tag))
        case ServiceRole(_)        |
             StandardRole.Admin(_) |
             StandardRole.Staff(_)   => AppliedFragment.empty
      }


    /** Insert a program, making the passed user PI if it's a non-service user. */
    val InsertProgram: Query[Option[NonEmptyString] ~ User, Program.Id] =
      sql"""
        INSERT INTO t_program (c_name, c_pi_user_id, c_pi_user_type)
        VALUES (${text_nonempty.opt}, ${(user_id ~ user_type).opt})
        RETURNING c_program_id
      """.query(program_id)
         .contramap {
            case oNes ~ ServiceUser(_, _) => oNes ~ None
            case oNes ~ nonServiceUser    => oNes ~ Some(nonServiceUser.id ~ UserType.fromUser(nonServiceUser))
         }

    /** Link a user to a program, without any access checking. */
    val LinkUser: Fragment[Program.Id ~ User.Id ~ ProgramUserRole ~ Option[ProgramUserSupportType] ~ Option[Tag]] =
      sql"""
         INSERT INTO t_program_user (c_program_id, c_user_id, c_user_type, c_role, c_support_type, c_support_partner)
         SELECT $program_id, $user_id, 'standard', $program_user_role, ${program_user_support_type.opt}, ${tag.opt}
        """

    /**
     * Link a co-investigator to a program.
     * - Guests cannot do this.
     * - Staff, Admin, and Service users can always do this.
     * - Standard user can only do this if they're the program's PI.
     */
    def linkCoi(
      targetProgram: Program.Id,
      targetUser: User.Id, // user to link
      user: User, // current user
    ): Option[AppliedFragment] = {
      val up = LinkUser(targetProgram ~ targetUser ~ ProgramUserRole.Coi ~ None ~ None)
      user.role match {
        case GuestRole                    => None
        case ServiceRole(_)               => Some(up)
        case StandardRole.Admin(_)        => Some(up)
        case StandardRole.Ngo(_, partner) => Some(up |+| void" WHERE " |+| existsAllocationForPartner(targetProgram, Tag(partner.tag)))
        case StandardRole.Pi(_)           => Some(up |+| void" WHERE " |+| existsUserAsPi(targetProgram, user.id))
        case StandardRole.Staff(_)        => Some(up)
      }
    }

    /**
     * Link an observer to a program.
     * - Guests cannot do this.
     * - Staff, Admin, and Service users can always do this.
     * - Standard user can only do this if they're the program's PI or Coi.
     */
    def linkObserver(
      targetProgram: Program.Id,
      targetUser: User.Id, // user to link
      user: User, // current user
    ): Option[AppliedFragment] = {
      val up = LinkUser(targetProgram ~ targetUser ~ ProgramUserRole.Observer ~ None ~ None)
      user.role match {
        case GuestRole                    => None
        case ServiceRole(_)               => Some(up)
        case StandardRole.Admin(_)        => Some(up)
        case StandardRole.Ngo(_, partner) => Some(up |+| void" WHERE " |+| existsAllocationForPartner(targetProgram, Tag(partner.tag)))
        case StandardRole.Staff(_)        => Some(up)
        case StandardRole.Pi(_)           =>
          Some(
            up |+| void" WHERE " |+| existsUserAsPi(targetProgram, user.id)  |+|
                   void" OR "    |+| existsUserAsCoi(targetProgram, user.id)
          )
      }
    }

    /**
     * Link staff support to a program.
     * - Staff, Admin, and Service users can always do this.
     * - Nobody else can do this.
     */
    def linkStaffSupport(
      targetProgram: Program.Id,
      targetUser: User.Id, // user to link
      user: User, // current user
    ): Option[AppliedFragment] = {
      import lucuma.core.model.Access._
      val up = LinkUser(targetProgram ~ targetUser ~ ProgramUserRole.Support ~ Some(ProgramUserSupportType.Staff) ~ None)
      user.role.access match {
        case Admin | Staff | Service => Some(up) // ok
        case _                       => None // nobody else can do this
      }
    }

    /**
     * Link partner support to a program.
     * - Staff, Admin, and Service users can always do this.
     * - Nobody else can do this.
     */
    def linkPartnerSupport(
      targetProgram: Program.Id,
      targetUser: User.Id, // user to link
      user: User, // current user
      partner: Tag, // partner
    ): Option[AppliedFragment] = {
      import lucuma.core.model.Access._
      val up = LinkUser(targetProgram ~ targetUser ~ ProgramUserRole.Support ~ Some(ProgramUserSupportType.Partner) ~ Some(partner))
      user.role.access match {
        case Admin | Staff | Service => Some(up) // ok
        case _                       => None // nobody else can do this
      }
    }

  }

}