package com.msight.app.client

data class MSightTrajectory(
    val points: List<MSightTrajectoryPoint>
)

data class MSightTrajectoryPoint(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val speedMps: Float? = null,
    val headingDegrees: Float? = null
)

data class MSightPredictedTrajectory(
    val headingDegrees: Float,
    val speedMps: Float,
    val curvature: Float
)