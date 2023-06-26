// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql
package table

import lucuma.odb.util.Codecs._
import skunk.codec.all._

trait ChronConditionsEntryView[F[_]] extends BaseMapping[F] {

  object ChronConditionsEntryView extends TableDef("v_chron_conditions_entry") {
    val ChronId      = col("c_chron_id", int8)
    val Timestamp    = col("c_timestamp", timestamptz)
    val UserId       = col("c_user", user_id.opt)
    val TransationId = col("c_transaction_id", int8) // xid8
    object Measurement {
      val SyntheticId   = col("c_measurement_id", int8.embedded)
      val Source        = col("c_measurement_source", tag.embedded)
      val Seeing        = col("c_measurement_seeing", angle_µas.opt)
      val ExtinctionPct = col("c_measurement_extinction_pct", int_percent.opt)
      val Azimuth       = col("c_measurement_azimuth", angle_µas.opt)
      val Elevation     = col("c_measurement_elevation", angle_µas.opt)
      object Wavelength {
        val SyntheticId = col("c_wavelength_id", int8.embedded)
        val Value       = col("c_measurement_wavelength", wavelength_pm.embedded)
      }
    }
    object Intuition {
      val SyntheticId = col("c_intuition_id", int8.embedded)
      val SeeingTrend = col("c_intuition_seeing_trend", tag.opt)
      object Expectation {
        val SyntheticId = col("c_expectation_id", int8.embedded)
        val Expectation = col("c_intuition_expectation", tag.embedded)
        val Timespan    = col("c_intuition_timespan", time_span.embedded)
      }
    }
  }

}