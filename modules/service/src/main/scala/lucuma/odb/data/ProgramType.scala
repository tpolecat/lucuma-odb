// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.data

import cats.Order
import cats.syntax.eq.*
import lucuma.core.util.Enumerated


enum ProgramType(val tag: String, val abbreviation: String) derives Enumerated:
  case Calibration extends ProgramType("calibration", "CAL")
  case Engineering extends ProgramType("engineering", "ENG")
  case Example     extends ProgramType("example", "XPL")
  case Library     extends ProgramType("library", "LIB")
  case Science     extends ProgramType("science", "SCI")

object ProgramType {

  def fromAbbreviation(a: String): Option[ProgramType] =
    values.find(_.abbreviation === a)

  given Order[ProgramType] =
    Order.by(_.abbreviation)

  given Ordering[ProgramType] =
    Order.catsKernelOrderingForOrder

}

