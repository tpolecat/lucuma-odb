// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql

import edu.gemini.grackle.Schema
import edu.gemini.grackle.skunk.SkunkMapping
import lucuma.odb.graphql.util.MappingExtras
import lucuma.odb.graphql.util.SchemaSemigroup

trait BaseMapping[F[_]]
  extends SkunkMapping[F]
     with SchemaSemigroup[F]
     with MappingExtras[F] {

  // TODO: auto-generate this
  lazy val AirMassRangeType                    = schema.ref("AirMassRange")
  lazy val AllocationType                      = schema.ref("Allocation")
  lazy val AsterismGroupType                   = schema.ref("AsterismGroup")
  lazy val AsterismGroupSelectResultType       = schema.ref("AsterismGroupSelectResult")
  lazy val AngleType                           = schema.ref("Angle")
  lazy val BigDecimalType                      = schema.ref("BigDecimal")
  lazy val CatalogInfoType                     = schema.ref("CatalogInfo")
  lazy val CatalogNameType                     = schema.ref("CatalogName")
  lazy val ClassicalType                       = schema.ref("Classical")
  lazy val CloudExtinctionType                 = schema.ref("CloudExtinction")
  lazy val ConstraintSetType                   = schema.ref("ConstraintSet")
  lazy val ConstraintSetGroupType              = schema.ref("ConstraintSetGroup")
  lazy val ConstraintSetGroupSelectResultType  = schema.ref("ConstraintSetGroupSelectResult")
  lazy val CoordinatesType                     = schema.ref("Coordinates")
  lazy val CreateObservationResultType         = schema.ref("CreateObservationResult")
  lazy val CreateProgramResultType             = schema.ref("CreateProgramResult")
  lazy val CreateTargetResultType              = schema.ref("CreateTargetResult")
  lazy val DeclinationType                     = schema.ref("Declination")
  lazy val DemoScienceType                     = schema.ref("DemoScience")
  lazy val DirectorsTimeType                   = schema.ref("DirectorsTime")
  lazy val DmsStringType                       = schema.ref("DmsString")
  lazy val EditTypeType                        = schema.ref("EditType")
  lazy val ElevationRangeType                  = schema.ref("ElevationRange")
  lazy val EphemerisKeyTypeType                = schema.ref("EphemerisKeyType")
  lazy val EpochStringType                     = schema.ref("EpochString")
  lazy val ExchangeType                        = schema.ref("Exchange")
  lazy val ExistenceType                       = schema.ref("Existence")
  lazy val FastTurnaroundType                  = schema.ref("FastTurnaround")
  lazy val FilterTypeMetaType                  = schema.ref("FilterTypeMeta")
  lazy val FilterTypeType                      = schema.ref("FilterType")
  lazy val FocalPlaneType                      = schema.ref("FocalPlane")
  lazy val GmosAmpGainType                     = schema.ref("GmosAmpGain")
  lazy val GmosAmpReadModeType                 = schema.ref("GmosAmpReadMode")
  lazy val GmosNorthBuiltinFpuType             = schema.ref("GmosNorthBuiltinFpu")
  lazy val GmosNorthFilterType                 = schema.ref("GmosNorthFilter")
  lazy val GmosNorthGratingType                = schema.ref("GmosNorthGrating")
  lazy val GmosNorthLongSlitType               = schema.ref("GmosNorthLongSlit")
  lazy val GmosRoiType                         = schema.ref("GmosRoi")
  lazy val GmosSouthBuiltinFpuType             = schema.ref("GmosSouthBuiltinFpu")
  lazy val GmosSouthFilterType                 = schema.ref("GmosSouthFilter")
  lazy val GmosSouthGratingType                = schema.ref("GmosSouthGrating")
  lazy val GmosSouthLongSlitType               = schema.ref("GmosSouthLongSlit")
  lazy val GmosXBinningType                    = schema.ref("GmosXBinning")
  lazy val GmosYBinningType                    = schema.ref("GmosYBinning")
  lazy val HmsStringType                       = schema.ref("HmsString")
  lazy val HourAngleRangeType                  = schema.ref("HourAngleRange")
  lazy val ImageQualityType                    = schema.ref("ImageQuality")
  lazy val InstrumentType                      = schema.ref("Instrument")
  lazy val IntensiveType                       = schema.ref("Intensive")
  lazy val IntPercentType                      = schema.ref("IntPercent")
  lazy val LargeProgramType                    = schema.ref("LargeProgram")
  lazy val LinkUserResultType                  = schema.ref("LinkUserResult")
  lazy val LongType                            = schema.ref("Long")
  lazy val MutationType                        = schema.ref("Mutation")
  lazy val NonEmptyStringType                  = schema.ref("NonEmptyString")
  lazy val NonNegBigDecimalType                = schema.ref("NonNegBigDecimal")
  lazy val NonNegLongType                      = schema.ref("NonNegLong")
  lazy val NonsiderealType                     = schema.ref("Nonsidereal")
  lazy val ObsActiveStatusType                 = schema.ref("ObsActiveStatus")
  lazy val ObservationEditType                 = schema.ref("ObservationEdit")
  lazy val ObservationIdType                   = schema.ref("ObservationId")
  lazy val ObservationType                     = schema.ref("Observation")
  lazy val ObservingModeType                   = schema.ref("ObservingMode")
  lazy val ObservingModeTypeType               = schema.ref("ObservingModeType")
  lazy val ObservationSelectResultType         = schema.ref("ObservationSelectResult")
  lazy val ObsStatusType                       = schema.ref("ObsStatus")
  lazy val pType                               = schema.ref("p")
  lazy val ParallaxType                        = schema.ref("Parallax")
  lazy val PartnerMetaType                     = schema.ref("PartnerMeta")
  lazy val PartnerSplitType                    = schema.ref("PartnerSplit")
  lazy val PartnerType                         = schema.ref("Partner")
  lazy val PlannedTimeSummaryType              = schema.ref("PlannedTimeSummary")
  lazy val PoorWeatherType                     = schema.ref("PoorWeather")
  lazy val PosAngleConstraintModeType          = schema.ref("PosAngleConstraintMode")
  lazy val PosAngleConstraintType              = schema.ref("PosAngleConstraint")
  lazy val PosBigDecimalType                   = schema.ref("PosBigDecimal")
  lazy val PosIntType                          = schema.ref("PosInt")
  lazy val ProgramEditType                     = schema.ref("ProgramEdit")
  lazy val ProgramIdType                       = schema.ref("ProgramId")
  lazy val ProgramSelectResultType             = schema.ref("ProgramSelectResult")
  lazy val ProgramType                         = schema.ref("Program")
  lazy val ProgramUserRoleType                 = schema.ref("ProgramUserRole")
  lazy val ProperMotionDeclinationType         = schema.ref("ProperMotionDeclination")
  lazy val ProperMotionRAType                  = schema.ref("ProperMotionRA")
  lazy val ProperMotionType                    = schema.ref("ProperMotion")
  lazy val ProposalClassType                   = schema.ref("ProposalClass")
  lazy val ProposalType                        = schema.ref("Proposal")
  lazy val qType                               = schema.ref("qType")
  lazy val QueryType                           = schema.ref("Query")
  lazy val QueueType                           = schema.ref("Queue")
  lazy val RadialVelocityType                  = schema.ref("RadialVelocity")
  lazy val RightAscensionType                  = schema.ref("RightAscension")
  lazy val ScienceModeType                     = schema.ref("ScienceMode")
  lazy val ScienceRequirementsType             = schema.ref("ScienceRequirements")
  lazy val SetAllocationResultType             = schema.ref("SetAllocationResult")
  lazy val SiderealType                        = schema.ref("Sidereal")
  lazy val SkyBackgroundType                   = schema.ref("SkyBackground")
  lazy val SpectroscopyCapabilitiesType        = schema.ref("SpectroscopyCapabilities")
  lazy val SpectroscopyScienceRequirementsType = schema.ref("SpectroscopyScienceRequirements")
  lazy val SystemVerificationType              = schema.ref("SystemVerification")
  lazy val TacCategoryType                     = schema.ref("TacCategory")
  lazy val TargetEnvironmentType               = schema.ref("TargetEnvironment")
  lazy val TargetGroupSelectResultType         = schema.ref("TargetGroupSelectResult")
  lazy val TargetGroupType                     = schema.ref("TargetGroup")
  lazy val TargetIdType                        = schema.ref("TargetId")
  lazy val TargetSelectResultType              = schema.ref("TargetSelectResult")
  lazy val TargetType                          = schema.ref("Target")
  lazy val TimestampType                       = schema.ref("Timestamp")
  lazy val TimeSpanType                        = schema.ref("TimeSpan")
  lazy val ToOActivationType                   = schema.ref("ToOActivation")
  lazy val UpdateAsterismsResultType           = schema.ref("UpdateAsterismsResult")
  lazy val UpdateObservationsResultType        = schema.ref("UpdateObservationsResult")
  lazy val UpdateProgramsResultType            = schema.ref("UpdateProgramsResult")
  lazy val UserIdType                          = schema.ref("UserId")
  lazy val UserTypeType                        = schema.ref("UserType")
  lazy val WaterVaporType                      = schema.ref("WaterVapor")
  lazy val WavelengthType                      = schema.ref("Wavelength")

}