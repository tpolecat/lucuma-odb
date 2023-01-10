// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql

package mapping

trait TargetGroupSelectResultMapping[F[_]] extends ResultMapping[F] {
  
  lazy val TargetGroupSelectResultMapping: ObjectMapping =
    topLevelSelectResultMapping(TargetGroupSelectResultType)

}
