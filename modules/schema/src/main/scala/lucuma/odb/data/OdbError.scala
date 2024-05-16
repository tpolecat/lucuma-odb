// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.data

import cats.data.NonEmptyList
import cats.kernel.Eq
import cats.syntax.all.*
import io.circe.ACursor
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Encoder
import io.circe.HCursor
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import lucuma.core.model.Observation
import lucuma.core.model.Program
import lucuma.core.model.Target
import lucuma.core.model.User
import lucuma.core.model.Visit
import lucuma.core.model.sequence.Atom
import lucuma.core.model.sequence.Dataset
import lucuma.core.model.sequence.Dataset.Filename
import lucuma.core.model.sequence.Step
import lucuma.odb.json.sequence.given

/** Errors returned from the ODB in the GraphQL response extension. */
enum OdbError:
  import OdbError.Tag

  /** Detailed message, if any. */  
  def detail: Option[String]
  
  /** The detail, if any, otherwise a generic message. */
  def message: String = detail.getOrElse(OdbError.defaultMessage(this))

  case InvalidArgument(detail: Option[String] = None)      
  case NoAction(detail: Option[String] = None)          
  case NotAuthorized(userId: User.Id, detail: Option[String] = None)        
  case InvitationError(invitationId: String, detail: Option[String] = None) // TODO: UserInvitation -> core, then we can have UserInvitation.Id here
  case InvalidProgram(programId: Program.Id, detail: Option[String] = None)       
  case InvalidObservation(observationId: Observation.Id, detail: Option[String] = None)   
  case InvalidObservationList(observationIds: NonEmptyList[Observation.Id], detail: Option[String] = None)
  case SequenceUnavailable(detail: Option[String] = None)
  case InvalidTarget(targetId: Target.Id, detail: Option[String] = None)        
  case InvalidTargetList(programId: Program.Id, targetIds: NonEmptyList[Target.Id], detail: Option[String] = None)    
  case InvalidVisit(visitId: Visit.Id, detail: Option[String] = None)         
  case InvalidStep(stepId: Step.Id, detail: Option[String] = None)          
  case InvalidFilename(filename: Filename, detail: Option[String] = None)      
  case InvalidAtom(atomId: Atom.Id, detail: Option[String] = None)          
  case InvalidDataset(datasetId: Dataset.Id, detail: Option[String] = None)       
  case InvalidUser(userId: User.Id, detail: Option[String] = None)          
  case UpdateFailed(detail: Option[String] = None)         
  case ItcError(detail: Option[String] = None)             
  case GuideEnvironmentError(detail: Option[String] = None)
  case EmailSendError(detail: Option[String] = None)
  case InconsistentGroupError(detail: Option[String] = None)

object OdbError:

  // N.B. package-private to allow for Arb instance
  private[data] enum Tag(val value: String):
    case InvalidArgument        extends Tag("invalid_argument")
    case NoAction               extends Tag("no_action")
    case NotAuthorized          extends Tag("not_authorized")
    case InvitationError        extends Tag("invitation_error")
    case InvalidProgram         extends Tag("invalid_program")
    case InvalidObservation     extends Tag("invalid_observation")
    case InvalidObservationList extends Tag("invalid_observation_list")
    case SequenceUnavailable    extends Tag("sequence_unavailable")
    case InvalidTarget          extends Tag("invalid_target")
    case InvalidTargetList      extends Tag("invalid_target_list")
    case InvalidVisit           extends Tag("invalid_visit")
    case InvalidStep            extends Tag("invalid_step")
    case InvalidFilename        extends Tag("invalid_filename")
    case InvalidAtom            extends Tag("invalid_atom")
    case InvalidDataset         extends Tag("invalid_dataset")
    case InvalidUser            extends Tag("invalid_user")
    case UpdateFailed           extends Tag("update_failed")
    case ItcError               extends Tag("itc_error")
    case GuideEnvironmentError  extends Tag("guide_environment_error")
    case EmailSendError         extends Tag("email_send_error")
    case InconsistentGroup      extends Tag("inconsistent_group")

  private[data]  object Tag:

    given Encoder[Tag] = d => 
      Json.fromString(d.value)
    
    given Decoder[Tag] = c => 
      c.as[String].flatMap(s => values.find(_.value === s).toRight(DecodingFailure(s"No such OdbError: $s", Nil)))

  private def tag(e: OdbError): Tag =
    e match
      case OdbError.InvalidArgument(_)           => Tag.InvalidArgument
      case OdbError.NoAction(_)                  => Tag.NoAction
      case OdbError.NotAuthorized(_, _)          => Tag.NotAuthorized
      case OdbError.InvitationError(_, _)        => Tag.InvitationError
      case OdbError.InvalidProgram(_, _)         => Tag.InvalidProgram
      case OdbError.InvalidObservation(_, _)     => Tag.InvalidObservation
      case OdbError.InvalidObservationList(_, _) => Tag.InvalidObservationList
      case OdbError.SequenceUnavailable(_)       => Tag.SequenceUnavailable
      case OdbError.InvalidTarget(_, _)          => Tag.InvalidTarget
      case OdbError.InvalidTargetList(_, _, _)   => Tag.InvalidTargetList
      case OdbError.InvalidVisit(_, _)           => Tag.InvalidVisit
      case OdbError.InvalidStep(_, _)            => Tag.InvalidStep
      case OdbError.InvalidFilename(_, _)        => Tag.InvalidFilename
      case OdbError.InvalidAtom(_, _)            => Tag.InvalidAtom
      case OdbError.InvalidDataset(_, _)         => Tag.InvalidDataset
      case OdbError.InvalidUser(_, _)            => Tag.InvalidUser
      case OdbError.UpdateFailed(_)              => Tag.UpdateFailed
      case OdbError.ItcError(_)                  => Tag.ItcError
      case OdbError.GuideEnvironmentError(_)     => Tag.GuideEnvironmentError
      case OdbError.EmailSendError(_)            => Tag.EmailSendError
      case OdbError.InconsistentGroupError(_)    => Tag.InconsistentGroup

  private def defaultMessage(e: OdbError): String =
    e match
      case InvalidArgument(_)            => "The provided argument is not valid."
      case NoAction(_)                   => "No action was taken."
      case NotAuthorized(u, _)           => s"User $u is not authorized to perform this operation."
      case InvitationError(_, _)         => "Invitation operation could not be completed."
      case InvalidProgram(p, _)          => s"Program $p does not exist, is not visible, or is ineligible for the requested operation."
      case InvalidObservation(o, _)      => s"Observation $o does not exist, is not visible, or is ineligible for the requested operation."
      case InvalidObservationList(os, _) => s"Observation(s) ${os.toList.mkString(", ")} must exist and be associated with the same program."
      case SequenceUnavailable(_)        => "Could not generate the requested sequence."
      case InvalidTarget(t, _)           => s"Target $t does not exist, is not visible, or is ineligible for the requested operation."
      case InvalidTargetList(p, ts, _)   => s"Target(s) ${ts.toList.mkString(", ")} must exist and be associated with Program $p."
      case InvalidVisit(v, _)            => s"Visit $v does not exist, is not visible, or is ineligible for the requested operation."
      case InvalidStep(s, _)             => s"Step $s does not exist, is not visible, or is ineligible for the requested operation."
      case InvalidFilename(n, _)         => s"Filename '$n' is invalid or already exists."
      case InvalidAtom(a, _)             => s"Atom $a does not exist, is not visible, or is ineligible for the requested operation."
      case InvalidDataset(d, _)          => s"Dataset $d does not exist, is not visible, or is ineligible for the requested operation."
      case InvalidUser(u, _)             => s"User $u user does not exist, or is ineligible for the requested operation."
      case UpdateFailed(_)               => "The specified operation could not be completed."
      case ItcError(_)                   => "The requested ITC operation could not be completed."
      case GuideEnvironmentError(_)      => "The guide environment as configured is ineligible for the requested operation."
      case EmailSendError(_)             => "Unable to send the email."
      case InconsistentGroupError(_)     => "Group hierarchy is inconsistent, or a deleted group contains a non-deleted element."

  private def data(e: OdbError): JsonObject =
    e match
      case InvalidArgument(_)            => JsonObject()
      case NoAction(_)                   => JsonObject()
      case NotAuthorized(u, _)           => JsonObject("userId" -> u.asJson)
      case InvitationError(i, _)         => JsonObject("invitationId" -> i.asJson)
      case InvalidProgram(p, _)          => JsonObject("programId" -> p.asJson)
      case InvalidObservation(o, _)      => JsonObject("observationId" -> o.asJson)
      case InvalidObservationList(os, _) => JsonObject("observationIds" -> os.asJson)
      case SequenceUnavailable(_)        => JsonObject()
      case InvalidTarget(t, _)           => JsonObject("targetId" -> t.asJson)
      case InvalidTargetList(p, ts, _)   => JsonObject("programId" -> p.asJson, "targetIds" -> ts.asJson)
      case InvalidVisit(v, _)            => JsonObject("visitId" -> v.asJson)
      case InvalidStep(s, _)             => JsonObject("stepId" -> s.asJson)
      case InvalidFilename(n, _)         => JsonObject("filename" -> n.asJson)
      case InvalidAtom(a, _)             => JsonObject("atomId" -> a.asJson)
      case InvalidDataset(d, _)          => JsonObject("datasetId" -> d.asJson)
      case InvalidUser(u, _)             => JsonObject("userId" -> u.asJson)
      case UpdateFailed(_)               => JsonObject()
      case ItcError(_)                   => JsonObject()
      case GuideEnvironmentError(_)      => JsonObject()
      case EmailSendError(_)             => JsonObject()
      case InconsistentGroupError(_)     => JsonObject()
    
  private def decode(d: Tag, detail: Option[String], c: ACursor): Decoder.Result[OdbError] =
    d match
      case Tag.InvalidArgument        => InvalidArgument(detail).asRight
      case Tag.NoAction               => NoAction(detail).asRight
      case Tag.NotAuthorized          => c.downField("userId").as[User.Id].map(NotAuthorized(_, detail))
      case Tag.InvitationError        => c.downField("invitationId").as[String].map(InvitationError(_, detail))
      case Tag.InvalidProgram         => c.downField("programId").as[Program.Id].map(InvalidProgram(_, detail))
      case Tag.InvalidObservation     => c.downField("observationId").as[Observation.Id].map(InvalidObservation(_, detail))
      case Tag.InvalidObservationList => c.downField("observationIds").as[NonEmptyList[Observation.Id]].map(InvalidObservationList(_, detail))
      case Tag.SequenceUnavailable    => SequenceUnavailable(detail).asRight
      case Tag.InvalidTarget          => c.downField("targetId").as[Target.Id].map(InvalidTarget(_, detail))
      case Tag.InvalidTargetList      => (c.downField("programId").as[Program.Id], c.downField("targetIds").as[NonEmptyList[Target.Id]]).mapN(InvalidTargetList(_, _, detail))
      case Tag.InvalidVisit           => c.downField("visitId").as[Visit.Id].map(InvalidVisit(_, detail))
      case Tag.InvalidStep            => c.downField("stepId").as[Step.Id].map(InvalidStep(_, detail))
      case Tag.InvalidFilename        => c.downField("filename").as[Filename].map(InvalidFilename(_, detail))
      case Tag.InvalidAtom            => c.downField("atomId").as[Atom.Id].map(InvalidAtom(_, detail))
      case Tag.InvalidDataset         => c.downField("datasetId").as[Dataset.Id].map(InvalidDataset(_, detail))
      case Tag.InvalidUser            => c.downField("userId").as[User.Id].map(InvalidUser(_, detail))
      case Tag.UpdateFailed           => UpdateFailed(detail).asRight
      case Tag.ItcError               => ItcError(detail).asRight
      case Tag.GuideEnvironmentError  => GuideEnvironmentError(detail).asRight
      case Tag.EmailSendError         => EmailSendError(detail).asRight
      case Tag.InconsistentGroup      => InconsistentGroupError(detail).asRight
    
  private object Field:
    private val Prefix = "odb_error"
    val Tag    = s"$Prefix.tag"
    val Detail = s"$Prefix.detail"
    val Data   = s"$Prefix.data"

  given Encoder[OdbError] with
    def apply(e: OdbError): Json =
      Json.obj(
        Field.Tag    -> tag(e).asJson,
        Field.Detail -> e.detail.asJson,
        Field.Data   -> data(e).asJson
      )

  given Decoder[OdbError] with
    def apply(c: HCursor): Decoder.Result[OdbError] =
      for
        disc   <- c.downField(Field.Tag).as[Tag]
        detail <- c.downField(Field.Detail).as[Option[String]]
        error  <- decode(disc, detail, c.downField(Field.Data))
      yield error
