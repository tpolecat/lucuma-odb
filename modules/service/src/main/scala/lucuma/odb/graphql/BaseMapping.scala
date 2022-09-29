// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql

import edu.gemini.grackle.Schema
import edu.gemini.grackle.skunk.SkunkMapping
import lucuma.odb.graphql.util.SchemaSemigroup
import lucuma.odb.graphql.util.MutationCompanionOps
import lucuma.odb.graphql.util.MappingExtras

trait BaseMapping[F[_]]
  extends SkunkMapping[F]
     with SchemaSemigroup[F]
     with MutationCompanionOps[F]
     with MappingExtras[F] {

  // TODO: auto-generate this
  lazy val AirMassRangeType            = schema.ref("AirMassRange")
  lazy val AllocationType              = schema.ref("Allocation")
  lazy val AngleType                   = schema.ref("Angle")
  lazy val BigDecimalType              = schema.ref("BigDecimal")
  lazy val CatalogInfoType             = schema.ref("CatalogInfo")
  lazy val ClassicalType               = schema.ref("Classical")
  lazy val CloudExtinctionType         = schema.ref("CloudExtinction")
  lazy val ConstraintSetType           = schema.ref("ConstraintSet")
  lazy val CoordinatesType             = schema.ref("Coordinates")
  lazy val CreateObservationResultType = schema.ref("CreateObservationResult")
  lazy val CreateProgramResultType     = schema.ref("CreateProgramResult")
  lazy val CreateTargetResultType      = schema.ref("CreateTargetResult")
  lazy val DeclinationType             = schema.ref("Declination")
  lazy val DemoScienceType             = schema.ref("DemoScience")
  lazy val DirectorsTimeType           = schema.ref("DirectorsTime")
  lazy val DmsStringType               = schema.ref("DmsString")
  lazy val EditTypeType                = schema.ref("EditType")
  lazy val ElevationRangeType          = schema.ref("ElevationRange")
  lazy val EphemerisKeyTypeType        = schema.ref("EphemerisKeyType")
  lazy val EpochStringType             = schema.ref("EpochString")
  lazy val ExchangeType                = schema.ref("Exchange")
  lazy val ExistenceType               = schema.ref("Existence")
  lazy val FastTurnaroundType          = schema.ref("FastTurnaround")
  lazy val FilterTypeMetaType          = schema.ref("FilterTypeMeta")
  lazy val FilterTypeType              = schema.ref("FilterType")
  lazy val HmsStringType               = schema.ref("HmsString")
  lazy val HourAngleRangeType          = schema.ref("HourAngleRange")
  lazy val ImageQualityType            = schema.ref("ImageQuality")
  lazy val IntensiveType               = schema.ref("Intensive")
  lazy val IntPercentType              = schema.ref("IntPercent")
  lazy val LargeProgramType            = schema.ref("LargeProgram")
  lazy val LinkUserResultType          = schema.ref("LinkUserResult")
  lazy val LongType                    = schema.ref("Long")
  lazy val MutationType                = schema.ref("Mutation")
  lazy val NonEmptyStringType          = schema.ref("NonEmptyString")
  lazy val NonNegBigDecimalType        = schema.ref("NonNegBigDecimal")
  lazy val NonNegDurationType          = schema.ref("NonNegDuration")
  lazy val NonNegLongType              = schema.ref("NonNegLong")
  lazy val NonsiderealType             = schema.ref("Nonsidereal")
  lazy val ObsActiveStatusType         = schema.ref("ObsActiveStatus")
  lazy val ObservationIdType           = schema.ref("ObservationId")
  lazy val ObservationType             = schema.ref("Observation")
  lazy val ObsStatusType               = schema.ref("ObsStatus")
  lazy val ParallaxType                = schema.ref("Parallax")
  lazy val PartnerMetaType             = schema.ref("PartnerMeta")
  lazy val PartnerSplitType            = schema.ref("PartnerSplit")
  lazy val PartnerType                 = schema.ref("Partner")
  lazy val PoorWeatherType             = schema.ref("PoorWeather")
  lazy val PosAngleConstraintModeType  = schema.ref("PosAngleConstraintMode")
  lazy val PosAngleConstraintType      = schema.ref("PosAngleConstraint")
  lazy val PosBigDecimalType           = schema.ref("PosBigDecimal")
  lazy val ProgramEditType             = schema.ref("ProgramEdit")
  lazy val ProgramIdType               = schema.ref("ProgramId")
  lazy val ProgramType                 = schema.ref("Program")
  lazy val ProgramUserRoleType         = schema.ref("ProgramUserRole")
  lazy val ProperMotionDeclinationType = schema.ref("ProperMotionDeclination")
  lazy val ProperMotionRAType          = schema.ref("ProperMotionRA")
  lazy val ProperMotionType            = schema.ref("ProperMotion")
  lazy val ProposalClassType           = schema.ref("ProposalClass")
  lazy val ProposalType                = schema.ref("Proposal")
  lazy val QueueType                   = schema.ref("Queue")
  lazy val RadialVelocityType          = schema.ref("RadialVelocity")
  lazy val RightAscensionType          = schema.ref("RightAscension")
  lazy val SetAllocationResultType     = schema.ref("SetAllocationResult")
  lazy val SiderealType                = schema.ref("Sidereal")
  lazy val SkyBackgroundType           = schema.ref("SkyBackground")
  lazy val SystemVerificationType      = schema.ref("SystemVerification")
  lazy val TacCategoryType             = schema.ref("TacCategory")
  lazy val TargetIdType                = schema.ref("TargetId")
  lazy val TargetType                  = schema.ref("Target")
  lazy val TimestampType               = schema.ref("Timestamp")
  lazy val ToOActivationType           = schema.ref("ToOActivation")
  lazy val UserIdType                  = schema.ref("UserId")
  lazy val UserTypeType                = schema.ref("UserType")
  lazy val WaterVaporType              = schema.ref("WaterVapor")

}