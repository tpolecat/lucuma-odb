// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql.predicate

import edu.gemini.grackle.Path
import lucuma.core.model.Observation

class ObservationPredicates(path: Path) {
  lazy val existence = ExistencePredicates(path / "existence")
  lazy val id        = LeafPredicates[Observation.Id](path / "id")
  lazy val program   = new ProgramPredicates(path / "program")
}
