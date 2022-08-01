// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql
package snippet

import cats.effect.MonadCancelThrow
import cats.effect.kernel.Resource
import cats.syntax.all._
import edu.gemini.grackle.Cursor.Env
import edu.gemini.grackle.Path.UniquePath
import edu.gemini.grackle.{ListType, Predicate, Query, Result, TypeRef}
import edu.gemini.grackle.Predicate._
import edu.gemini.grackle.Query._
import edu.gemini.grackle.skunk.SkunkMapping
import lucuma.core.enums.CloudExtinction
import lucuma.core.enums.ImageQuality
import lucuma.core.enums.SkyBackground
import lucuma.core.enums.WaterVapor
import lucuma.core.model.Observation
import lucuma.core.model.User
import lucuma.odb.data.Existence
import lucuma.odb.data.ObsActiveStatus
import lucuma.odb.data.ObsStatus
import lucuma.odb.graphql.snippet.input.{CreateObservationInput, ObservationPropertiesInput, UpdateObservationsInput}
import lucuma.odb.graphql.util.Bindings.BooleanBinding
import lucuma.odb.graphql.util._
import lucuma.odb.service.ObservationService
import lucuma.odb.service.ObservationService.CreateObservationResponse.NotAuthorized
import lucuma.odb.service.ObservationService.CreateObservationResponse.Success
import lucuma.odb.util.Codecs._
import skunk.Session

object ObservationSnippet {

  def apply[F[_]: MonadCancelThrow](
    m:      SnippetMapping[F] with SkunkMapping[F] with MutationCompanionOps[F],
    dbPool: Resource[F, Session[F]],
    user:   User
  ): m.Snippet = {

    import m.{ ColumnRef, TableDef, ObjectMapping, Join, Snippet, SqlField, SqlObject, Mutation, MutationCompanionOps, SqlRoot, LeafMapping, col, schema }

    // The types that we're going to map.
    val QueryType           = schema.ref("Query")
    val MutationType        = schema.ref("Mutation")
    val ObservationType     = schema.ref("Observation")
    val ObservationIdType   = schema.ref("ObservationId")
    val ObsStatusType       = schema.ref("ObsStatus")
    val ObsActiveStatusType = schema.ref("ObsActiveStatus")
    val CreateObservationResultType  = schema.ref("CreateObservationResult")
//    val UpdateObservationsResultType = schema.ref("UpdateObservationsResult")

    val ConstraintSetType   = schema.ref("ConstraintSet")
    val CloudExtinctionType = schema.ref("CloudExtinction")
    val ImageQualityType    = schema.ref("ImageQuality")
    val SkyBackgroundType   = schema.ref("SkyBackground")
    val WaterVaporType      = schema.ref("WaterVapor")
    val ElevationRangeType  = schema.ref("ElevationRange")
    val AirMassRangeType    = schema.ref("AirMassRange")
    val HourAngleRangeType  = schema.ref("HourAngleRange")

    val pool = dbPool.map(ObservationService.fromUserAndSession(user, _))

    // TODO: Can we share the common predicates somewhere?
    object Predicates {

      def includeDeleted(b: Boolean): Predicate =
        if (b) True else Eql(UniquePath(List("existence")), Const[Existence](Existence.Present))

      def hasObservationId(oid: Observation.Id): Predicate =
        Eql(UniquePath(List("id")), Const(oid))

      /* TBD
      def isVisibleTo(user: model.User): Predicate =
        user.role.access match {
          case Guest | Pi =>
            Or(
              Contains(ListPath(List("users", "userId")), Const(user.id)), // user is linked, or
              Eql(UniquePath(List("piUserId")), Const(user.id))            // user is the PI
            )
          case Ngo => ???
          case Staff | Admin | Service => True
        }
      */
    }

    // Column references for our mapping.
    object ObservationView extends TableDef("v_observation") {
      val ProgramId: ColumnRef    = col("c_program_id",          program_id)
      val Id: ColumnRef           = col("c_observation_id",      observation_id)
      val Existence: ColumnRef    = col("c_existence",           existence)
      val Name: ColumnRef         = col("c_name",                text_nonempty.opt)
//      val Instrument: m.ColumnRef   = col("c_instrument", tag.opt)
      val Status: ColumnRef       = col("c_status",              obs_status)
      val ActiveStatus: ColumnRef = col("c_active_status",       obs_active_status)
      object ConstraintSet {
        val CloudExtinction: ColumnRef = col("c_cloud_extinction", cloud_extinction.embedded)
        val ImageQuality: ColumnRef    = col("c_image_quality",    image_quality.embedded)
        val SkyBackground: ColumnRef   = col("c_sky_background",   sky_background.embedded)
        val WaterVapor: ColumnRef      = col("c_water_vapor",      water_vapor.embedded)
        object ElevationRange {
          object AirMassRange {
            val SyntheticId: ColumnRef = col("c_air_mass_id",  observation_id.embedded)
            val AirMassMin: ColumnRef  = col("c_air_mass_min", air_mass_range_value.embedded)
            val AirMassMax: ColumnRef  = col("c_air_mass_max", air_mass_range_value.embedded)
          }
          object HourAngleRange {
            val SyntheticId: ColumnRef  = col("c_hour_angle_id",  observation_id.embedded)
            val HourAngleMin: ColumnRef = col("c_hour_angle_min", hour_angle_range_value.embedded)
            val HourAngleMax: ColumnRef = col("c_hour_angle_max", hour_angle_range_value.embedded)
          }
        }
      }
    }

    // Column references for our mapping.
    object ProgramTable extends TableDef("t_program") {
      val Id: ColumnRef = col("c_program_id", program_id)
    }

    def uniqueObservationNoFiltering(id: Observation.Id, child: Query): Result[Query] =
      Result(Unique(Filter(Predicates.hasObservationId(id), child)))

    val createObservation: Mutation =
      Mutation.simple { (child, env) =>
        env.getR[CreateObservationInput]("input").flatTraverse { input =>
          pool.use { svc =>
            svc.createObservation(input.programId, input.SET.getOrElse(ObservationPropertiesInput.DefaultCreate)).map {
              case NotAuthorized(user) => Result.failure(s"User ${user.id} is not authorized to perform this action")
              case Success(id)         => uniqueObservationNoFiltering(id, child)
            }
          }
        }
      }

    val updateObservation: Mutation =
      Mutation.simple { (child, env) =>
        env.getR[UpdateObservationsInput]("input").flatTraverse { input =>
          pool.use { svc =>
            ???
          }
        }
      }

    val typeMappings =
      List(
        ObjectMapping(
          tpe = ObservationType,
          fieldMappings = List(
            SqlField("id", ObservationView.Id, key = true),
            SqlField("programId", ObservationView.ProgramId, hidden=true),
            SqlField("existence", ObservationView.Existence, hidden = true),
            SqlField("subtitle", ObservationView.Name),
            SqlField("status", ObservationView.Status),
            SqlField("activeStatus", ObservationView.ActiveStatus),
            SqlObject("constraintSet"),
            SqlObject("program", Join(ObservationView.ProgramId, ProgramTable.Id))
          ),
        ),
        ObjectMapping(
          tpe = ConstraintSetType,
          fieldMappings = List(
            SqlField("id", ObservationView.Id, key = true, hidden = true),
            SqlField("cloudExtinction", ObservationView.ConstraintSet.CloudExtinction),
            SqlField("imageQuality",    ObservationView.ConstraintSet.ImageQuality),
            SqlField("skyBackground",   ObservationView.ConstraintSet.SkyBackground),
            SqlField("waterVapor",      ObservationView.ConstraintSet.WaterVapor),
            SqlObject("elevationRange")
          )
        ),
        ObjectMapping(
          tpe = ElevationRangeType,
          fieldMappings = List(
            SqlField("id", ObservationView.Id, key = true, hidden = true),
            SqlObject("airMass"),
            SqlObject("hourAngle")
          )
        ),
        ObjectMapping(
          tpe = AirMassRangeType,
          fieldMappings = List(
            SqlField("synthetic_id", ObservationView.ConstraintSet.ElevationRange.AirMassRange.SyntheticId, key = true, hidden = true),
            SqlField("min", ObservationView.ConstraintSet.ElevationRange.AirMassRange.AirMassMin),
            SqlField("max", ObservationView.ConstraintSet.ElevationRange.AirMassRange.AirMassMax)
          )
        ),
        ObjectMapping(
          tpe = HourAngleRangeType,
          fieldMappings = List(
            SqlField("synthetic_id", ObservationView.ConstraintSet.ElevationRange.HourAngleRange.SyntheticId, key = true, hidden = true),
            SqlField("minHours", ObservationView.ConstraintSet.ElevationRange.HourAngleRange.HourAngleMin),
            SqlField("maxHours", ObservationView.ConstraintSet.ElevationRange.HourAngleRange.HourAngleMax)
          )
        ),
        ObjectMapping(
          tpe = MutationType,
          fieldMappings = List(
            SqlRoot("createObservation", mutation = createObservation),
          )
        ),
        ObjectMapping(
          tpe = CreateObservationResultType,
          fieldMappings = List(
            SqlField("id", ObservationView.Id, key = true, hidden = true),
            SqlObject("observation"),
          )
        ),
        ObjectMapping(
          tpe = ListType(ObservationType),
          fieldMappings = List(
            SqlRoot("updateObservation", mutation = updateObservation)
          )
        ),
//        ObjectMapping(
//          tpe = UpdateObservationsResultType,
//          fieldMappings = List(
//            SqlField("matches", )
//          )
//        ),
        ObjectMapping(
          tpe = QueryType,
          fieldMappings = List(
            SqlRoot("observation")
          )
        ),
        LeafMapping[Observation.Id](ObservationIdType),
        LeafMapping[ObsStatus](ObsStatusType),
        LeafMapping[ObsActiveStatus](ObsActiveStatusType),
        LeafMapping[CloudExtinction](CloudExtinctionType),
        LeafMapping[ImageQuality](ImageQualityType),
        LeafMapping[SkyBackground](SkyBackgroundType),
        LeafMapping[WaterVapor](WaterVaporType)
      )

    val elaborator = Map[TypeRef, PartialFunction[Select, Result[Query]]](
      QueryType -> {
        case Select("observation", List(
          ObservationIdBinding("observationId", rOid),
          BooleanBinding("includeDeleted", rIncludeDeleted)
        ), child) =>
          (rOid, rIncludeDeleted).parMapN { (oid, includeDeleted) =>
            Select("observation", Nil,
              Unique(
                Filter(
                  And(
                    Predicates.hasObservationId(oid),
                    Predicates.includeDeleted(includeDeleted)
//                    Predicates.isVisibleTo(user)   // TBD
                  ),
                  child
                )
              )
            )
          }
      },

      MutationType -> {
        case Select("createObservation", List(CreateObservationInput.Binding("input", rInput)), child) =>
          rInput.map { input =>
            Environment(Env("input" -> input), Select("createObservation", Nil, child))
          }

        case Select("updateObservation", List(UpdateObservationsInput.Binding("input", rInput)), child) =>
          rInput.map { input =>
            Environment(Env("input" -> input), Select("updateObservation", Nil, child))
          }
      }
    )

    // Done.
    Snippet(typeMappings, elaborator)

  }


}
