// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.service

import cats.data.EitherT
import cats.data.NonEmptyList
import cats.effect.Concurrent
import cats.syntax.all.*
import fs2.text.utf8
import io.circe.Encoder
import io.circe.Json
import io.circe.generic.semiauto.*
import io.circe.refined.given
import io.circe.syntax.*
import lucuma.ags
import lucuma.ags.AgsPosition
import lucuma.ags.GuideProbe
import lucuma.ags.GuideStarCandidate
import lucuma.ags.*
import lucuma.catalog.votable.*
import lucuma.core.enums.PortDisposition
import lucuma.core.enums.Site
import lucuma.core.geom.ShapeExpression
import lucuma.core.geom.gmos.probeArm
import lucuma.core.geom.jts.interpreter.given
import lucuma.core.math.Angle
import lucuma.core.math.Coordinates
import lucuma.core.math.Offset
import lucuma.core.math.Wavelength
import lucuma.core.model.ConstraintSet
import lucuma.core.model.ElevationRange
import lucuma.core.model.ElevationRange.AirMass
import lucuma.core.model.ElevationRange.HourAngle
import lucuma.core.model.ObjectTracking
import lucuma.core.model.Observation
import lucuma.core.model.PosAngleConstraint
import lucuma.core.model.Program
import lucuma.core.model.Target
import lucuma.core.model.Target.Sidereal
import lucuma.core.model.User
import lucuma.core.model.sequence.ExecutionDigest
import lucuma.core.model.sequence.Step
import lucuma.core.model.sequence.StepConfig
import lucuma.core.util.TimeSpan
import lucuma.itc.client.ItcClient
import lucuma.odb.data.Md5Hash
import lucuma.odb.data.PosAngleConstraintMode
import lucuma.odb.json.all.query.given
import lucuma.odb.json.target
import lucuma.odb.logic.Generator
import lucuma.odb.logic.PlannedTimeCalculator
import lucuma.odb.sequence.data.GeneratorParams
import lucuma.odb.sequence.util.CommitHash
import lucuma.odb.util.Codecs.*
import org.http4s.Header
import org.http4s.Headers
import org.http4s.Method
import org.http4s.Request
import org.http4s.client.Client
import skunk.AppliedFragment
import skunk.Decoder
import skunk.implicits.*

import java.time.Instant
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneOffset
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit

import Services.Syntax.*

trait GuideEnvironmentService[F[_]] {
  import GuideEnvironmentService.Error
  import GuideEnvironmentService.GuideEnvironment

  def get(pid: Program.Id, oid: Observation.Id, obsTime: Instant)(using
    NoTransaction[F]
  ): F[Either[Error, List[GuideEnvironment]]]
}

object GuideEnvironmentService {

  case class GuideTarget(probe: GuideProbe, target: Target)

  object GuideTarget {
    given Encoder[GuideTarget] =
      Encoder.instance { gt =>
        Json.obj(
          "probe"         -> gt.probe.asJson,
          "name"          -> gt.target.name.asJson,
          "sourceProfile" -> gt.target.sourceProfile.asJson,
          target.query.siderealOrNonJson(gt.target)
        )
      }
  }

  case class GuideEnvironment(posAngle: Angle, guideTargets: List[GuideTarget])

  object GuideEnvironment {
    given Encoder[GuideEnvironment] = deriveEncoder
  }

  sealed trait Error {
    def format: String
  }

  object Error {
    case class GeneralError(error: String) extends Error {
      val format: String = error
    }

    case class GeneratorError(
      error: Generator.Error
    ) extends Error {
      def format: String = error.format
    }

    case class GaiaError(error: String) extends Error {
      val format: String = s"Error calling Gaia: '$error'"
    }
  }

  private case class ObservationInfo(
    id:                 Observation.Id,
    constraints:        ConstraintSet,
    posAngleConstraint: PosAngleConstraint,
    optWavelength:      Option[Wavelength],
    explicitBase:       Option[Coordinates]
  ) {
    def wavelength: Either[Error, Wavelength] =
      optWavelength.toRight(Error.GeneralError(s"No wavelength defined for observation $id."))
  }

  private case class GeneratorInfo(
    digest: ExecutionDigest,
    params: GeneratorParams,
    hash:        Md5Hash
  ) {
      val plannedTime = digest.fullPlannedTime.sum
      val offsets = NonEmptyList.fromFoldable(digest.science.offsets.union(digest.acquisition.offsets))
      val (site, agsParams): (Site, AgsParams) = params match
        case GeneratorParams.GmosNorthLongSlit(_, mode) => 
          (Site.GN, AgsParams.GmosAgsParams(mode.fpu.asLeft.some, PortDisposition.Side))
        case GeneratorParams.GmosSouthLongSlit(_, mode) => //(Site.GS, mode.fpu.asRight)
          (Site.GS, AgsParams.GmosAgsParams(mode.fpu.asRight.some, PortDisposition.Side))

  }

  def instantiate[F[_]: Concurrent](
    httpClient:            Client[F],
    itcClient:             ItcClient[F],
    commitHash:            CommitHash,
    plannedTimeCalculator: PlannedTimeCalculator.ForInstrumentMode
  )(using Services[F]): GuideEnvironmentService[F] =
    new GuideEnvironmentService[F] {

      def getAsterism(pid: Program.Id, oid: Observation.Id)(using
        NoTransaction[F]
      ): F[Either[Error, NonEmptyList[Target]]] = {
        asterismService.getAsterism(pid, oid)
          .map(l => 
            NonEmptyList.fromList(
              l.map(_._2)
            ).toRight(Error.GeneralError(s"No targets have been defined for observation $oid."))
          )
      }

      def getObservationInfo(pid: Program.Id, oid: Observation.Id)(using
        NoTransaction[F]
      ): F[Either[Error, ObservationInfo]] = {
        val af = Statements.getObservationInfo(user, pid, oid)
        session
          .prepareR(
            af.fragment.query(Decoders.obsInfoDecoder)
          )
          .use(
            _.option(af.argument).map(_.toRight(Error.GeneralError(s"Observation $oid not found.")))
          )
      }

      def getGaiaQuery(
        oid:             Observation.Id,
        obsTime:         Instant,
        tracking:        ObjectTracking,
        shapeConstraint: ShapeExpression
      ): Either[Error, ADQLQuery] = {
        // We want the query dates to be in discreet units to make them more cachable, but
        // we always want at least six months in the future for the scheduler. So, we'll use one year 
        // starting at either January or July 1st.
        val ldt   = LocalDateTime.ofInstant(obsTime, ZoneOffset.UTC)
        val firstOfYear = ldt.`with`(ChronoField.DAY_OF_YEAR, 1L).`with`(ChronoField.NANO_OF_DAY, 0)
        val start =
          if (ldt.getMonthValue() < Month.JULY.getValue())
            firstOfYear
          else firstOfYear.`with`(ChronoField.MONTH_OF_YEAR, Month.JULY.getValue())
        val end   = start.plus(1, ChronoUnit.YEARS)

        (tracking.at(start.toInstant(ZoneOffset.UTC)), tracking.at(end.toInstant(ZoneOffset.UTC)))
          .mapN { (a, b) =>
            // Make a query based on two coordinates of the base of an asterism over a year
            CoordinatesRangeQueryByADQL(
              NonEmptyList.of(a.value, b.value),
              shapeConstraint,
              ags.widestConstraints.some
            )
          }
          .toRight(
            Error.GeneralError(
              s"Unable to get tracking information for asterism for observation $oid."
            )
          )
      }

      def callGaia(
        oid:     Observation.Id,
        obsTime: Instant,
        query:   ADQLQuery
      ): F[Either[Error, NonEmptyList[GuideStarCandidate]]] = {
        val MaxTargets                     = 100
        given catalog: CatalogAdapter.Gaia = CatalogAdapter.Gaia3Lite
        given ci: ADQLInterpreter          = ADQLInterpreter.nTarget(MaxTargets)
        val request                        = Request[F](Method.GET,
                                 CatalogSearch.gaiaSearchUri(query),
                                 headers = Headers(("x-requested-with", "XMLHttpRequest"))
        )
        httpClient
          .stream(request)
          .flatMap(
            _.body
              .through(utf8.decode)
              .through(CatalogSearch.guideStars[F](CatalogAdapter.Gaia3Lite))
          )
          .compile
          .toList
          .map(_.collect { case Right(s) => GuideStarCandidate.siderealTarget.get(s).at(obsTime) })
          .map(
            NonEmptyList
              .fromList(_)
              .toRight(
                Error
                  .GaiaError(s"No valid guide star candidates were returned for observation $oid.")
              )
          )
          .handleError(e => Error.GaiaError(e.getMessage()).asLeft)
      }

      // TODO: use caching
      def getCandidates(
        oid:      Observation.Id,
        obsTime:  Instant,
        tracking: ObjectTracking
      ): F[Either[Error, NonEmptyList[GuideStarCandidate]]] =
        (for {
          query      <- EitherT.fromEither(getGaiaQuery(oid, obsTime, tracking, probeArm.candidatesArea))
          candidates <- EitherT(callGaia(oid, obsTime, query))
        } yield candidates).value

      extension [D](steps: NonEmptyList[Step[D]])
        def offsets: List[Offset] = steps.collect { case Step(_, _, StepConfig.Science(offset, _), _, _, _) =>
          offset
        }

      extension (usable: NonEmptyList[AgsAnalysis.Usable])
        def toGuideEnvironments: NonEmptyList[GuideEnvironment] = usable.map { ags =>
          val target = GuideStarCandidate.siderealTarget.reverseGet(ags.target)
          GuideEnvironment(ags.vignetting.head._1, List(GuideTarget(ags.guideProbe, target)))
        }

      def getGeneratorInfo(
        pid: Program.Id,
        oid: Observation.Id
      ): F[Either[Error, GeneratorInfo]] =
        generator(commitHash, itcClient, plannedTimeCalculator)
          .digestWithParamsAndHash(pid, oid)
          .map {
            _.leftMap(Error.GeneratorError(_))
              .map { (d, p, h) => GeneratorInfo(d, p, h) }
          }

      def getPositions(
        oid:                Observation.Id,
        site:               Site,
        posAngleConstraint: PosAngleConstraint,
        offsets:            Option[NonEmptyList[Offset]],
        tracking:           ObjectTracking,
        obsTime:            Instant,
        obsDuration:        TimeSpan,
      ): Either[Error, NonEmptyList[AgsPosition]] = {
        val angles     =
          posAngleConstraint.anglesToTestAt(site, tracking, obsTime, obsDuration.toDuration).map(_.sorted(Angle.AngleOrder))
        val newOffsets = offsets.getOrElse(NonEmptyList.of(Offset.Zero))

        angles
          .map(toTest =>
            for {
              pa  <- toTest
              off <- newOffsets
            } yield AgsPosition(pa, off)
          )
          .toRight(Error.GeneralError(s"No angles to test for guide target candidates for observation $oid."))
      }

      def processCandidates(
        obsInfo:       ObservationInfo,
        genInfo:       GeneratorInfo,
        baseCoords:    Coordinates,
        scienceCoords: List[Coordinates],
        positions:     NonEmptyList[AgsPosition],
        candidates:    NonEmptyList[GuideStarCandidate]
      ): Either[Error, NonEmptyList[AgsAnalysis.Usable]] =
        obsInfo.wavelength.flatMap { wavelength =>
          val analysis = Ags
            .agsAnalysis(obsInfo.constraints,
                         wavelength,
                         baseCoords,
                         scienceCoords,
                         positions,
                         genInfo.agsParams,
                         candidates.toList
            )
            .sortUsablePositions
            .collect { case usable: AgsAnalysis.Usable => usable }
          NonEmptyList
            .fromList(analysis)
            .toRight(Error.GeneralError(s"No usable guide targets were found for observation ${obsInfo.id}"))
        }
        
      override def get(pid: Program.Id, oid: Observation.Id, obsTime: Instant)(using
        NoTransaction[F]
      ): F[Either[Error, List[GuideEnvironment]]] =
        (for {
          obsInfo        <- EitherT(getObservationInfo(pid, oid))
          asterism       <- EitherT(getAsterism(pid, oid))
          genInfo        <- EitherT(getGeneratorInfo(pid, oid))
          tracking        = ObjectTracking.fromAsterism(asterism)
          candidates     <- EitherT(getCandidates(oid, obsTime, tracking))
          baseCoords     <- EitherT.fromEither(
                              obsInfo.explicitBase
                                .orElse(
                                  tracking
                                    .at(obsTime)
                                    .map(_.value)
                                )
                                .toRight(
                                  Error.GeneralError(
                                    s"Unable to get coordinates for asterism in observation $oid"
                                  )
                                )
                            )
          scienceCoords  <- EitherT.fromEither(
                              asterism.toList
                                .traverse(t => ObjectTracking.fromTarget(t).at(obsTime).map(_.value))
                                .toRight(
                                  Error.GeneralError(
                                    s"Unable to get coordinates for science targets in observation $oid"
                                  )
                                )
                            )
          positions      <- EitherT.fromEither(
                              getPositions(oid, genInfo.site, obsInfo.posAngleConstraint, genInfo.offsets, tracking, obsTime, genInfo.plannedTime)
                            )
          usable         <- EitherT.fromEither(
                              processCandidates(obsInfo, genInfo, baseCoords, scienceCoords, positions, candidates)
                            )
        } yield usable.toGuideEnvironments.toList).value
    }

  object Statements {
    import ProgramService.Statements.andWhereUserAccess

    def getObservationInfo(user: User, pid: Program.Id, oid: Observation.Id): AppliedFragment =
      sql"""
        select
          obs.c_observation_id,
          obs.c_cloud_extinction,
          obs.c_image_quality,
          obs.c_sky_background,
          obs.c_water_vapor,
          obs.c_air_mass_min,
          obs.c_air_mass_max,
          obs.c_hour_angle_min,
          obs.c_hour_angle_max,
          obs.c_pac_mode,
          obs.c_pac_angle,
          obs.c_spec_wavelength,
          obs.c_explicit_ra,
          obs.c_explicit_dec
        from t_observation obs
        where obs.c_program_id = $program_id
          and obs.c_observation_id = $observation_id
      """.apply(pid, oid) |+| andWhereUserAccess(user, pid)
  }

  private object Decoders {

    val obsInfoDecoder: Decoder[ObservationInfo] =
      (observation_id *:
        cloud_extinction *:
        image_quality *:
        sky_background *:
        water_vapor *:
        air_mass_range_value.opt *:
        air_mass_range_value.opt *:
        hour_angle_range_value.opt *:
        hour_angle_range_value.opt *:
        pac_mode *:
        angle_µas *:
        wavelength_pm.opt *:
        right_ascension.opt *:
        declination.opt).emap {
        case (id, cloud, image, sky, water, amMin, amMax, haMin, haMax, mode, angle, wavelength, ra, dec) =>
          val paConstraint: PosAngleConstraint = mode match
            case PosAngleConstraintMode.Unbounded           => PosAngleConstraint.Unbounded
            case PosAngleConstraintMode.Fixed               => PosAngleConstraint.Fixed(angle)
            case PosAngleConstraintMode.AllowFlip           => PosAngleConstraint.AllowFlip(angle)
            case PosAngleConstraintMode.AverageParallactic  => PosAngleConstraint.AverageParallactic
            case PosAngleConstraintMode.ParallacticOverride =>
              PosAngleConstraint.ParallacticOverride(angle)

          val elevRange =
            (for {
              min  <- amMin
              max  <- amMax
              aMin <- AirMass.DecimalValue.from(min.value).toOption
              aMax <- AirMass.DecimalValue.from(max.value).toOption
              am   <- AirMass.fromOrderedDecimalValues.getOption((aMin, aMax))
            } yield am)
              .orElse(
                for {
                  min  <- haMin
                  max  <- haMax
                  hMin <- HourAngle.DecimalHour.from(min).toOption
                  hMax <- HourAngle.DecimalHour.from(max).toOption
                  ha   <- HourAngle.fromOrderedDecimalHours.getOption((hMin, hMax))
                } yield ha
              )
              .toRight(s"Invalid elevation range in observation $id.")

          val explicitBase: Option[Coordinates] =
            (ra, dec).mapN(Coordinates(_, _))

          elevRange.map(elev =>
            ObservationInfo(id,
                            ConstraintSet(image, cloud, sky, water, elev),
                            paConstraint,
                            wavelength,
                            explicitBase
            )
          )
      }
  }
}
