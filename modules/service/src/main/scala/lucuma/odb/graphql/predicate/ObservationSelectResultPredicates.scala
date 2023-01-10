// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql.predicate

import edu.gemini.grackle.Path

case class ObservationSelectResultPredicates(path: Path) {
  val hasMore = LeafPredicates[Boolean](path / "hasMore")
  val matches = ObservationPredicates(path / "matches")
}