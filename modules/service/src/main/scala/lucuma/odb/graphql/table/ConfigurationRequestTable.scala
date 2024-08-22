// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql
package table

import lucuma.odb.util.Codecs.*
import lucuma.odb.util.GmosCodecs.*

trait ConfigurationRequestTable[F[_]] extends BaseMapping[F]:

  object ConfigurationRequestTable extends TableDef("t_configuration_request"):

    val Id = col("c_configuration_request_id", configuration_request_id)
    val ProgramId = col("c_program_id", program_id)
    val Status = col("c_status", configuration_request_status)

    object Conditions:
      val CloudExtinction = col("c_cloud_extinction", cloud_extinction)
      val ImageQuality = col("c_image_quality", image_quality)
      val SkyBackground = col("c_sky_background", sky_background)
      val WaterVapor = col("c_water_vapor", water_vapor)

    object ReferenceCoordinates:
      val Ra = col("c_reference_ra", right_ascension)
      val Dec = col("c_reference_dec", declination)

    val ObservingModeType = col("c_observing_mode_type", observing_mode_type)
    val GmosNorthLongslitGrating = col("c_gmos_north_longslit_grating", gmos_north_grating.opt)
    val GmosSouthLongslitGrating = col("c_gmos_south_longslit_grating", gmos_south_grating.opt)

