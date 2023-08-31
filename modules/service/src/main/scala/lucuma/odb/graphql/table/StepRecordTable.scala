// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

 package lucuma.odb.graphql.table

import lucuma.odb.graphql.BaseMapping
import lucuma.odb.util.Codecs.angle_µas
import lucuma.odb.util.Codecs.atom_id
import lucuma.odb.util.Codecs.core_timestamp
import lucuma.odb.util.Codecs.gcal_continuum
import lucuma.odb.util.Codecs.gcal_diffuser
import lucuma.odb.util.Codecs.gcal_filter
import lucuma.odb.util.Codecs.gcal_shutter
import lucuma.odb.util.Codecs.guide_state
import lucuma.odb.util.Codecs.instrument
import lucuma.odb.util.Codecs.observation_id
import lucuma.odb.util.Codecs.smart_gcal_type
import lucuma.odb.util.Codecs.step_id
import lucuma.odb.util.Codecs.step_type
import skunk.codec.boolean.bool

trait StepRecordTable[F[_]] extends BaseMapping[F] {

  object StepRecordTable extends TableDef("v_step_record") {
    val Id: ColumnRef            = col("c_step_id",        step_id)
    val ObservationId: ColumnRef = col("c_observation_id", observation_id)
    val Instrument: ColumnRef    = col("c_instrument",     instrument)
    val AtomId: ColumnRef        = col("c_atom_id",        atom_id)
    val StepType: ColumnRef      = col("c_step_type",      step_type)
    val Created: ColumnRef       = col("c_created",        core_timestamp)

    object Gcal {
      val Continuum: ColumnRef = col("c_gcal_continuum", gcal_continuum)
      val ArArc: ColumnRef     = col("c_gcal_ar_arc",    bool)
      val CuarArc: ColumnRef   = col("c_gcal_cuar_arc",  bool)
      val TharArc: ColumnRef   = col("c_gcal_thar_arc",  bool)
      val XeArc: ColumnRef     = col("c_gcal_xe_arc",    bool)

      val Filter: ColumnRef    = col("c_gcal_filter",    gcal_filter)
      val Diffuser: ColumnRef  = col("c_gcal_diffuser",  gcal_diffuser)
      val Shutter: ColumnRef   = col("c_gcal_shutter",   gcal_shutter)
    }

    object Science {
      val OffsetP: ColumnRef    = col("c_offset_p",    angle_µas)
      val OffsetQ: ColumnRef    = col("c_offset_q",    angle_µas)
      val GuideState: ColumnRef = col("c_guide_state", guide_state)
    }

    object SmartGcal {
      val Type: ColumnRef = col("c_smart_gcal_type", smart_gcal_type)
    }

  }

}
