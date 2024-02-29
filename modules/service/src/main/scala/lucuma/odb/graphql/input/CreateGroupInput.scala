// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql
package input

import cats.syntax.all.*
import lucuma.core.model.Program
import lucuma.odb.data.ProgramReference
import lucuma.odb.data.ProposalReference
import lucuma.odb.graphql.binding._

case class CreateGroupInput(
  programId:         Option[Program.Id],
  proposalReference: Option[ProposalReference],
  programReference:  Option[ProgramReference],
  SET:               GroupPropertiesInput.Create
)

object CreateGroupInput {
  val Binding: Matcher[CreateGroupInput] =
    ObjectFieldsBinding.rmap {
      case List(
        ProgramIdBinding.Option("programId", rPid),
        ProposalReferenceBinding.Option("proposalReference", rProp),
        ProgramReferenceBinding.Option("programReference", rProg),
        GroupPropertiesInput.CreateBinding.Option("SET", rInput)
      ) => (rPid, rProp, rProg, rInput).mapN { (pid, prop, prog, oset) =>
        CreateGroupInput(pid, prop, prog, oset.getOrElse(GroupPropertiesInput.Empty))
      }
    }
}



