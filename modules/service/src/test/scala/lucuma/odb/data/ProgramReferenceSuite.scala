// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.data

import cats.kernel.laws.discipline.*
import lucuma.core.optics.laws.discipline.*
import lucuma.odb.data.arb.ArbProgramReference
import monocle.law.discipline.PrismTests

final class ProgramReferenceSuite extends munit.DisciplineSuite {

  import ArbProgramReference.given
  import ArbProgramReference.*

  checkAll("ProgramReference", OrderTests[ProgramReference].order)

  import ProgramReference.*
  checkAll("Calibration.fromString", FormatTests(Calibration.fromString).formatWith(calibrationStrings))
  checkAll("Engineering.fromString", FormatTests(Engineering.fromString).formatWith(engineeringStrings))
  checkAll("Example.fromString",     PrismTests(Example.fromString))
  checkAll("Library.fromString",     PrismTests(Library.fromString))
  checkAll("Science.fromString",     FormatTests(Science.fromString).formatWith(scienceStrings))

  checkAll("ProgramReference", FormatTests(ProgramReference.fromString).formatWith(programReferenceStrings))
}
