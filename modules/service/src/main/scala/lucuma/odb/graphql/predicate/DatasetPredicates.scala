// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql
package predicate

import edu.gemini.grackle.Path
import lucuma.core.model.sequence.Dataset

class DatasetPredicates(path: Path) {
  lazy val id          = LeafPredicates[Dataset.Id](path / "id")
  lazy val observation = new ObservationPredicates(path / "observation")
}
