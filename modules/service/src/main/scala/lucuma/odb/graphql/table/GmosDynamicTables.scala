// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql
package table

import lucuma.odb.util.Codecs.*
import lucuma.odb.util.GmosCodecs.*
import skunk.codec.text.varchar

trait GmosDynamicTables[F[_]] extends BaseMapping[F] {

  class GmosDynamicTable[G, L, U](
    name:    String,
    grating: skunk.Codec[G],
    filter:  skunk.Codec[L],
    fpu:     skunk.Codec[U]
  ) extends TableDef(name) {
    val Id: ColumnRef                     = col("c_step_id",                    step_id)
    val Instrument: ColumnRef             = col("c_instrument",                 instrument)

    val ExposureTime: ColumnRef           = col("c_exposure_time",              time_span)

    object CcdMode {
      val Xbin: ColumnRef                 = col("c_xbin",                       gmos_x_binning)
      val Ybin: ColumnRef                 = col("c_ybin",                       gmos_y_binning)
      val AmpCount: ColumnRef             = col("c_amp_count",                  gmos_amp_count)
      val AmpGain: ColumnRef              = col("c_amp_gain",                   gmos_amp_gain)
      val AmpReadMode: ColumnRef          = col("c_amp_read_mode",              gmos_amp_read_mode)
    }

    val Dtax: ColumnRef                   = col("c_dtax",                       gmos_dtax)
    val Roi: ColumnRef                    = col("c_roi",                        gmos_roi)

    // Grating (if any)
    object Grating {
      val Disperser: ColumnRef     = col("c_grating_disperser",          grating.opt)
      val Order: ColumnRef         = col("c_grating_order",              gmos_grating_order.opt)
      val Wavelength: ColumnRef    = col("c_grating_wavelength",         wavelength_pm.opt)
    }

    val Filter: ColumnRef                 = col("c_filter",                     filter.opt)

    // FPU (custom or builtin or none)
    object Fpu {
      val CustomMaskFilename: ColumnRef  = col("c_fpu_custom_mask_filename",   varchar.opt)
      val CustomMaskSlitWidth: ColumnRef = col("c_fpu_custom_mask_slit_width", gmos_custom_slit_width.opt)
      val Builtin: ColumnRef             = col("c_fpu_builtin",                fpu.opt)
    }

  }

  object GmosNorthDynamicTable extends TableDef("t_gmos_north_dynamic") {
    val Id: ColumnRef                     = col("c_step_id",                    step_id)
    val Instrument: ColumnRef             = col("c_instrument",                 instrument)

    val ExposureTime: ColumnRef           = col("c_exposure_time",              time_span)

    object CcdMode {
      val Xbin: ColumnRef                 = col("c_xbin",                       gmos_x_binning)
      val Ybin: ColumnRef                 = col("c_ybin",                       gmos_y_binning)
      val AmpCount: ColumnRef             = col("c_amp_count",                  gmos_amp_count)
      val AmpGain: ColumnRef              = col("c_amp_gain",                   gmos_amp_gain)
      val AmpReadMode: ColumnRef          = col("c_amp_read_mode",              gmos_amp_read_mode)
    }

    val Dtax: ColumnRef                   = col("c_dtax",                       gmos_dtax)
    val Roi: ColumnRef                    = col("c_roi",                        gmos_roi)

    // Grating (if any)

    val GratingDisperser: ColumnRef       = col("c_grating_disperser",          gmos_north_grating.opt)
    val GratingOrder: ColumnRef           = col("c_grating_order",              gmos_grating_order.opt)
    val GratingWavelength: ColumnRef      = col("c_grating_wavelength",         wavelength_pm.opt)

    val Filter: ColumnRef                 = col("c_filter",                     gmos_north_filter.opt)

    // FPU (custom or builtin or none)
    val FpuCustomMaskFilename: ColumnRef  = col("c_fpu_custom_mask_filename",   varchar.opt)
    val FpuCustomMaskSlitWidth: ColumnRef = col("c_fpu_custom_mask_slit_width", gmos_custom_slit_width.opt)
    val FpuBuiltin: ColumnRef             = col("c_fpu_builtin",                gmos_north_fpu.opt)
  }

  object GmosSouthDynamicTable extends TableDef("t_gmos_south_dynamic") {
    val Id: ColumnRef                     = col("c_step_id",                    step_id)
    val Instrument: ColumnRef             = col("c_instrument",                 instrument)

    val ExposureTime: ColumnRef           = col("c_exposure_time",              time_span)

    // CCD Mode
    val Xbin: ColumnRef                   = col("c_xbin",                       gmos_x_binning)
    val Ybin: ColumnRef                   = col("c_ybin",                       gmos_y_binning)
    val AmpCount: ColumnRef               = col("c_amp_count",                  gmos_amp_count)
    val AmpGain: ColumnRef                = col("c_amp_gain",                   gmos_amp_gain)
    val AmpReadMode: ColumnRef            = col("c_amp_read_mode",              gmos_amp_read_mode)

    val Dtax: ColumnRef                   = col("c_dtax",                       gmos_dtax)
    val Roi: ColumnRef                    = col("c_roi",                        gmos_roi)

    // Grating (if any)
    val GratingDisperser: ColumnRef       = col("c_grating_disperser",          gmos_south_grating.opt)
    val GratingOrder: ColumnRef           = col("c_grating_order",              gmos_grating_order.opt)
    val GratingWavelength: ColumnRef      = col("c_grating_wavelength",         wavelength_pm.opt)

    val Filter: ColumnRef                 = col("c_filter",                     gmos_south_filter.opt)

    // FPU (custom or builtin or none)
    val FpuCustomMaskFilename: ColumnRef  = col("c_fpu_custom_mask_filename",   varchar.opt)
    val FpuCustomMaskSlitWidth: ColumnRef = col("c_fpu_custom_mask_slit_width", gmos_custom_slit_width.opt)
    val FpuBuiltin: ColumnRef             = col("c_fpu_builtin",                gmos_south_fpu.opt)
  }

}