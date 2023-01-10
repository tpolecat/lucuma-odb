// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.data

import lucuma.core.enums.Instrument
import lucuma.core.util.Enumerated

enum ObservingModeType(val dbTag: String, val instrument: Instrument):
  case GmosNorthLongSlit extends ObservingModeType("gmos_north_long_slit", Instrument.GmosNorth)
  case GmosSouthLongSlit extends ObservingModeType("gmos_south_long_slit", Instrument.GmosSouth)

object ObservingModeType:
  
  given Enumerated[ObservingModeType] =
    Enumerated.from(
      GmosNorthLongSlit,
      GmosSouthLongSlit
    ).withTag(_.dbTag)
