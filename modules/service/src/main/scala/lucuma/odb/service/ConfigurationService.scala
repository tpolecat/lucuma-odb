// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.service

import cats.effect.Concurrent
import cats.syntax.all.*
import grackle.Result
import grackle.ResultT
import io.circe.ACursor
import io.circe.syntax.*
import lucuma.core.enums.GmosNorthGrating
import lucuma.core.math.Coordinates
import lucuma.core.model.Observation
import lucuma.odb.data.Configuration
import lucuma.odb.data.Configuration.Conditions
import lucuma.odb.data.ConfigurationRequest
import lucuma.odb.data.ObservingModeType
import lucuma.odb.data.OdbError
import lucuma.odb.data.OdbErrorExtensions.asFailure
import lucuma.odb.util.Codecs.*
import lucuma.odb.util.GmosCodecs.*
import skunk.Query
import skunk.Transaction
import skunk.syntax.all.*

import Services.Syntax.*
import io.circe.Json

trait ConfigurationService[F[_]] {

  /** Selects all configuration requests that subsume this observation's configuration. */
  def selectRequests(oid: Observation.Id)(using Transaction[F]): F[Result[List[ConfigurationRequest]]]

  /* Inserts (or selects) a `ConfigurationRequest` based on the configuration of `oid`. */
  def canonicalizeRequest(oid: Observation.Id)(using Transaction[F]): F[Result[ConfigurationRequest]]

}

object ConfigurationService {

  extension (hc: ACursor) def downFields(fields: String*): ACursor = 
    fields.foldLeft(hc)(_.downField(_))

  def instantiate[F[_]: Concurrent](using Services[F]): ConfigurationService[F] =
    new ConfigurationService[F] {
      val impl = Impl[F]

      override def selectRequests(oid: Observation.Id)(using Transaction[F]): F[Result[List[ConfigurationRequest]]] =
        impl.selectRequests(oid).value

      override def canonicalizeRequest(oid: Observation.Id)(using Transaction[F]): F[Result[ConfigurationRequest]] =
        impl.canonicalizeRequest(oid).value

    }

  /** An implementation with unwrapped parameters and results in more natural types. */
  private class Impl[F[_]: Concurrent](using Services[F]) {

    def selectConfiguration(oid: Observation.Id)(using Transaction[F]): ResultT[F, Configuration] =
      ResultT:
        selectConfigurations(List(oid)).value.map: result =>
          result.flatMap: map =>
            map.get(oid) match
              case Some(config) => Result(config)          
              case None => OdbError.InvalidConfiguration(Some("Invalid observation or incomplete configuration.")).asFailure
      
    /** Select the configurations for many observations. */
    def selectConfigurations(oids: List[Observation.Id])(using Transaction[F]): ResultT[F, Map[Observation.Id, Configuration]] =
      ResultT:
        services.runGraphQLQuery(Queries.selectConfigurations(oids)).map: r =>
          r.flatMap: json =>
            json.hcursor.downField("observations").downField("matches").as[List[Json]] match
              case Left(error)  => Result.failure(error.getMessage) // Should never happen
              case Right(jsons) =>
                jsons.traverse { json =>
                  val hc = json.hcursor
                  (hc.downField("id").as[Observation.Id], hc.downField("configuration").as[Configuration]).tupled match
                    case Right(pair) => Result(pair)
                    case Left(Configuration.DecodingFailures.NoReferenceCoordinates) => OdbError.InvalidConfiguration(Some("Reference coordinates are not available.")).asFailure
                    case Left(Configuration.DecodingFailures.NoObservingMode) => OdbError.InvalidConfiguration(Some("Observing mode is undefined.")).asFailure
                    case Left(other)  => Result.failure(other.getMessage) // TODO: this probably isn't good enough
                } .map(_.toMap)            

    def selectAllRequestsForProgram(oid: Observation.Id)(using Transaction[F]): ResultT[F, List[ConfigurationRequest]] =
      ResultT:
        services.runGraphQLQuery(Queries.selectAllRequestsForProgram(oid)).map: r =>
          r.flatMap: json =>
            json.hcursor.downFields("observation", "program", "configurationRequests", "matches").as[List[ConfigurationRequest]] match
              case Left(value)  => Result.failure(value.getMessage) // TODO: this probably isn't good enough
              case Right(value) => Result(value)

    def selectRequests(oid: Observation.Id)(using Transaction[F]): ResultT[F, List[ConfigurationRequest]] =
      selectAllRequestsForProgram(oid).flatMap: crs =>
        if crs.isEmpty then Nil.pure[ResultT[F, *]] // in this case we can avoid the call to `selectConfiguration`
        else selectConfiguration(oid).map: cfg =>
          crs.filter(_.configuration.subsumes(cfg))

    def canonicalizeRequest(oid: Observation.Id)(using Transaction[F]): ResultT[F, ConfigurationRequest] = 
      selectConfiguration(oid).flatMap(canonicalizeRequest(oid, _))

    def canonicalizeRequest(oid: Observation.Id, cfg: Configuration)(using Transaction[F]): ResultT[F, ConfigurationRequest] =
      ResultT.liftF:
        session.prepareR(Statements.InsertRequest).use: pq =>
          pq.option(oid, cfg).flatMap:
            case Some(req) => req.pure[F]
            case None      =>
              session.prepareR(Statements.SelectRequest).use: pq =>
                pq.unique(oid, cfg)

  } 

  private object Queries {

    def selectConfigurations(oids: List[Observation.Id]) =
      s"""
        query {
          observations(            
            WHERE: {
              id: {
                IN: ${oids.asJson}
              }
            }
            LIMIT: 1000 # TODO: we need unlimited in this case
          ) {
            matches {
              id
              configuration {
                conditions {
                  imageQuality
                  cloudExtinction
                  skyBackground
                  waterVapor
                }
                referenceCoordinates {
                  ra { 
                    hms 
                  }
                  dec { 
                    dms 
                  }
                }
                observingMode {
                  instrument
                  mode
                  gmosNorthLongSlit {
                    grating
                  }
                  gmosSouthLongSlit {
                    grating
                  }
                }
              }
            }
          }
        }
      """

  
    def selectAllRequestsForProgram(oid: Observation.Id) =
      s"""
        query {
          observation(observationId: "$oid") {
            program {
              configurationRequests {
                matches {
                  id
                  status
                  configuration {
                    conditions {
                      imageQuality
                      cloudExtinction
                      skyBackground
                      waterVapor
                    }
                    referenceCoordinates {
                      ra { 
                        hms 
                      }
                      dec { 
                        dms 
                      }
                    }
                    observingMode {
                      instrument
                      mode
                      gmosNorthLongSlit {
                        grating
                      }
                      gmosSouthLongSlit {
                        grating
                      }
                    }
                  }
                }
              }
            }
          }
        }
      """
  }

  private object Statements {

    // select matching row, if any
    val SelectRequest: Query[(Observation.Id, Configuration), ConfigurationRequest] =
      sql"""
        SELECT
          c_configuration_request_id,
          c_status,
          c_cloud_extinction,
          c_image_quality,
          c_sky_background,
          c_water_vapor,
          c_reference_ra,
          c_reference_dec,
          c_observing_mode_type,
          c_gmos_north_longslit_grating,
          c_gmos_south_longslit_grating
        FROM t_configuration_request
        WHERE (
          c_program_id = (select c_program_id from t_observation where c_observation_id = $observation_id) AND
          c_cloud_extinction = $cloud_extinction AND
          c_image_quality = $image_quality AND
          c_sky_background = $sky_background AND
          c_water_vapor = $water_vapor AND
          c_reference_ra = $right_ascension AND
          c_reference_dec = $declination AND
          c_observing_mode_type = $observing_mode_type AND
          c_gmos_north_longslit_grating is not distinct from ${gmos_north_grating.opt} AND
          c_gmos_south_longslit_grating is not distinct from ${gmos_south_grating.opt}
        ) 
      """.query(
        (
          configuration_request_id *:
          configuration_request_status *:
          cloud_extinction         *:
          image_quality            *:
          sky_background           *:
          water_vapor              *:
          right_ascension          *:
          declination              *:
          observing_mode_type      *:
          gmos_north_grating.opt   *:
          gmos_south_grating.opt
        ).emap:       
          { case 
            id                       *:
            status                   *:
            cloudExtinction          *:
            imageQuality             *:
            skyBackground            *:
            waterVapor               *:
            rightAscension           *:
            declination              *:
            observingModeType        *:
            gmosNorthLongSlitGrating *:
            gmosSouthLongSlitGrating *:
            EmptyTuple =>

              val mode: Either[String, Configuration.ObservingMode] = 
                (observingModeType, gmosNorthLongSlitGrating, gmosSouthLongSlitGrating) match

                  case (ObservingModeType.GmosNorthLongSlit, Some(g), _) => 
                    Right(Configuration.ObservingMode.GmosNorthLongSlit(g))
                  
                  case (ObservingModeType.GmosSouthLongSlit, _, Some(g)) => 
                    Right(Configuration.ObservingMode.GmosSouthLongSlit(g))
                  
                  case _ => Left(s"Malformed observing mode for configuration request $configuration_request_id")

              mode.map: m =>
                ConfigurationRequest(
                  id, 
                  status,
                  Configuration(
                    Conditions(
                      cloudExtinction,
                      imageQuality,
                      skyBackground,
                      waterVapor
                    ),
                    Coordinates(
                      rightAscension,
                      declination
                    ),
                    m
                  )
                )

          }
      ).contramap[(Observation.Id, Configuration)] { (oid, cfg) => 
        oid                                                         *:
        cfg.conditions.cloudExtinction                              *:
        cfg.conditions.imageQuality                                 *:
        cfg.conditions.skyBackground                                *:
        cfg.conditions.waterVapor                                   *:
        cfg.refererenceCoordinates.ra                               *:
        cfg.refererenceCoordinates.dec                              *:
        cfg.observingMode.tpe                                       *:
        cfg.observingMode.gmosNorthLongSlit.map(_.grating) *:
        cfg.observingMode.gmosSouthLongSlit.map(_.grating) *:
        EmptyTuple
      }

    // insert and return row, or return nothing if a matching row exists
    val InsertRequest: Query[(Observation.Id, Configuration), ConfigurationRequest] =
      sql"""
        INSERT INTO t_configuration_request (
          c_program_id,
          c_cloud_extinction,
          c_image_quality,
          c_sky_background,
          c_water_vapor,
          c_reference_ra,
          c_reference_dec,
          c_observing_mode_type,
          c_gmos_north_longslit_grating,
          c_gmos_south_longslit_grating
        ) VALUES (
          (select c_program_id from t_observation where c_observation_id = $observation_id),
          $cloud_extinction,
          $image_quality,
          $sky_background,
          $water_vapor,
          $right_ascension,
          $declination,
          $observing_mode_type,
          ${gmos_north_grating.opt},
          ${gmos_south_grating.opt}
        ) 
        ON CONFLICT DO NOTHING
        RETURNING
          c_configuration_request_id,
          c_status,
          c_cloud_extinction,
          c_image_quality,
          c_sky_background,
          c_water_vapor,
          c_reference_ra,
          c_reference_dec,
          c_observing_mode_type,
          c_gmos_north_longslit_grating,
          c_gmos_south_longslit_grating
      """.query(
        (
          configuration_request_id *:
          configuration_request_status *:
          cloud_extinction         *:
          image_quality            *:
          sky_background           *:
          water_vapor              *:
          right_ascension          *:
          declination              *:
          observing_mode_type      *:
          gmos_north_grating.opt                  *:
          gmos_south_grating.opt
        ).emap:       
          { case 
            id                       *:
            status                   *:
            cloudExtinction          *:
            imageQuality             *:
            skyBackground            *:
            waterVapor               *:
            rightAscension           *:
            declination              *:
            observingModeType        *:
            gmosNorthLongSlitGrating *:
            gmosSouthLongSlitGrating *:
            EmptyTuple =>

              val mode: Either[String, Configuration.ObservingMode] = 
                (observingModeType, gmosNorthLongSlitGrating, gmosSouthLongSlitGrating) match

                  case (ObservingModeType.GmosNorthLongSlit, Some(g), _) => 
                    Right(Configuration.ObservingMode.GmosNorthLongSlit(g))
                  
                  case (ObservingModeType.GmosSouthLongSlit, _, Some(g)) => 
                    Right(Configuration.ObservingMode.GmosSouthLongSlit(g))
                  
                  case _ => Left(s"Malformed observing mode for configuration request $configuration_request_id")

              mode.map: m =>
                ConfigurationRequest(
                  id, 
                  status,
                  Configuration(
                    Conditions(
                      cloudExtinction,
                      imageQuality,
                      skyBackground,
                      waterVapor
                    ),
                    Coordinates(
                      rightAscension,
                      declination
                    ),
                    m
                  )
                )

          }
      ).contramap[(Observation.Id, Configuration)] { (oid, cfg) => 
        oid                                                         *:
        cfg.conditions.cloudExtinction                              *:
        cfg.conditions.imageQuality                                 *:
        cfg.conditions.skyBackground                                *:
        cfg.conditions.waterVapor                                   *:
        cfg.refererenceCoordinates.ra                               *:
        cfg.refererenceCoordinates.dec                              *:
        cfg.observingMode.tpe                                       *:
        cfg.observingMode.gmosNorthLongSlit.map(_.grating) *:
        cfg.observingMode.gmosSouthLongSlit.map(_.grating) *:
        EmptyTuple
      }

  }

}

