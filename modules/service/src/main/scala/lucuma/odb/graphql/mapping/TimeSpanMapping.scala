// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql.mapping

import lucuma.core.optics.Format
import lucuma.core.util.TimeSpan
import lucuma.odb.graphql.table.AllocationTable
import lucuma.odb.graphql.table.ChronConditionsEntryView
import lucuma.odb.graphql.table.GmosDynamicTables
import lucuma.odb.graphql.table.GroupView
import lucuma.odb.graphql.table.ObservationView
import lucuma.odb.graphql.table.ProgramTable
import lucuma.odb.graphql.table.ProposalTable
import lucuma.odb.graphql.table.TimingWindowView

trait TimeSpanMapping[F[_]] extends AllocationTable[F] with GmosDynamicTables[F] with ProgramTable[F] with ProposalTable[F] with ObservationView[F] with GroupView[F] with TimingWindowView[F] with ChronConditionsEntryView[F] {

  lazy val TimeSpanMapping: TypeMapping =
    SwitchMapping(
      TimeSpanType,
      List(
        AllocationType / "duration"                   -> timeSpanMapping(AllocationTable.Duration)(AllocationTable.ProgramId, AllocationTable.Partner),
        ConditionsExpectationType / "timeframe"       -> timeSpanMapping(ChronConditionsEntryView.Intuition.Expectation.Timespan)(ChronConditionsEntryView.Intuition.Expectation.SyntheticId),
        GmosNorthStepRecordType / "instrumentConfig" / "exposure" -> timeSpanMapping(GmosNorthDynamicTable.ExposureTime)(GmosNorthDynamicTable.Id),
        GmosSouthStepRecordType / "instrumentConfig" / "exposure" -> timeSpanMapping(GmosSouthDynamicTable.ExposureTime)(GmosSouthDynamicTable.Id),
        GroupType / "maximumInterval"                 -> timeSpanMapping(GroupView.MaxInterval)(GroupView.MaxIntervalId),
        GroupType / "minimumInterval"                 -> timeSpanMapping(GroupView.MinInterval)(GroupView.MinIntervalId),
        IntensiveType / "totalTime"                   -> timeSpanMapping(ProposalTable.TotalTime)(ProposalTable.ProgramId),
        LargeProgramType / "totalTime"                -> timeSpanMapping(ProposalTable.TotalTime)(ProposalTable.ProgramId),
        TimingWindowEndAfterType / "after"            -> timeSpanMapping(TimingWindowView.End.After)(TimingWindowView.End.SyntheticId),
        TimingWindowRepeatType / "period"             -> timeSpanMapping(TimingWindowView.End.Repeat.Period)(TimingWindowView.End.SyntheticId)
      )
    )

  private def valueAs[A: io.circe.Encoder](name: String)(f: Format[A, TimeSpan]): CursorField[A] =
    FieldRef("value").as(name, f.reverseGet)

  private def timeSpanMapping(data: ColumnRef)(keys: ColumnRef*): ObjectMapping =
    ObjectMapping(
      tpe = TimeSpanType,
      fieldMappings =
        keyFields(keys: _*) ++ List(
        SqlField("value", data, hidden = true),
        valueAs("microseconds")(Format.fromPrism(TimeSpan.FromMicroseconds)),
        valueAs("milliseconds")(TimeSpan.FromMilliseconds),
        valueAs("seconds")(TimeSpan.FromSeconds),
        valueAs("minutes")(TimeSpan.FromMinutes),
        valueAs("hours")(TimeSpan.FromHours),
        valueAs("iso")(TimeSpan.FromString)
      )
    )

  private def keyFields(keys: ColumnRef*): List[FieldMapping] =
    keys.toList.zipWithIndex.map { (col, n) =>
      SqlField(s"key_$n", col, key = true, hidden = true)
    }

}
