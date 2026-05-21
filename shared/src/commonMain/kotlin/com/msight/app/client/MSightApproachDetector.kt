package com.msight.app.client

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class SignalColor {
    GREEN, YELLOW, RED, UNKNOWN;

    companion object {
        fun fromEventState(state: String): SignalColor = when {
            state.equals("protected-Movement-Allowed", ignoreCase = true) ||
            state.equals("permissive-Movement-Allowed", ignoreCase = true) -> GREEN

            state.equals("protected-clearance", ignoreCase = true) ||
            state.equals("permissive-clearance", ignoreCase = true) ||
            state.equals("caution-Conflicting-Traffic", ignoreCase = true) -> YELLOW

            state.equals("stop-And-Remain", ignoreCase = true) ||
            state.equals("stop-Then-Proceed", ignoreCase = true) ||
            state.equals("pre-Movement", ignoreCase = true) -> RED

            else -> UNKNOWN
        }
    }
}

data class ApproachResult(
    val intersection: MSightIntersectionMap,
    val arm: MapArm
)

data class ArmSignalState(
    val straightColor: SignalColor,
    val leftTurnColor: SignalColor
)

object MSightApproachDetector {

    private const val APPROACH_DISTANCE_METERS = 150.0
    private const val HEADING_TOLERANCE_DEG = 35.0
    private const val MOVING_SPEED_THRESHOLD_MPS = 2.0f
    private const val MIN_BASELINE_METERS = 10.0

    /**
     * Given the current GPS fix, the recent location history, and all loaded intersection maps,
     * returns the intersection + arm the device is approaching, or null if none match.
     *
     * Arm matching uses heading alignment: the arm whose approachBearingDeg best matches the
     * device's heading (within HEADING_TOLERANCE_DEG) wins. When stopped, the last known heading
     * from history is used so the result stays stable at a red light.
     */
    fun detectActiveApproach(
        currentLocation: MSightLocationEvent,
        locationHistory: List<MSightTrajectoryPoint>,
        maps: List<MSightIntersectionMap>
    ): ApproachResult? {
        val heading = effectiveHeading(currentLocation, locationHistory) ?: run {
            println("INFO: ApproachDetector heading=null (insufficient history), returning null")
            return null
        }

        println("INFO: ApproachDetector heading=%.1f°  loc=(%.6f, %.6f)".format(
            heading, currentLocation.latitude, currentLocation.longitude))

        var bestResult: ApproachResult? = null
        var bestScore = Double.MAX_VALUE

        for (map in maps) {
            val dist = haversineDistanceMeters(
                currentLocation.latitude, currentLocation.longitude,
                map.centerLat, map.centerLon
            )
            if (dist > APPROACH_DISTANCE_METERS) {
                println("INFO: ApproachDetector  skip intersection=${map.name} dist=%.1fm > threshold".format(dist))
                continue
            }

            for (arm in map.arms) {
                val diff = angleDifferenceDeg(heading, arm.approachBearingDeg)
                val score = diff + dist * 0.1
                println("INFO: ApproachDetector  intersection=${map.name} armId=${arm.armId} bearing=%.1f° diff=%.1f° dist=%.1fm score=%.2f %s".format(
                    arm.approachBearingDeg, diff, dist, score,
                    if (diff > HEADING_TOLERANCE_DEG) "[REJECTED: diff>$HEADING_TOLERANCE_DEG]" else "[candidate]"))
                if (diff > HEADING_TOLERANCE_DEG) continue
                if (score < bestScore) {
                    bestScore = score
                    bestResult = ApproachResult(map, arm)
                }
            }
        }

        println("INFO: ApproachDetector result=${bestResult?.let {
            val left = it.arm.leftTurnSignalGroups.joinToString(",").ifEmpty { "none" }
            val straight = it.arm.straightSignalGroups.joinToString(",").ifEmpty { "none" }
            "intersection=${it.intersection.name} armId=${it.arm.armId} bearing=%.1f° left: $left; straight: $straight".format(it.arm.approachBearingDeg)
        } ?: "null"}")
        return bestResult
    }

    /**
     * For the given arm and a SPaT event from its intersection, returns the current
     * signal color for straight-ahead and left-turn movements.
     */
    fun extractArmSignals(arm: MapArm, spatEvent: MSightSpatEvent): ArmSignalState {
        val statesByGroup = spatEvent.intersection.states.associateBy { it.signalGroup }

        val straightColor = arm.straightSignalGroups
            .mapNotNull { sg -> statesByGroup[sg]?.stateTimeSpeed?.firstOrNull()?.eventState }
            .firstNotNullOfOrNull { s -> SignalColor.fromEventState(s).takeIf { it != UNKNOWN } }
            ?: UNKNOWN

        val leftColor = arm.leftTurnSignalGroups
            .mapNotNull { sg -> statesByGroup[sg]?.stateTimeSpeed?.firstOrNull()?.eventState }
            .firstNotNullOfOrNull { s -> SignalColor.fromEventState(s).takeIf { it != UNKNOWN } }
            ?: UNKNOWN

        return ArmSignalState(straightColor, leftColor)
    }

    /**
     * Returns the heading the device is traveling, in degrees (0–360, clockwise from north).
     * Precedence:
     *  1. GPS bearing when speed is above stopped threshold
     *  2. Most recent moving point's heading from history (handles stopped-at-red)
     *  3. Position delta across the last few history points
     */
    private fun effectiveHeading(
        current: MSightLocationEvent,
        history: List<MSightTrajectoryPoint>
    ): Double? {
        // Case 1: clearly moving — GPS bearing is reliable above ~2 m/s
        val speed = current.speedMps ?: 0f
        if (speed >= MOVING_SPEED_THRESHOLD_MPS && current.bearingDegrees != null) {
            return current.bearingDegrees.toDouble()
        }
        // Case 2: stopped or slow — use the frozen heading from the last clearly-moving point.
        // This keeps approach detection stable while waiting at a red light.
        val movingPoint = history.lastOrNull { (it.speedMps ?: 0f) >= MOVING_SPEED_THRESHOLD_MPS }
        if (movingPoint?.headingDegrees != null) {
            return movingPoint.headingDegrees.toDouble()
        }
        // Case 3: no moving point in history yet — compute bearing from the most recent history
        // point that is at least MIN_BASELINE_METERS away. Using a long baseline filters out
        // GPS jitter: if the vehicle is truly stationary, all history points sit within the
        // jitter cloud (~2–5 m) and no point will qualify, correctly returning null.
        for (point in history.asReversed()) {
            val dist = haversineDistanceMeters(
                current.latitude, current.longitude, point.latitude, point.longitude
            )
            if (dist >= MIN_BASELINE_METERS) {
                val dLon = current.longitude - point.longitude
                val dLat = current.latitude - point.latitude
                val scaledDLon = dLon * cos(point.latitude * PI / 180.0)
                return (atan2(scaledDLon, dLat) * 180.0 / PI + 360.0) % 360.0
            }
        }
        return null
    }

    /** Returns the absolute angular difference between two bearings, in [0, 180]. */
    private fun angleDifferenceDeg(a: Double, b: Double): Double {
        val raw = ((a - b) % 360.0 + 360.0) % 360.0
        return abs(if (raw > 180.0) 360.0 - raw else raw)
    }

    private fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6_371_000.0
        val phi1 = lat1 * PI / 180.0
        val phi2 = lat2 * PI / 180.0
        val dPhi = (lat2 - lat1) * PI / 180.0
        val dLambda = (lon2 - lon1) * PI / 180.0
        val sinHalfDPhi = sin(dPhi / 2)
        val sinHalfDLambda = sin(dLambda / 2)
        val a = sinHalfDPhi * sinHalfDPhi + cos(phi1) * cos(phi2) * sinHalfDLambda * sinHalfDLambda
        return R * 2.0 * asin(sqrt(a))
    }

    private val UNKNOWN = SignalColor.UNKNOWN
}
