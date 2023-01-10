// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql
package mapping

import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.all._
import edu.gemini.grackle.skunk.SkunkMapping
import edu.gemini.grackle.skunk.SkunkMonitor
import edu.gemini.grackle.sql.SqlMapping
import io.circe
import lucuma.core.math.Declination
import lucuma.odb.graphql.table.ObservationView
import lucuma.odb.graphql.util.MappingExtras
import lucuma.odb.util.Codecs._
import skunk.Session
import skunk.circe.codec.all._
import skunk.codec.all._

import scala.reflect.ClassTag

import table.TargetView

trait DeclinationMapping[F[_]] extends ObservationView[F] with TargetView[F] {

  private def declinationMapping(
    idColumn:    ColumnRef,
    valueColumn: ColumnRef
  ): ObjectMapping =
    ObjectMapping(
      tpe = DeclinationType,
      fieldMappings = List(
        SqlField("synthetic_id", idColumn, key = true, hidden = true),
        SqlField("value", valueColumn, hidden = true),
        FieldRef[Declination]("value").as("dms", Declination.fromStringSignedDMS.reverseGet),
        FieldRef[Declination]("value").as("degrees", c => BigDecimal(c.toAngle.toDoubleDegrees)),
        FieldRef[Declination]("value").as("microarcseconds", _.toAngle.toMicroarcseconds),
      )
    )

  lazy val DeclinationMapping: TypeMapping =
    SwitchMapping(
      DeclinationType,
      List(
        (CoordinatesType, "dec", declinationMapping(ObservationView.TargetEnvironment.Coordinates.SyntheticId, ObservationView.TargetEnvironment.Coordinates.Dec)),
        (SiderealType,    "dec", declinationMapping(TargetView.Sidereal.SyntheticId, TargetView.Sidereal.Dec))
      )
    )

}

