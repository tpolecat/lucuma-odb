// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql

package mapping

import eu.timepit.refined.cats.*
import grackle.Query.*
import grackle.QueryCompiler.Elab
import grackle.TypeRef
import grackle.skunk.SkunkMapping
import lucuma.core.model.IntPercent
import lucuma.odb.data.Tag
import lucuma.odb.graphql.table.PartnerSplitTable
import lucuma.odb.graphql.table.ProposalTable

import table.ProgramTable

trait ProposalMapping[F[_]] extends PartnerSplitTable[F] with ProgramTable[F] with ProposalTable[F] {

  lazy val ProposalMapping =
    ObjectMapping(
      tpe = ProposalType,
      fieldMappings = List(
        SqlField("program_id", ProposalTable.ProgramId, key = true, hidden = true),
        SqlField("title", ProposalTable.Title),
        SqlField("category", ProposalTable.Category),
        SqlField("toOActivation", ProposalTable.TooActivation),
        SqlField("abstract", ProposalTable.Abstrakt),
        SqlObject("proposalClass"),
        SqlObject("partnerSplits", Join(ProposalTable.ProgramId, PartnerSplitTable.ProgramId))
      )
    )

  lazy val ProposalElaborator: PartialFunction[(TypeRef, String, List[Binding]), Elab[Unit]] = {
    case (ProposalType, "partnerSplits", Nil) =>
      Elab.transformChild{ child =>
        OrderBy(
          OrderSelections(
            List(
              OrderSelection[IntPercent](PartnerSplitType / "percent", ascending = false),
              OrderSelection[Tag](PartnerSplitType / "partner")
            )
          ),
          child
        )
      }
  }
}

