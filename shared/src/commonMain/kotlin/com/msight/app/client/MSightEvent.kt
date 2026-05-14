package com.msight.app.client

sealed class MSightEvent(
    open val timestampMillis: Long,
    open val eventId: String? = null
)

data class MSightLocationEvent(
    override val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val bearingDegrees: Float?,
    val provider: String
) : MSightEvent(timestampMillis)

sealed class MSightWarningEvent(
    override val timestampMillis: Long
) : MSightEvent(timestampMillis)

data class MSightSimpleWarning(
    override val timestampMillis: Long,
    override val eventId: String? = null,
    val message: String
) : MSightWarningEvent(timestampMillis)

data class MSightSdsmEvent(
    override val timestampMillis: Long,
    override val eventId: String? = null,
    val sensorName: String,
    val deviceName: String,
    val captureTimestamp: Double,
    val creationTimestamp: Double,
    val frameId: String,
    val msgCnt: Int,
    val sourceId: String,
    val equipmentType: String?,
    val sdsmTimestamp: SdsmTimestamp?,
    val refPos: SdsmRefPos,
    val refPosXYConf: SdsmRefPosConf?,
    val objects: List<SdsmDetectedObject>
) : MSightEvent(timestampMillis)

sealed class MSightConflictWarningEvent(
    override val timestampMillis: Long,
    open val ttcSeconds: Double?,
    open val pretSeconds: Double?
) : MSightWarningEvent(timestampMillis)

data class MSightTwoVehicleConflictEvent(
    override val timestampMillis: Long,
    override val eventId: String? = null,
    override val ttcSeconds: Double?,
    override val pretSeconds: Double?,
    val firstVehicleTrajectory: MSightTrajectory,
    val secondVehicleTrajectory: MSightTrajectory,
    val firstVehiclePredictedTrajectory: MSightPredictedTrajectory,
    val secondVehiclePredictedTrajectory: MSightPredictedTrajectory
) : MSightConflictWarningEvent(
    timestampMillis = timestampMillis,
    ttcSeconds = ttcSeconds,
    pretSeconds = pretSeconds
)

data class MSightVehicleVRUConflictEvent(
    override val timestampMillis: Long,
    override val eventId: String? = null,
    override val ttcSeconds: Double?,
    override val pretSeconds: Double?,
    val vehicleTrajectory: MSightTrajectory,
    val vruTrajectory: MSightTrajectory,
    val vehiclePredictedTrajectory: MSightPredictedTrajectory,
    val vruPredictedTrajectory: MSightPredictedTrajectory
) : MSightConflictWarningEvent(
    timestampMillis = timestampMillis,
    ttcSeconds = ttcSeconds,
    pretSeconds = pretSeconds
)