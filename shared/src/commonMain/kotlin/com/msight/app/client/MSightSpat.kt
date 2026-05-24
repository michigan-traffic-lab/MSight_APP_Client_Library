package com.msight.app.client

import kotlinx.serialization.Serializable

/**
 * J2735 SPAT (Signal Phase and Timing) data classes.
 * Mirror the standard message structure as received from the MSight server.
 */

/** Intersection identifier: regional ID + intersection ID. */
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

/** A single state-time-speed entry: the current or predicted phase state + timing. */
@Serializable
data class SpatStateTimeSpeed(
    val eventState: String,
    val timing: SpatTiming? = null
)

/** One signal group's current movement state list. */
data class SpatMovementState(
    val signalGroup: Int,
    val stateTimeSpeed: List<SpatStateTimeSpeed>
)

/** A single intersection within the SPAT message. */
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
    val states: List<SpatMovementState>
)
