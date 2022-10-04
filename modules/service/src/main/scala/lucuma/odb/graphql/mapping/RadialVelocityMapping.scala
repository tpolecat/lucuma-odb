// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql

package mapping

import edu.gemini.grackle.skunk.SkunkMapping
import lucuma.core.math.RadialVelocity
import lucuma.odb.graphql.util.MappingExtras

import table.TargetView
import table.ProgramTable

trait RadialVelocityMapping[F[_]] extends ProgramTable[F] with TargetView[F] {

  lazy val RadialVelocityMapping =
    ObjectMapping(
      tpe = RadialVelocityType,
      fieldMappings = List(
        SqlField("synthetic_id", TargetView.Sidereal.RadialVelocity.SyntheticId, key = true, hidden = true),
        SqlField("value", TargetView.Sidereal.RadialVelocity.Value, hidden = true),
        FieldRef[RadialVelocity]("value").as("metersPerSecond", _.rv.value),
        FieldRef[RadialVelocity]("value").as("kilometersPerSecond", _.rv.value / BigDecimal(1000)),
        FieldRef[RadialVelocity]("value").as("centimetersPerSecond", v => (v.rv.value * BigDecimal(100)).toLong),
      )
    )

  }

