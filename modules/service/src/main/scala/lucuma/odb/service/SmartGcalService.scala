// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.service

import cats.effect.Concurrent
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.numeric.PosLong
import lucuma.core.enums.GcalBaselineType
import lucuma.core.enums.GmosAmpGain
import lucuma.core.enums.GmosGratingOrder
import lucuma.core.enums.GmosNorthFilter
import lucuma.core.enums.GmosNorthFpu
import lucuma.core.enums.GmosNorthGrating
import lucuma.core.enums.GmosSouthFilter
import lucuma.core.enums.GmosSouthFpu
import lucuma.core.enums.GmosSouthGrating
import lucuma.core.enums.GmosXBinning
import lucuma.core.enums.GmosYBinning
import lucuma.core.enums.Instrument
import lucuma.core.enums.SmartGcalType
import lucuma.core.math.BoundedInterval
import lucuma.core.math.Wavelength
import lucuma.core.model.sequence.DynamicConfig.GmosNorth
import lucuma.core.model.sequence.DynamicConfig.GmosSouth
import lucuma.core.model.sequence.ExecutionSequence
import lucuma.core.model.sequence.GmosFpuMask
import lucuma.core.model.sequence.StepConfig.Gcal
import lucuma.core.util.TimeSpan
import lucuma.odb.smartgcal.data.Gmos.SearchKey.{ North => GmosNorthSearchKey}
import lucuma.odb.smartgcal.data.Gmos.SearchKey.{ South => GmosSouthSearchKey}
import lucuma.odb.smartgcal.data.Gmos.TableKey.{ North  => GmosNorthTableKey}
import lucuma.odb.smartgcal.data.Gmos.TableKey.{ South  => GmosSouthTableKey}
import lucuma.odb.smartgcal.data.Gmos.TableRow.{ North  => GmosNorthTableRow}
import lucuma.odb.smartgcal.data.Gmos.TableRow.{ South  => GmosSouthTableRow}
import lucuma.odb.util.Codecs.*
import lucuma.odb.util.GmosCodecs.*
import skunk.*
import skunk.codec.numeric.int4
import skunk.implicits.*

import Services.Syntax.*

trait SmartGcalService[F[_]] {

  /**
   * Selects calibration information corresponding to the given search key and
   * type.  There can be multiple steps per key.
   *
   * @param gn  GMOS North search information
   * @param sgt SmartGcal type of interest
   *
   * @return list of tuples, each corresponding to a calibration step; each
   *         tuple contains an update to the dynamic config to apply (e.g., to
   *         set its exposure time) and the GCAL unit configuration to use
   */
  def selectGmosNorth(
    gn:  GmosNorthSearchKey,
    sgt: SmartGcalType
  ): F[List[(GmosNorth => GmosNorth, Gcal)]]

  /**
   * Selects calibration information corresponding to the given search key and
   * type.  There can be multiple steps per key.
   *
   * @param gn  GMOS South search information
   * @param sgt SmartGcal type of interest
   *
   * @return list of tuples, each corresponding to a calibration step; each
   *         tuple contains an update to the dynamic config to apply (e.g., to
   *         set its exposure time) and the GCAL unit configuration to use
   */
  def selectGmosSouth(
    gn:  GmosSouthSearchKey,
    sgt: SmartGcalType
  ): F[List[(GmosSouth => GmosSouth, Gcal)]]

  // N.B. Insertion is done by a flyway migration and not via this method.  The
  // insert here is intended for initializing a database for testing.
  def insertGmosNorth(
    id:  Int,
    row: GmosNorthTableRow
  ): F[Unit]

  def insertGmosSouth(
    id:  Int,
    row: GmosSouthTableRow
  ): F[Unit]
}

object SmartGcalService {

  def instantiate[F[_] : Concurrent](using Services[F]): SmartGcalService[F] =
    new SmartGcalService[F] {

      override def selectGmosNorth(
        gn:  GmosNorthSearchKey,
        sgt: SmartGcalType
      ): F[List[(GmosNorth => GmosNorth, Gcal)]] =
        selectGmos(Statements.selectGmosNorth(gn, sgt)) { exposureTime =>
          GmosNorth.exposure.replace(exposureTime)
        }

      override def selectGmosSouth(
        gs:  GmosSouthSearchKey,
        sgt: SmartGcalType
      ): F[List[(GmosSouth => GmosSouth, Gcal)]] =
        selectGmos(Statements.selectGmosSouth(gs, sgt)) { exposureTime =>
          GmosSouth.exposure.replace(exposureTime)
        }

      private def selectGmos[D](
        af: AppliedFragment
      )(
        f: TimeSpan => D => D
      ): F[List[(D => D, Gcal)]] =
        session
          .prepareR(af.fragment.query(step_config_gcal ~ pos_int ~ time_span))
          .use(_.stream(af.argument, chunkSize = 16).compile.to(List))
          .map {
            _.flatMap { case ((gcal, count), exposureTime) =>
              List.fill(count.value)(f(exposureTime) -> gcal)
            }
          }

      override def insertGmosNorth(
        id:  Int,
        row: GmosNorthTableRow
      ): F[Unit] =

        val insertInstRow =
          session.executeCommand(
            Statements.InsertGmosNorth(
              Instrument.GmosNorth                         ,
              id                                           ,
              row.line                                     ,
              row.key.gratingConfig.map(_.grating)         ,
              row.key.filter                               ,
              row.key.fpu                                  ,
              row.key.xBin                                 ,
              row.key.yBin                                 ,
              row.key.gratingConfig.map(_.wavelengthRange) ,
              row.key.gratingConfig.map(_.order)           ,
              row.key.gain                                 ,
              row.value.instrumentConfig.exposureTime
            )
          ).void

        val insertGcalRow  =
          session.executeCommand(
            Statements.InsertGcal(Instrument.GmosNorth, id, row.value.gcalConfig, row.value.stepCount, row.value.baselineType)
          ).void

        for {
          _ <- insertGcalRow
          _ <- insertInstRow
        } yield ()

      override def insertGmosSouth(
        id:  Int,
        row: GmosSouthTableRow
      ): F[Unit] =

        val insertInstRow =
          session.executeCommand(
            Statements.InsertGmosSouth(
              Instrument.GmosSouth                         ,
              id                                           ,
              row.line                                     ,
              row.key.gratingConfig.map(_.grating)         ,
              row.key.filter                               ,
              row.key.fpu                                  ,
              row.key.xBin                                 ,
              row.key.yBin                                 ,
              row.key.gratingConfig.map(_.wavelengthRange) ,
              row.key.gratingConfig.map(_.order)           ,
              row.key.gain                                 ,
              row.value.instrumentConfig.exposureTime
            )
          ).void

        val insertGcalRow  =
          session.executeCommand(
            Statements.InsertGcal(Instrument.GmosSouth, id, row.value.gcalConfig, row.value.stepCount, row.value.baselineType)
          ).void

        for {
          _ <- insertGcalRow
          _ <- insertInstRow
        } yield ()
    }


  object Statements {

    private def whereSmartGcalType(sgt: SmartGcalType): AppliedFragment =
      sgt match {
        case SmartGcalType.Arc           => void"g.c_gcal_lamp_type = 'Arc'"
        case SmartGcalType.Flat          => void"g.c_gcal_lamp_type = 'Flat'"
        case SmartGcalType.DayBaseline   => void"g.c_gcal_baseline  = 'Day'"
        case SmartGcalType.NightBaseline => void"g.c_gcal_baseline  = 'Night'"
      }

    private def selectGmos(tableName: String, where: List[AppliedFragment]): AppliedFragment =
      sql"""
        SELECT g.c_gcal_continuum,
               g.c_gcal_ar_arc,
               g.c_gcal_cuar_arc,
               g.c_gcal_thar_arc,
               g.c_gcal_xe_arc,
               g.c_gcal_filter,
               g.c_gcal_diffuser,
               g.c_gcal_shutter,
               g.c_gcal_step_count,
               s.c_exposure_time
          FROM #$tableName s
          JOIN t_gcal      g ON s.c_instrument = g.c_instrument
                            AND s.c_gcal_id    = g.c_gcal_id
         WHERE """(Void) |+| where.intercalate(void" AND ") |+| void" ORDER BY s.c_step_order"


    def selectGmosNorth(
      gn:  GmosNorthSearchKey,
      sgt: SmartGcalType
    ): AppliedFragment = {
      val where = List(
        sql"s.c_disperser       IS NOT DISTINCT FROM ${gmos_north_grating.opt}"(gn.grating.map(_.grating)),
        sql"s.c_filter          IS NOT DISTINCT FROM ${gmos_north_filter.opt}"(gn.filter),
        sql"s.c_fpu             IS NOT DISTINCT FROM ${gmos_north_fpu.opt}"(gn.fpu),
        sql"s.c_disperser_order IS NOT DISTINCT FROM ${gmos_disperser_order.opt}"(gn.grating.map(_.order)),
        sql"s.c_x_binning = ${gmos_x_binning}"(gn.xBin),
        sql"s.c_y_binning = ${gmos_y_binning}"(gn.yBin),
        sql"s.c_amp_gain  = ${gmos_amp_gain}"(gn.gain),
        gn.grating.map(_.wavelength).fold(void"s.c_wavelength_range IS NULL")(
          sql"s.c_wavelength_range @> ${wavelength_pm}"
        ),
        whereSmartGcalType(sgt),
      )

      selectGmos("t_smart_gmos_north", where)
    }

    def selectGmosSouth(
      gs:  GmosSouthSearchKey,
      sgt: SmartGcalType
    ): AppliedFragment = {
      val where = List(
        sql"s.c_disperser       IS NOT DISTINCT FROM ${gmos_south_grating.opt}"(gs.grating.map(_.grating)),
        sql"s.c_filter          IS NOT DISTINCT FROM ${gmos_south_filter.opt}"(gs.filter),
        sql"s.c_fpu             IS NOT DISTINCT FROM ${gmos_south_fpu.opt}"(gs.fpu),
        sql"s.c_disperser_order IS NOT DISTINCT FROM ${gmos_disperser_order.opt}"(gs.grating.map(_.order)),
        sql"s.c_x_binning = ${gmos_x_binning}"(gs.xBin),
        sql"s.c_y_binning = ${gmos_y_binning}"(gs.yBin),
        sql"s.c_amp_gain  = ${gmos_amp_gain}"(gs.gain),
        gs.grating.map(_.wavelength).fold(void"s.c_wavelength_range IS NULL")(
          sql"s.c_wavelength_range @> ${wavelength_pm}"
        ),
        whereSmartGcalType(sgt),
      )

      selectGmos("t_smart_gmos_south", where)
    }

    val InsertGcal: Fragment[(
      Instrument ,
        Int      ,
        Gcal     ,
        PosInt   ,
        GcalBaselineType
    )] =
      sql"""
        INSERT INTO t_gcal (
          c_instrument,
          c_gcal_id,
          c_gcal_continuum,
          c_gcal_ar_arc,
          c_gcal_cuar_arc,
          c_gcal_thar_arc,
          c_gcal_xe_arc,
          c_gcal_filter,
          c_gcal_diffuser,
          c_gcal_shutter,
          c_gcal_step_count,
          c_gcal_baseline
        ) SELECT
          $instrument,
          $int4,
          $step_config_gcal,
          $pos_int,
          $gcal_baseline
      """

    val InsertGmosNorth: Fragment[(
      Instrument                          ,
      Int                                 ,
      PosLong                             ,
      Option[GmosNorthGrating]            ,
      Option[GmosNorthFilter]             ,
      Option[GmosNorthFpu]                ,
      GmosXBinning                        ,
      GmosYBinning                        ,
      Option[BoundedInterval[Wavelength]] ,
      Option[GmosGratingOrder]            ,
      GmosAmpGain                         ,
      TimeSpan
    )] =
      sql"""
        INSERT INTO t_smart_gmos_north (
          c_instrument,
          c_gcal_id,
          c_step_order,
          c_disperser,
          c_filter,
          c_fpu,
          c_x_binning,
          c_y_binning,
          c_wavelength_range,
          c_disperser_order,
          c_amp_gain,
          c_exposure_time
        ) SELECT
          $instrument,
          $int4,
          $pos_long,
          ${gmos_north_grating.opt},
          ${gmos_north_filter.opt},
          ${gmos_north_fpu.opt},
          $gmos_x_binning,
          $gmos_y_binning,
          ${wavelength_pm_range.opt},
          ${gmos_disperser_order.opt},
          $gmos_amp_gain,
          $time_span
      """

    val InsertGmosSouth: Fragment[(
      Instrument                          ,
      Int                                 ,
      PosLong                             ,
      Option[GmosSouthGrating]            ,
      Option[GmosSouthFilter]             ,
      Option[GmosSouthFpu]                ,
      GmosXBinning                        ,
      GmosYBinning                        ,
      Option[BoundedInterval[Wavelength]] ,
      Option[GmosGratingOrder]            ,
      GmosAmpGain                         ,
      TimeSpan
    )] =
      sql"""
        INSERT INTO t_smart_gmos_south (
          c_instrument,
          c_gcal_id,
          c_step_order,
          c_disperser,
          c_filter,
          c_fpu,
          c_x_binning,
          c_y_binning,
          c_wavelength_range,
          c_disperser_order,
          c_amp_gain,
          c_exposure_time
        ) SELECT
          $instrument,
          $int4,
          $pos_long,
          ${gmos_south_grating.opt},
          ${gmos_south_filter.opt},
          ${gmos_south_fpu.opt},
          $gmos_x_binning,
          $gmos_y_binning,
          ${wavelength_pm_range.opt},
          ${gmos_disperser_order.opt},
          $gmos_amp_gain,
          $time_span
      """
  }
}

