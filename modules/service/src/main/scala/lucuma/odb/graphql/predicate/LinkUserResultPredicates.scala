// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql.predicate

import edu.gemini.grackle.Path
import lucuma.core.model.Program
import lucuma.core.model.User

class LinkUserResultPredicates(path: Path) {
  val programId = LeafPredicates[Program.Id](path / "programId")
  val userId = LeafPredicates[User.Id](path / "userId")
}