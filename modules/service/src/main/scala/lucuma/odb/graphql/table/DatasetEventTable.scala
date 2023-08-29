// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql.table

import lucuma.odb.graphql.BaseMapping
import lucuma.odb.util.Codecs.core_timestamp
import lucuma.odb.util.Codecs.dataset_stage
import lucuma.odb.util.Codecs.execution_event_id
import lucuma.odb.util.Codecs.int2_nonneg
import lucuma.odb.util.Codecs.pos_int
import lucuma.odb.util.Codecs.site
import lucuma.odb.util.Codecs.step_id
import skunk.codec.temporal.date

trait DatasetEventTable[F[_]] extends BaseMapping[F] {

  object DatasetEventTable extends TableDef("t_dataset_event") {
    val Id: ColumnRef           = col("c_execution_event_id", execution_event_id)

    object DatasetId {
      val StepId: ColumnRef     = col("c_step_id",            step_id)
      val Index: ColumnRef      = col("c_index",              int2_nonneg)
    }

    val Received: ColumnRef     = col("c_received",           core_timestamp)
    val DatasetStage: ColumnRef = col("c_dataset_stage",      dataset_stage)

    object DatasetFilename {
      val FileSite: ColumnRef   = col("c_file_site",          site.opt)
      val FileDate: ColumnRef   = col("c_file_date",          date.opt)
      val FileIndex: ColumnRef  = col("c_file_index",         pos_int.opt)
    }
  }

}
