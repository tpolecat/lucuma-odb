// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql.mapping

import grackle.skunk.SkunkMapping
import lucuma.odb.graphql.table.TimeAccountingTable
import lucuma.odb.graphql.table.TimeChargeCorrectionTable
import lucuma.odb.graphql.table.UserTable
import lucuma.odb.graphql.table.VisitTable

trait TimeChargeCorrectionMapping[F[_]] extends VisitTable[F]
                                        with UserTable[F]
                                        with TimeAccountingTable[F]
                                        with TimeChargeCorrectionTable[F] {

  lazy val TimeChargeCorrectionMapping: ObjectMapping =
    ObjectMapping(
      tpe = TimeChargeCorrectionType,
      fieldMappings = List(
        SqlField("id", TimeChargeCorrectionTable.Id, key = true, hidden = true),
        SqlField("created",     TimeChargeCorrectionTable.Created),
        SqlField("chargeClass", TimeChargeCorrectionTable.ChargeClass),
        SqlField("op",          TimeChargeCorrectionTable.Op),
        SqlObject("amount"),
        SqlObject("user",       Join(TimeChargeCorrectionTable.UserId, UserTable.UserId)),
        SqlField("comment",     TimeChargeCorrectionTable.Comment)
      )
    )
}
