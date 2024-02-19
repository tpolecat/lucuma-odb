// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql

package mapping

import cats.effect.Resource
import cats.syntax.all._
import eu.timepit.refined.types.numeric.NonNegShort
import grackle.Predicate
import grackle.Predicate._
import grackle.Query
import grackle.Query._
import grackle.QueryCompiler.Elab
import grackle.TypeRef
import grackle.skunk.SkunkMapping
import lucuma.core.model.Group
import lucuma.core.model.ObsAttachment
import lucuma.core.model.Observation
import lucuma.core.model.Program
import lucuma.core.model.User
import lucuma.core.model.sequence.CategorizedTime
import lucuma.core.model.sequence.CategorizedTimeRange
import lucuma.itc.client.ItcClient
import lucuma.odb.data.Tag
import lucuma.odb.graphql.predicate.Predicates
import lucuma.odb.graphql.table.GroupElementView
import lucuma.odb.json.time.query.given
import lucuma.odb.json.timeaccounting.given
import lucuma.odb.logic.TimeEstimateCalculator
import lucuma.odb.sequence.util.CommitHash
import lucuma.odb.service.Services

import Services.Syntax.*
import binding._
import table._

trait ProgramMapping[F[_]]
  extends ProgramTable[F]
     with UserTable[F]
     with ProgramUserTable[F]
     with ProposalTable[F]
     with ObservationView[F]
     with ObsAttachmentTable[F]
     with Predicates[F]
     with ProposalAttachmentTable[F]
     with ResultMapping[F]
     with GroupElementView[F]
     with UserInvitationTable[F]
     with KeyValueEffectHandler[F] {

  def user: User
  def itcClient: ItcClient[F]
  def services: Resource[F, Services[F]]
  def commitHash: CommitHash
  def timeEstimateCalculator: TimeEstimateCalculator.ForInstrumentMode

  lazy val ProgramMapping: ObjectMapping =
    ObjectMapping(
      tpe = ProgramType,
      fieldMappings = List(
        SqlField("id", ProgramTable.Id, key = true),
        SqlField("existence", ProgramTable.Existence, hidden = true),
        SqlField("name", ProgramTable.Name),

        SqlField("programType",      ProgramTable.ProgramType),
        SqlField("instrument",       ProgramTable.Reference.Instrument),
        SqlField("scienceSubtype",   ProgramTable.Reference.ScienceSubtype),
        SqlField("semester",         ProgramTable.Reference.Semester),
        SqlField("semesterIndex",    ProgramTable.Reference.SemesterIndex),
        SqlField("programReference", ProgramTable.Reference.ProgramReference),

        SqlField("piUserId", ProgramTable.PiUserId, hidden = true),
        SqlField("proposalStatus", ProgramTable.ProposalStatus),
        SqlObject("pi", Join(ProgramTable.PiUserId, UserTable.UserId)),
        SqlObject("users", Join(ProgramTable.Id, ProgramUserTable.ProgramId)),
        SqlObject("observations"),
        SqlObject("proposal", Join(ProgramTable.Id, ProposalTable.ProgramId)),
        SqlObject("groupElements", Join(ProgramTable.Id, GroupElementView.ProgramId)),
        SqlObject("allGroupElements", Join(ProgramTable.Id, GroupElementView.ProgramId)),
        SqlObject("obsAttachments", Join(ProgramTable.Id, ObsAttachmentTable.ProgramId)),
        SqlObject("proposalAttachments", Join(ProgramTable.Id, ProposalAttachmentTable.ProgramId)),
        EffectField("timeEstimateRange", timeEstimateHandler, List("id")),
        EffectField("timeCharge", timeChargeHandler, List("id")),
        SqlObject("userInvitations", Join(ProgramTable.Id, UserInvitationTable.ProgramId)),
      )
    )

  lazy val ProgramElaborator: PartialFunction[(TypeRef, String, List[Binding]), Elab[Unit]] = {

    case (ProgramType, "observations", List(
      BooleanBinding("includeDeleted", rIncludeDeleted),
      ObservationIdBinding.Option("OFFSET", rOFFSET),
      NonNegIntBinding.Option("LIMIT", rLIMIT),
    )) =>
      Elab.transformChild { child =>
        (rIncludeDeleted, rOFFSET, rLIMIT).parTupled.flatMap { (includeDeleted, OFFSET, lim) =>
          val limit = lim.fold(ResultMapping.MaxLimit)(_.value)
          ResultMapping.selectResult(child, limit) { q =>
            FilterOrderByOffsetLimit(
              pred = Some(and(List(
                Predicates.observation.existence.includeDeleted(includeDeleted),
                OFFSET.fold[Predicate](True)(Predicates.observation.id.gtEql)
              ))),
              oss = Some(List(OrderSelection[Observation.Id](ObservationType / "id", true, true))),
              offset = None,
              limit = Some(limit + 1),
              q
            )
          }
        }
      }

    case (ProgramType, "groupElements", Nil) =>
      Elab.transformChild { child =>
        FilterOrderByOffsetLimit(
          pred = Some(Predicates.groupElement.parentGroupId.isNull(true)),
          oss = Some(List(OrderSelection[NonNegShort](GroupElementType / "parentIndex", true, true))),
          offset = None,
          limit = None,
          child
        )
      }

    case (ProgramType, "allGroupElements", Nil) =>
      Elab.transformChild { child =>
        FilterOrderByOffsetLimit(
          pred = None,
          oss = Some(List(
            OrderSelection[Option[Group.Id]](GroupElementType / "parentGroupId", true, true),
            OrderSelection[NonNegShort](GroupElementType / "parentIndex", true, true)
          )),
          offset = None,
          limit = None,
          child
        )
      }

    case (ProgramType, "obsAttachments", Nil) =>
      Elab.transformChild { child =>
        OrderBy(OrderSelections(List(OrderSelection[ObsAttachment.Id](ObsAttachmentType / "id"))), child)
      }

    case (ProgramType, "proposalAttachments", Nil) =>
      Elab.transformChild { child =>
        OrderBy(OrderSelections(List(OrderSelection[Tag](ProposalAttachmentType / "attachmentType"))), child)
      }
  }

  private lazy val timeEstimateHandler: EffectHandler[F] =
    keyValueEffectHandler[Program.Id, Option[CategorizedTimeRange]]("id") { pid =>
      services.useNonTransactionally {
        timeEstimateService(commitHash, itcClient, timeEstimateCalculator)
          .estimateProgram(pid)
      }
    }

  private val timeChargeHandler: EffectHandler[F] =
    keyValueEffectHandler[Program.Id, CategorizedTime]("id") { pid =>
      services.useTransactionally {
        timeAccountingService.selectProgram(pid)
      }
    }

}