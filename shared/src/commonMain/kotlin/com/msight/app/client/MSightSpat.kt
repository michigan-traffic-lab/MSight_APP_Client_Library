package com.msight.app.client

import kotlinx.serialization.Serializable

/**
 * SAE J2735 SPaT (Signal Phase and Timing) data classes.
 *
 * These mirror the standard message structure as received from MSight Cloud, which decodes the
 * signal controller's SPaT and pushes it to nearby clients. Field names follow the J2735
 * spelling rather than Kotlin convention so they line up with the standard and the cloud payload.
 *
 * A SPaT says, for each *signal group* at one intersection, what the current phase is and when
 * it is expected to end. Mapping a signal group to a movement a driver can see (straight ahead,
 * left turn) requires the intersection's MAP message — see [MSightIntersectionMap] and
 * [MSightApproachDetector.extractArmSignals].
 */

/** Intersection identifier: intersection [id], optionally scoped by a regional [region]. */
@Serializable
data class SpatIntersectionId(
    val id: Int,
    val region: Int? = null
)

/**
 * Timing window for a single movement event state.
 * All times are in units of 1/10 second relative to the current minute-of-year.
 */
@Serializable
data class SpatTiming(
    val startTime: Int? = null,
    val minEndTime: Int,
    val maxEndTime: Int? = null,
    val likelyTime: Int? = null,
    val confidence: Int? = null,
    val nextTime: Int? = null
)

/**
 * A single state-time-speed entry: the current or predicted phase state + timing.
 *
 * @property eventState J2735 movement phase state, e.g. `protected-Movement-Allowed`,
 *   `stop-And-Remain`. Map it to a displayable colour with [SignalColor.fromEventState].
 */
@Serializable
data class SpatStateTimeSpeed(
    val eventState: String,
    val timing: SpatTiming? = null
)

/**
 * One signal group's current movement state list.
 *
 * The first entry of [stateTimeSpeed] is the state in effect now; any further entries are
 * predictions for upcoming phases.
 */
data class SpatMovementState(
    val signalGroup: Int,
    val stateTimeSpeed: List<SpatStateTimeSpeed>
)

/**
 * A single intersection within the SPAT message.
 *
 * @property name Intersection name as configured in MSight Cloud. This is the key that ties a
 *   SPaT to the [MSightIntersectionMap] with the same name.
 */
data class SpatIntersection(
    val name: String?,
    val id: SpatIntersectionId,
    val revision: Int,
    /** Bit-string status flags as a list of set-bit positions. */
    val status: List<Int>,
    /** Minute of Year (UTC). */
    val moy: Int?,
    /** 1/10-second offset within the current minute. */
    val timeStamp: Int?,
    /** Current phase state of every signal group this intersection reports. */
    val states: List<SpatMovementState>
)
