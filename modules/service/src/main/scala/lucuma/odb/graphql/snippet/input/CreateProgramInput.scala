// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql.snippet
package input

import cats.syntax.all._
import eu.timepit.refined.types.string.NonEmptyString
import lucuma.odb.data.Existence
import lucuma.odb.graphql.util.Bindings._

case class CreateProgramInput(
  SET: ProgramPropertiesInput
)

object CreateProgramInput {
  val Binding: Matcher[CreateProgramInput] =
    ObjectFieldsBinding.rmap {
      case List(
        ProgramPropertiesInput.Binding.Option("SET", rInput)
      ) => rInput.map(o => CreateProgramInput(o.getOrElse(ProgramPropertiesInput.Default)))
    }
}

case class ProgramPropertiesInput(
  name: Option[NonEmptyString],
  // TODO: Proposal
  existence: Existence
)

object ProgramPropertiesInput {

  val Default: ProgramPropertiesInput =
    ProgramPropertiesInput(None, Existence.Present)

  val Binding: Matcher[ProgramPropertiesInput] =
    ObjectFieldsBinding.rmap {
      case List(
        NonEmptyStringBinding.Option("name", rName),
        ("proposal", _), // ignored
        ExistenceBinding.Option("existence", rExistence),
      ) =>
        (rName, rExistence).parMapN { (on, oe) =>
          ProgramPropertiesInput(on, oe.getOrElse(Existence.Present))
       }
      case x =>
        println("? " + x)
        throw new Error("huh?")
    }

}