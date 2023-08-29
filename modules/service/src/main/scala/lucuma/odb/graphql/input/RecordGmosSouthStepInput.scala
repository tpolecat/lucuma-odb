// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql
package input

import cats.syntax.parallel.*
import eu.timepit.refined.types.numeric.NonNegShort
import lucuma.core.model.sequence.Atom
import lucuma.core.model.sequence.StepConfig
import lucuma.core.model.sequence.gmos.DynamicConfig.GmosSouth
import lucuma.odb.graphql.binding.*

case class RecordGmosSouthStepInput(
  atomId:     Atom.Id,
  instrument: GmosSouth,
  step:       StepConfig,
  stepIndex:  NonNegShort
)

object RecordGmosSouthStepInput {

  val Binding: Matcher[RecordGmosSouthStepInput] =
    ObjectFieldsBinding.rmap {
      case List(
        AtomIdBinding("atomId", rAtomId),
        GmosSouthDynamicInput.Binding("instrument", rInstrument),
        StepConfigInput.Binding("stepConfig", rStepConfig),
        NonNegShortBinding("stepIndex", rStepIndex)
      ) => (rAtomId, rInstrument, rStepConfig, rStepIndex).parMapN { (atomId, instrument, step, stepIndex) =>
        RecordGmosSouthStepInput(atomId, instrument, step, stepIndex)
      }
    }

}
