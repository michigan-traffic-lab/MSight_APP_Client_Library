package com.msight.app.client

/**
 * An ordered sequence of observed positions for one road user.
 *
 * Points are ordered oldest-first. The library uses this both for the host device's own recent
 * track (see [MSightClient.locationHistory]) and for the participant tracks carried by conflict
 * warnings pushed from MSight Cloud.
 */
data class MSightTrajectory(
    val points: List<MSightTrajectoryPoint>
)

/**
 * A single observed position sample.
 *
 * @property timestampMillis Observation time, Unix epoch milliseconds (UTC).
 * @property latitude Latitude in decimal degrees (WGS 84).
 * @property longitude Longitude in decimal degrees (WGS 84).
 * @property altitudeMeters Altitude in metres above mean sea level, or null when unavailable.
 * @property speedMps Ground speed in metres per second, or null when unavailable.
 * @property headingDegrees Direction of travel in degrees clockwise from true north, or null
 *   when unavailable. Note that consumer GNSS receivers usually stop reporting a usable heading
 *   at low speed.
 */
data class MSightTrajectoryPoint(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val speedMps: Float? = null,
    val headingDegrees: Float? = null
)

/**
 * A short-horizon motion prediction for one road user, expressed as a constant-curvature model
 * rather than a list of future positions.
 *
 * @property headingDegrees Predicted heading in degrees clockwise from true north.
 * @property speedMps Predicted ground speed in metres per second.
 * @property curvature Predicted path curvature in 1/metres; positive turns right, negative turns
 *   left, zero is a straight path.
 */
data class MSightPredictedTrajectory(
    val headingDegrees: Float,
    val speedMps: Float,
    val curvature: Float
)
