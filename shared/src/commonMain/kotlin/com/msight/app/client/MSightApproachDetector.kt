package com.msight.app.client

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A signal indication as a driver sees it, collapsed from the finer-grained J2735 movement
 * phase states.
 *
 * [UNKNOWN] covers three distinct situations a caller may want to distinguish by context: no SPaT
 * has arrived yet, the SPaT reports a state with no colour equivalent (dark, flashing), or the
 * arm's signal groups are absent from the SPaT.
 */
enum class SignalColor {
    GREEN, YELLOW, RED, UNKNOWN;

    companion object {
        /**
         * Maps a J2735 `eventState` string onto a displayable colour.
         *
         * `pre-Movement` maps to [RED] because the movement is not yet permitted — a driver
         * should still be stopped. Anything unrecognised maps to [UNKNOWN] rather than guessing.
         */
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

/** The intersection and the specific approach arm a device was matched to. */
data class ApproachResult(
    val intersection: MSightIntersectionMap,
    val arm: MapArm
)

/** Current signal colours for the movements available from one matched arm. */
data class ArmSignalState(
    val straightColor: SignalColor,
    val leftTurnColor: SignalColor
)

/**
 * Decides which intersection approach a device is on, and what its signal is showing.
 *
 * This is the piece that turns two independent cloud feeds — intersection MAP geometry and SPaT
 * phase state — into something a driver-facing app can display, by answering the question the
 * cloud cannot answer for a specific device: *which* of the intersection's signal groups is the
 * one facing this driver.
 *
 * Both functions are pure and stateless; the frame-to-frame hysteresis that keeps a display from
 * flickering lives in the client's signal processor, not here.
 */
object MSightApproachDetector {

    /** Beyond this distance from an intersection's reference point, no approach is reported. */
    private const val APPROACH_DISTANCE_METERS = 150.0

    /**
     * Maximum allowed difference between the device's heading and the lane's direction of travel.
     * Wide enough to tolerate consumer-GNSS heading noise and a curving approach, tight enough to
     * reject the opposing arm, whose bearing differs by 180°.
     */
    private const val HEADING_TOLERANCE_DEG = 25.0

    /**
     * How far off a lane's centreline a device may be, as a multiple of the lane's own width.
     * At 1.0, a position must fall within roughly one lane width of the centreline, which is
     * about the limit of what GNSS accuracy can resolve between adjacent lanes anyway.
     */
    private const val SEGMENT_WIDTH_MULTIPLIER = 1.00

    /** Speed above which the GNSS-reported bearing is trusted as the direction of travel. */
    private const val MOVING_SPEED_THRESHOLD_MPS = 2.0f

    /**
     * Minimum displacement between two fixes before their difference is used to infer a heading.
     * Shorter baselines are indistinguishable from GNSS jitter.
     */
    private const val MIN_BASELINE_METERS = 10.0

    /** Flat-earth conversion factor; accurate enough over the span of one intersection. */
    private const val METERS_PER_DEGREE_LAT = 111320.0

    /**
     * Given the current GPS fix, the recent location history, and all loaded intersection maps,
     * returns the intersection + arm the device is approaching, or null if none match.
     *
     * Selection is two-stage:
     *   1. Pick the single closest map by haversine distance to refPoint; gate at
     *      APPROACH_DISTANCE_METERS.
     *   2. Within that map, find the ingress-lane segment nearest to the user (point-to-segment
     *      distance, clamped to endpoints). Accept iff perp distance ≤ SEGMENT_WIDTH_MULTIPLIER ×
     *      node[i].widthM AND |heading − segmentBearing| ≤ HEADING_TOLERANCE_DEG.
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

        // Stage 1: closest map by refPoint, distance gate.
        val map = maps.minByOrNull {
            haversineDistanceMeters(
                currentLocation.latitude, currentLocation.longitude, it.refPoint.lat, it.refPoint.lon
            )
        } ?: return null
        val refDist = haversineDistanceMeters(
            currentLocation.latitude, currentLocation.longitude, map.refPoint.lat, map.refPoint.lon
        )
        if (refDist > APPROACH_DISTANCE_METERS) {
            println("INFO: ApproachDetector skip intersection=${map.name} refDist=%.1fm > threshold".format(refDist))
            return null
        }

        // User position in the intersection's local east/north frame (metres from refPoint).
        val refLatRad = map.refPoint.lat * PI / 180.0
        val userE = (currentLocation.longitude - map.refPoint.lon) * cos(refLatRad) * METERS_PER_DEGREE_LAT
        val userN = (currentLocation.latitude - map.refPoint.lat) * METERS_PER_DEGREE_LAT

        // Stage 2: nearest ingress segment across all arms.
        var bestDist = Double.MAX_VALUE
        var bestArm: MapArm? = null
        var bestWidth = 0.0
        var bestBearing = 0.0
        var bestLaneId = -1
        var bestSegIdx = -1
        for (arm in map.arms) {
            for (lane in arm.ingressLanes) {
                if (lane.nodes.size < 2) continue
                for (i in 0 until lane.nodes.size - 1) {
                    val a = lane.nodes[i]
                    val b = lane.nodes[i + 1]
                    val perpDist = pointToSegmentDistance(userE, userN, a.offsetX, a.offsetY, b.offsetX, b.offsetY)
                    if (perpDist < bestDist) {
                        bestDist = perpDist
                        bestArm = arm
                        bestWidth = a.widthM
                        bestBearing = segmentBearingDeg(a, b)
                        bestLaneId = lane.laneID
                        bestSegIdx = i
                    }
                }
            }
        }

        val arm = bestArm ?: run {
            println("INFO: ApproachDetector intersection=${map.name} no ingress segments found")
            return null
        }

        val widthThreshold = SEGMENT_WIDTH_MULTIPLIER * bestWidth
        val angDiff = angleDifferenceDeg(heading, bestBearing)
        val widthOk = bestDist <= widthThreshold
        val angleOk = angDiff <= HEADING_TOLERANCE_DEG
        println("INFO: ApproachDetector intersection=${map.name} armId=${arm.armId} laneId=$bestLaneId segIdx=$bestSegIdx perpDist=%.2fm width=%.2fm threshold=%.2fm bearing=%.1f° diff=%.1f° %s".format(
            bestDist, bestWidth, widthThreshold, bestBearing, angDiff,
            if (widthOk && angleOk) "[ACCEPT]"
            else "[REJECT: ${if (!widthOk) "dist>${SEGMENT_WIDTH_MULTIPLIER}×width" else "diff>$HEADING_TOLERANCE_DEG"}]"
        ))
        if (!widthOk || !angleOk) return null

        return ApproachResult(map, arm)
    }

    /** Perpendicular distance from point P to segment AB, clamped to the segment endpoints. */
    private fun pointToSegmentDistance(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        val ex = px - cx
        val ey = py - cy
        return sqrt(ex * ex + ey * ey)
    }

    /**
     * Direction of travel along the segment (a, b), where a is the node closer to the stop bar
     * (lower index in the lane's node list) and b is farther upstream. A driver approaching the
     * intersection travels from b toward a, so the bearing vector is (a − b).
     * Returned in degrees clockwise from north, in [0, 360).
     */
    private fun segmentBearingDeg(a: MapLaneNode, b: MapLaneNode): Double {
        val dx = a.offsetX - b.offsetX
        val dy = a.offsetY - b.offsetY
        return (atan2(dx, dy) * (180.0 / PI) + 360.0) % 360.0
    }

    /**
     * For the given arm and a SPaT intersection snapshot (from either the regular or the
     * critical SPaT stream), returns the current signal color for straight-ahead and
     * left-turn movements.
     */
    fun extractArmSignals(arm: MapArm, intersection: SpatIntersection): ArmSignalState {
        val statesByGroup = intersection.states.associateBy { it.signalGroup }

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
