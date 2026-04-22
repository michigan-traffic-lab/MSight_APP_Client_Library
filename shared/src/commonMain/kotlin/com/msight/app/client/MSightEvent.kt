package com.msight.app.client

sealed class MSightEvent(
    open val timestampMillis: Long
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

sealed class MSightConflictWarningEvent(
    override val timestampMillis: Long,
    open val ttcSeconds: Double?,
    open val pretSeconds: Double?
) : MSightWarningEvent(timestampMillis)

data class MSightTwoVehicleConflictEvent(
    override val timestampMillis: Long,
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