// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.sequence
package data
package arb

import cats.data.NonEmptyList
import cats.syntax.either.*
import lucuma.core.enums.CalibrationRole
import lucuma.core.enums.ScienceBand
import lucuma.core.model.Target
import lucuma.core.util.arb.ArbEnumerated
import lucuma.core.util.arb.ArbGid
import lucuma.itc.client.ImagingIntegrationTimeParameters
import lucuma.itc.client.InstrumentMode
import lucuma.itc.client.SpectroscopyIntegrationTimeParameters
import lucuma.itc.client.TargetInput
import lucuma.itc.client.arb.ArbInstrumentMode
import lucuma.itc.client.arb.ArbIntegrationTimeInput
import lucuma.itc.client.arb.ArbTargetInput
import lucuma.odb.sequence.gmos.longslit.Config
import lucuma.odb.sequence.gmos.longslit.arb.ArbGmosLongSlitConfig
import org.scalacheck.*
import org.scalacheck.Arbitrary.arbitrary

trait ArbGeneratorParams:
  import ArbEnumerated.given
  import ArbGid.given
  import ArbGmosLongSlitConfig.given
  import ArbIntegrationTimeInput.given
  import ArbInstrumentMode.given
  import ArbTargetInput.given

  private def genItcInput(mo: InstrumentMode): Gen[ItcInput] =
    for
      im <- arbitrary[ImagingIntegrationTimeParameters]
      sm <- arbitrary[SpectroscopyIntegrationTimeParameters]
      s  <- Gen.choose(1, 4)
      t  <- Gen.listOfN(s, arbitrary[(Target.Id, TargetInput)]).map(NonEmptyList.fromListUnsafe)
    yield ItcInput(im.copy(mode = mo), sm.copy(mode = mo), t)

  val genGmosNorthLongSlit: Gen[GeneratorParams] =
    for
      mo  <- arbitrary[InstrumentMode.GmosNorthSpectroscopy]
      itc <- genItcInput(mo)
      bnd <- arbitrary[Option[ScienceBand]]
      cfg <- arbitrary[Config.GmosNorth]
      rol <- arbitrary[Option[CalibrationRole]]
    yield GeneratorParams(Either.right(itc), bnd, cfg, rol)

  val genGmosSouthLongSlit: Gen[GeneratorParams] =
    for
      mo  <- arbitrary[InstrumentMode.GmosSouthSpectroscopy]
      itc <- genItcInput(mo)
      bnd <- arbitrary[Option[ScienceBand]]
      cfg <- arbitrary[Config.GmosSouth]
      rol <- arbitrary[Option[CalibrationRole]]
    yield GeneratorParams(Either.right(itc), bnd, cfg, rol)

  given Arbitrary[GeneratorParams] =
    Arbitrary:
      Gen.oneOf(
        genGmosNorthLongSlit,
        genGmosSouthLongSlit
      )

object ArbGeneratorParams extends ArbGeneratorParams