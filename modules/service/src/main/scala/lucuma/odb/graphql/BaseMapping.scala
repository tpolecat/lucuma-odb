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
  lazy val AddConditionsEntryResultType        = schema.ref("AddConditionsEntryResult")
  lazy val AddSequenceEventResultType          = schema.ref("AddSequenceEventResult")
  lazy val AirMassRangeType                    = schema.ref("AirMassRange")
  lazy val AllocationType                      = schema.ref("Allocation")
  lazy val AsterismGroupType                   = schema.ref("AsterismGroup")
  lazy val AsterismGroupSelectResultType       = schema.ref("AsterismGroupSelectResult")
  lazy val AngleType                           = schema.ref("Angle")
  lazy val BigDecimalType                      = schema.ref("BigDecimal")
  lazy val CatalogInfoType                     = schema.ref("CatalogInfo")
  lazy val CatalogNameType                     = schema.ref("CatalogName")
  lazy val ChronicleIdType                     = schema.ref("ChronicleId")
  lazy val ClassicalType                       = schema.ref("Classical")
  lazy val CloneObservationResultType          = schema.ref("CloneObservationResult")
  lazy val CloneTargetResultType               = schema.ref("CloneTargetResult")
  lazy val CloudExtinctionType                 = schema.ref("CloudExtinction")
  lazy val ConditionsEntryType                 = schema.ref("ConditionsEntry")
  lazy val ConditionsMeasurementType           = schema.ref("ConditionsMeasurement")
  lazy val ConditionsMeasurementSourceType     = schema.ref("ConditionsMeasurementSource")
  lazy val ConditionsIntuitionType             = schema.ref("ConditionsIntuition")
  lazy val ConditionsExpectationType           = schema.ref("ConditionsExpectation")
  lazy val ConditionsExpectationTypeType       = schema.ref("ConditionsExpectationType")
  lazy val ConditionsSourceType                = schema.ref("ConditionsSource")
  lazy val ConstraintSetType                   = schema.ref("ConstraintSet")
  lazy val ConstraintSetGroupType              = schema.ref("ConstraintSetGroup")
  lazy val ConstraintSetGroupSelectResultType  = schema.ref("ConstraintSetGroupSelectResult")
  lazy val CoordinatesType                     = schema.ref("Coordinates")
  lazy val CreateGroupResultType               = schema.ref("CreateGroupResult")
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
  lazy val ExecutionEventIdType                = schema.ref("ExecutionEventId")
  lazy val ExistenceType                       = schema.ref("Existence")
  lazy val ExtinctionType                      = schema.ref("Extinction")
  lazy val FastTurnaroundType                  = schema.ref("FastTurnaround")
  lazy val FilterTypeMetaType                  = schema.ref("FilterTypeMeta")
  lazy val FilterTypeType                      = schema.ref("FilterType")
  lazy val FocalPlaneType                      = schema.ref("FocalPlane")
  lazy val GcalArcType                         = schema.ref("GcalArc")
  lazy val GcalContinuumType                   = schema.ref("GcalContinuum")
  lazy val GcalDiffuserType                    = schema.ref("GcalDiffuser")
  lazy val GcalFilterType                      = schema.ref("GcalFilter")
  lazy val GcalShutterType                     = schema.ref("GcalShutter")
  lazy val GmosAmpGainType                     = schema.ref("GmosAmpGain")
  lazy val GmosAmpReadModeType                 = schema.ref("GmosAmpReadMode")
  lazy val GmosNorthBuiltinFpuType             = schema.ref("GmosNorthBuiltinFpu")
  lazy val GmosNorthDetectorType               = schema.ref("GmosNorthDetector")
  lazy val GmosNorthFilterType                 = schema.ref("GmosNorthFilter")
  lazy val GmosNorthGratingType                = schema.ref("GmosNorthGrating")
  lazy val GmosNorthLongSlitType               = schema.ref("GmosNorthLongSlit")
  lazy val GmosNorthStageModeType              = schema.ref("GmosNorthStageMode")
  lazy val GmosRoiType                         = schema.ref("GmosRoi")
  lazy val GmosNorthStaticType                 = schema.ref("GmosNorthStatic")
  lazy val GmosNorthStepRecordType             = schema.ref("GmosNorthStepRecord")
  lazy val GmosNorthStepType                   = schema.ref("GmosNorthStep")
  lazy val GmosNorthVisitType                  = schema.ref("GmosNorthVisit")
  lazy val GmosSouthBuiltinFpuType             = schema.ref("GmosSouthBuiltinFpu")
  lazy val GmosSouthDetectorType               = schema.ref("GmosSouthDetector")
  lazy val GmosSouthFilterType                 = schema.ref("GmosSouthFilter")
  lazy val GmosSouthGratingType                = schema.ref("GmosSouthGrating")
  lazy val GmosSouthLongSlitType               = schema.ref("GmosSouthLongSlit")
  lazy val GmosSouthStageModeType              = schema.ref("GmosSouthStageMode")
  lazy val GmosSouthStaticType                 = schema.ref("GmosSouthStatic")
  lazy val GmosSouthStepRecordType             = schema.ref("GmosSouthStepRecord")
  lazy val GmosSouthStepType                   = schema.ref("GmosSouthStep")
  lazy val GmosSouthVisitType                  = schema.ref("GmosSouthVisit")
  lazy val GmosXBinningType                    = schema.ref("GmosXBinning")
  lazy val GmosYBinningType                    = schema.ref("GmosYBinning")
  lazy val GroupType                           = schema.ref("Group")
  lazy val GroupIdType                         = schema.ref("GroupId")
  lazy val GroupEditType                       = schema.ref("GroupEdit")
  lazy val GroupElementType                    = schema.ref("GroupElement")
  lazy val GuideStateType                      = schema.ref("GuideState")
  lazy val HmsStringType                       = schema.ref("HmsString")
  lazy val HourAngleRangeType                  = schema.ref("HourAngleRange")
  lazy val ImageQualityType                    = schema.ref("ImageQuality")
  lazy val InstrumentType                      = schema.ref("Instrument")
  lazy val IntensiveType                       = schema.ref("Intensive")
  lazy val IntPercentType                      = schema.ref("IntPercent")
  lazy val LargeProgramType                    = schema.ref("LargeProgram")
  lazy val LinkUserResultType                  = schema.ref("LinkUserResult")
  lazy val LongType                            = schema.ref("Long")
  lazy val MosPreImagingType                   = schema.ref("MosPreImaging")
  lazy val MutationType                        = schema.ref("Mutation")
  lazy val NonEmptyStringType                  = schema.ref("NonEmptyString")
  lazy val NonNegBigDecimalType                = schema.ref("NonNegBigDecimal")
  lazy val NonNegIntType                       = schema.ref("NonNegInt")
  lazy val NonNegLongType                      = schema.ref("NonNegLong")
  lazy val NonNegShortType                     = schema.ref("NonNegShort")
  lazy val NonsiderealType                     = schema.ref("Nonsidereal")
  lazy val ObsActiveStatusType                 = schema.ref("ObsActiveStatus")
  lazy val ObsAttachmentFileExtType            = schema.ref("ObsAttachmentFileExt")
  lazy val ObsAttachmentIdType                 = schema.ref("ObsAttachmentId")
  lazy val ObsAttachmentType                   = schema.ref("ObsAttachment")
  lazy val ObsAttachmentTypeMetaType           = schema.ref("ObsAttachmentTypeMeta")
  lazy val ObsAttachmentTypeType               = schema.ref("ObsAttachmentType")
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
  lazy val ProposalAttachmentType              = schema.ref("ProposalAttachment")
  lazy val ProposalAttachmentTypeMetaType      = schema.ref("ProposalAttachmentTypeMeta")
  lazy val ProposalAttachmentTypeType          = schema.ref("ProposalAttachmentType")
  lazy val ProposalClassType                   = schema.ref("ProposalClass")
  lazy val ProposalType                        = schema.ref("Proposal")
  lazy val qType                               = schema.ref("qType")
  lazy val QueryType                           = schema.ref("Query")
  lazy val QueueType                           = schema.ref("Queue")
  lazy val RadialVelocityType                  = schema.ref("RadialVelocity")
  lazy val RecordGmosNorthStepResultType       = schema.ref("RecordGmosNorthStepResult")
  lazy val RecordGmosSouthStepResultType       = schema.ref("RecordGmosSouthStepResult")
  lazy val RecordGmosNorthVisitResultType      = schema.ref("RecordGmosNorthVisitResult")
  lazy val RecordGmosSouthVisitResultType      = schema.ref("RecordGmosSouthVisitResult")
  lazy val RightAscensionType                  = schema.ref("RightAscension")
  lazy val ScienceModeType                     = schema.ref("ScienceMode")
  lazy val ScienceRequirementsType             = schema.ref("ScienceRequirements")
  lazy val SeeingTrendType                     = schema.ref("SeeingTrend")
  lazy val SequenceCommandType                 = schema.ref("SequenceCommand")
  lazy val SequenceEventType                   = schema.ref("SequenceEvent")
  lazy val SetAllocationResultType             = schema.ref("SetAllocationResult")
  lazy val SiderealType                        = schema.ref("Sidereal")
  lazy val SignalToNoiseType                   = schema.ref("SignalToNoise")
  lazy val SkyBackgroundType                   = schema.ref("SkyBackground")
  lazy val SmartGcalType                       = schema.ref("StepGcalType")
  lazy val SpectroscopyCapabilitiesType        = schema.ref("SpectroscopyCapabilities")
  lazy val SpectroscopyScienceRequirementsType = schema.ref("SpectroscopyScienceRequirements")
  lazy val StepIdType                          = schema.ref("StepId")
  lazy val StepType                            = schema.ref("StepType")
  lazy val SystemVerificationType              = schema.ref("SystemVerification")
  lazy val TacCategoryType                     = schema.ref("TacCategory")
  lazy val TargetEditType                      = schema.ref("TargetEdit")
  lazy val TargetEnvironmentType               = schema.ref("TargetEnvironment")
  lazy val TargetGroupSelectResultType         = schema.ref("TargetGroupSelectResult")
  lazy val TargetGroupType                     = schema.ref("TargetGroup")
  lazy val TargetIdType                        = schema.ref("TargetId")
  lazy val TargetSelectResultType              = schema.ref("TargetSelectResult")
  lazy val TargetType                          = schema.ref("Target")
  lazy val TimestampType                       = schema.ref("Timestamp")
  lazy val TimeSpanType                        = schema.ref("TimeSpan")
  lazy val TimingWindowEndAfterType            = schema.ref("TimingWindowEndAfter")
  lazy val TimingWindowEndAtType               = schema.ref("TimingWindowEndAt")
  lazy val TimingWindowEndType                 = schema.ref("TimingWindowEnd")
  lazy val TimingWindowInclusionType           = schema.ref("TimingWindowInclusion")
  lazy val TimingWindowRepeatType              = schema.ref("TimingWindowRepeat")
  lazy val TimingWindowType                    = schema.ref("TimingWindow")
  lazy val ToOActivationType                   = schema.ref("ToOActivation")
  lazy val UpdateAsterismsResultType           = schema.ref("UpdateAsterismsResult")
  lazy val UpdateGroupsResultType              = schema.ref("UpdateGroupsResult")
  lazy val UpdateObsAttachmentsResultType      = schema.ref("UpdateObsAttachmentsResult")
  lazy val UpdateObservationsResultType        = schema.ref("UpdateObservationsResult")
  lazy val UpdateProgramsResultType            = schema.ref("UpdateProgramsResult")
  lazy val UpdateProposalAttachmentsResultType = schema.ref("UpdateProposalAttachmentsResult")
  lazy val UpdateTargetsResultType             = schema.ref("UpdateTargetsResult")
  lazy val UserIdType                          = schema.ref("UserId")
  lazy val UserTypeType                        = schema.ref("UserType")
  lazy val VisitIdType                         = schema.ref("VisitId")
  lazy val WaterVaporType                      = schema.ref("WaterVapor")
  lazy val WavelengthType                      = schema.ref("Wavelength")

}
