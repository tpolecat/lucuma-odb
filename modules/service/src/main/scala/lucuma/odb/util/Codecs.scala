// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.util

// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

import eu.timepit.refined.types.numeric.PosBigDecimal
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import lucuma.core.enums.CatalogName
import lucuma.core.enums.CloudExtinction
import lucuma.core.enums.EphemerisKeyType
import lucuma.core.enums.FocalPlane
import lucuma.core.enums.ImageQuality
import lucuma.core.enums.Instrument
import lucuma.core.enums.ObsActiveStatus
import lucuma.core.enums.ObsStatus
import lucuma.core.enums.ScienceMode
import lucuma.core.enums.Site
import lucuma.core.enums.SkyBackground
import lucuma.core.enums.SpectroscopyCapabilities
import lucuma.core.enums.ToOActivation
import lucuma.core.enums.WaterVapor
import lucuma.core.math.Angle
import lucuma.core.math.Declination
import lucuma.core.math.Epoch
import lucuma.core.math.Parallax
import lucuma.core.math.RadialVelocity
import lucuma.core.math.RightAscension
import lucuma.core.math.Wavelength
import lucuma.core.model.ElevationRange.AirMass
import lucuma.core.model.ElevationRange.HourAngle
import lucuma.core.model.*
import lucuma.core.util.Enumerated
import lucuma.core.util.Gid
import lucuma.core.util.TimeSpan
import lucuma.odb.data.EditType
import lucuma.odb.data.Existence
import lucuma.odb.data.ObservingModeType
import lucuma.odb.data.PosAngleConstraintMode
import lucuma.odb.data.ProgramUserRole
import lucuma.odb.data.ProgramUserSupportType
import lucuma.odb.data.Tag
import lucuma.odb.data.Timestamp
import lucuma.odb.data.UserType
import skunk.*
import skunk.codec.all.*
import skunk.data.Arr
import skunk.data.Type

// Codecs for some atomic types.
trait Codecs {

  def enumerated[A](tpe: Type)(implicit ev: Enumerated[A]): Codec[A] =
    `enum`(ev.tag, ev.fromTag, tpe)

  def gid[A](implicit ev: Gid[A]): Codec[A] = {
    val prism = ev.fromString
    Codec.simple(
      prism.reverseGet,
      s => prism.getOption(s).toRight(s"Invalid: $s"),
      Type.varchar
    )
  }

  // -----

  val air_mass_range_value: Codec[PosBigDecimal] =
    numeric(3, 2).eimap(PosBigDecimal.from)(_.value)

  val air_mass_range: Codec[AirMass] =
    (air_mass_range_value ~ air_mass_range_value).eimap { case (min, max) =>
      for {
        n <- AirMass.DecimalValue.from(min.value)
        x <- AirMass.DecimalValue.from(max.value)
        a <- AirMass.fromOrderedDecimalValues.getOption((n, x)).toRight(s"air mass min and max out of order: ($min, $max)")
      } yield a
    } { a =>
      (PosBigDecimal.unsafeFrom(a.min.value), PosBigDecimal.unsafeFrom(a.max.value))
    }

  val angle_µas: Codec[Angle] =
    int8.imap(Angle.microarcseconds.reverseGet)(Angle.microarcseconds.get)

  val attachment_id: Codec[Attachment.Id] =
    gid[Attachment.Id]

  val catalog_name: Codec[CatalogName] =
    enumerated(Type("e_catalog_name"))

  val cloud_extinction: Codec[CloudExtinction] =
    enumerated[CloudExtinction](Type.varchar)

  val data_timestamp: Codec[Timestamp] =
    timestamp.eimap(
      ldt => Timestamp.FromLocalDateTime.getOption(ldt).toRight(s"Invalid Timestamp: $ldt"))(
      _.toLocalDateTime
    )

  val declination: Codec[Declination] =
    angle_µas.eimap(
      a => Declination.fromAngle.getOption(a).toRight(s"Invalid declination: $a"))(
      Declination.fromAngle.reverseGet
    )

  val edit_type: Codec[EditType] =
    enumerated(Type("e_edit_type"))

  val ephemeris_key_type: Codec[EphemerisKeyType] =
    enumerated(Type("e_ephemeris_key_type"))

  val epoch: Codec[Epoch] =
    varchar.eimap(
      s => Epoch.fromString.getOption(s).toRight(s"Invalid epoch: $s"))(
      Epoch.fromString.reverseGet
    )

  val existence: Codec[Existence] =
    enumerated(Type("e_existence"))

  val focal_plane: Codec[FocalPlane] =
    enumerated[FocalPlane](Type.varchar)

  val hour_angle_range_value: Codec[BigDecimal] =
    numeric(3, 2)

  val hour_angle_range: Codec[HourAngle] =
    (hour_angle_range_value ~ hour_angle_range_value).eimap { case (min, max) =>
      for {
        n <- HourAngle.DecimalHour.from(min)
        x <- HourAngle.DecimalHour.from(max)
        h <- HourAngle.fromOrderedDecimalHours.getOption((n, x)).toRight(s"hour angle min and max out of order: ($min, $max)")
      } yield h
    } { h =>
      (h.minHours.value, h.maxHours.value)
    }

  val image_quality: Codec[ImageQuality] =
    enumerated[ImageQuality](Type.varchar)
    
  val instrument: Codec[Instrument] =
    enumerated[Instrument](Type.varchar)  

  val int_percent: Codec[IntPercent] =
    int2.eimap(n => IntPercent.from(n))(_.value.toShort)

  val obs_active_status: Codec[ObsActiveStatus] =
    enumerated(Type("e_obs_active_status"))

  val obs_status: Codec[ObsStatus] =
    enumerated(Type("e_obs_status"))

  val observation_id: Codec[Observation.Id] =
    gid[Observation.Id]
  
  val observing_mode_type: Codec[ObservingModeType] =
    enumerated(Type("e_observing_mode_type"))

  val orcid_id: Codec[OrcidId] =
    Codec.simple[OrcidId](
      _.value.toString(),
      OrcidId.fromValue(_),
      Type.varchar
    )

  val pac_mode: Codec[PosAngleConstraintMode] =
    enumerated(Type("e_pac_mode"))

  val parallax: Codec[Parallax] =
    angle_µas.imap(
      a => Parallax.fromMicroarcseconds(a.toMicroarcseconds))(
      p => Angle.fromMicroarcseconds(p.μas.value.value)
    )

  val partner: Codec[Partner] =
    enumerated(Type.varchar)

  val pos_big_decimal: Codec[PosBigDecimal] =
    numeric.eimap(PosBigDecimal.from)(_.value)

  val pos_int: Codec[PosInt] =
    int4.eimap(PosInt.from)(_.value)

  val program_id: Codec[Program.Id] =
    gid[Program.Id]

  val program_user_role: Codec[ProgramUserRole] =
    enumerated(Type("e_program_user_role"))

  val program_user_support_type: Codec[ProgramUserSupportType] =
    enumerated(Type("e_program_user_support_type"))

  val radial_velocity: Codec[RadialVelocity] =
    numeric.eimap(
      bd => RadialVelocity.kilometerspersecond.getOption(bd).toRight(s"Invalid radial velocity: $bd"))(
      RadialVelocity.kilometerspersecond.reverseGet
    )

  val right_ascension: Codec[RightAscension] =
    angle_µas.eimap(
      a => RightAscension.fromAngleExact.getOption(a).toRight(s"Invalid right ascension: $a"))(
      RightAscension.fromAngleExact.reverseGet
    )

  val science_mode: Codec[ScienceMode] =
    enumerated[ScienceMode](Type.varchar)

  val _site: Codec[Arr[Site]] =
    Codec.array(_.tag.toLowerCase, s => Site.fromTag(s.toUpperCase).toRight(s"Invalid tag: $s"), Type("_e_site"))

  val site: Codec[Site] =
    `enum`(_.tag.toLowerCase, s => Site.fromTag(s.toUpperCase), Type("e_site"))

  val sky_background: Codec[SkyBackground] =
    enumerated[SkyBackground](Type.varchar)

  val spectroscopy_capabilities: Codec[SpectroscopyCapabilities] =
    enumerated[SpectroscopyCapabilities](Type.varchar)

  val signal_to_noise: Codec[PosBigDecimal] =
    numeric(5, 2).eimap(PosBigDecimal.from)(_.value)

  val tag: Codec[Tag] =
    varchar.imap(Tag(_))(_.value)

  val target_id: Codec[Target.Id] =
    gid[Target.Id]

  val text_nonempty: Codec[NonEmptyString] =
    text.eimap(NonEmptyString.from)(_.value)

  val time_span: Codec[TimeSpan] =
    interval.eimap(
      µs => TimeSpan.FromDuration.getOption(µs).toRight(s"Invalid TimeSpan, must be non-negative µs <  9,223,372,036,854,775,807 µs: $µs"))(
      TimeSpan.FromDuration.reverseGet
    )

  val too_activation: Codec[ToOActivation] =
    enumerated(Type("e_too_activation"))

  val user_id: Codec[User.Id] =
    gid[User.Id]

  val user_type: Codec[UserType] =
    enumerated(Type("e_user_type"))

  val water_vapor: Codec[WaterVapor] =
    enumerated[WaterVapor](Type.varchar)

  val wavelength_pm: Codec[Wavelength] =
    int4.eimap(
      pm => Wavelength.intPicometers.getOption(pm).toRight(s"Invalid wavelength, must be positive pm: $pm"))(
      Wavelength.intPicometers.reverseGet
    )

  // Not so atomic ...

  val elevation_range: Codec[ElevationRange] =
    (air_mass_range.opt ~ hour_angle_range.opt).eimap { case (am, ha) =>
      am.orElse(ha).toRight("Undefined elevation range")
    } { e =>
      (ElevationRange.airMass.getOption(e), ElevationRange.hourAngle.getOption(e))
    }

  val constraint_set: Codec[ConstraintSet] =
    (image_quality    ~
     cloud_extinction ~
     sky_background   ~
     water_vapor      ~
     elevation_range
    ).gimap[ConstraintSet]

}

object Codecs extends Codecs
