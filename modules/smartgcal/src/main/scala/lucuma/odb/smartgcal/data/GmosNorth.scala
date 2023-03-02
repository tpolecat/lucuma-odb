// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.smartgcal.data

import cats.data.NonEmptyList
import fs2.Pipe
import fs2.Stream
import lucuma.core.enums.GmosAmpGain
import lucuma.core.enums.GmosGratingOrder
import lucuma.core.enums.GmosNorthFilter
import lucuma.core.enums.GmosNorthFpu
import lucuma.core.enums.GmosNorthGrating
import lucuma.core.enums.GmosXBinning
import lucuma.core.enums.GmosYBinning
import lucuma.core.math.BoundedInterval
import lucuma.core.math.Wavelength
import lucuma.core.syntax.enumerated.*
import lucuma.core.util.Enumerated

object GmosNorth {

  case class SearchKey(
    grating:    Option[GmosNorthGrating],
    filter:     Option[GmosNorthFilter],
    fpu:        Option[GmosNorthFpu],
    xBin:       GmosXBinning,
    yBin:       GmosYBinning,
    wavelength: Wavelength,
    order:      GmosGratingOrder,
    gain:       GmosAmpGain
  )

  case class TableKey(
    grating:         Option[GmosNorthGrating],
    filter:          Option[GmosNorthFilter],
    fpu:             Option[GmosNorthFpu],
    xBin:            GmosXBinning,
    yBin:            GmosYBinning,
    wavelengthRange: BoundedInterval[Wavelength],
    order:           GmosGratingOrder,
    gain:            GmosAmpGain
  ) {

    def format: String = {
      def quote[A: Enumerated](a: A): String =
        s"'${a.tag}'"

      def quoteOpt[A: Enumerated](a: Option[A]): String =
        a.fold("NULL")(quote)

      s"${quoteOpt(grating)}, ${quoteOpt(filter)}, ${quoteOpt(fpu)}, ${quote(xBin)}, ${quote(yBin)}, $wavelengthRange, ${quote(order)}, ${quote(gain)}"
    }

  }

  case class TableRow(
    key:   TableKey,
    value: SmartGcalValue.Legacy
  )

  case class FileKey(
    gratings:        NonEmptyList[Option[GmosNorthGrating]],
    filters:         NonEmptyList[Option[GmosNorthFilter]],
    fpus:            NonEmptyList[Option[GmosNorthFpu]],
    xBin:            GmosXBinning,
    yBin:            GmosYBinning,
    wavelengthRange: BoundedInterval[Wavelength],
    orders:          NonEmptyList[GmosGratingOrder],
    gains:           NonEmptyList[GmosAmpGain]
  ) {

    def tableKeys: NonEmptyList[TableKey] =
      for {
        g <- gratings
        f <- filters
        u <- fpus
        o <- orders
        n <- gains
      } yield TableKey(g, f, u, xBin, yBin, wavelengthRange, o, n)

  }

  case class FileEntry(
    key:   FileKey,
    value: SmartGcalValue.Legacy
  ) {

    def tableRows: NonEmptyList[TableRow] =
      key.tableKeys.map { tk => TableRow(tk, value) }

  }

  object FileEntry {

    def tableRows[F[_]]: Pipe[F, FileEntry, TableRow] =
      _.flatMap(fe => Stream.emits(fe.tableRows.toList))

  }

}


