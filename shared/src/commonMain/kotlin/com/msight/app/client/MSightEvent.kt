package com.msight.app.client

/**
 * Base type for everything [MSightClient.events] emits.
 *
 * The flow is deliberately a single stream of one sealed hierarchy: a host application collects
 * it once and dispatches on type, rather than subscribing to a channel per message kind. Events
 * come from three places — the device's own sensors ([MSightLocationEvent]), MSight Cloud's
 * WebSocket push ([MSightSdsmEvent], [MSightSpatEvent], [MSightCriticalSpatEvent],
 * [MSightSimpleWarning]), and the library's own processing ([MSightSignalStateEvent]).
 *
 * @property timestampMillis When the event happened, Unix epoch milliseconds (UTC). For cloud
 *   messages this is the upstream sensor's own time where available, not the time of receipt, so
 *   events can be ordered and aged consistently.
 * @property eventId The cloud's per-broadcast identifier, when the event arrived over the
 *   WebSocket. Useful for de-duplication and for correlating a client-side log with cloud logs;
 *   null for locally generated events.
 */
sealed class MSightEvent(
    open val timestampMillis: Long,
    open val eventId: String? = null
)

/**
 * A GNSS fix for the host device, delivered after rate limiting to
 * [MSightClientConfig.locationUpdateFrequencyHz].
 *
 * Emitting these is a side effect of the upload the client performs anyway, so a host can drive
 * its own map view from this flow instead of subscribing to platform location APIs separately.
 *
 * @property provider Platform name of the location source, e.g. `fused`, `gps`, `network`.
 */
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

/**
 * Base type for safety messages addressed to this device — as opposed to the raw sensor and
 * signal streams, which describe the world rather than warn about it.
 */
sealed class MSightWarningEvent(
    override val timestampMillis: Long
) : MSightEvent(timestampMillis)

/**
 * A free-text warning broadcast to clients within a radius, typically by a cloud microservice
 * that detected a hazard.
 *
 * This is the lowest-common-denominator warning shape: it carries no geometry, so a host can
 * surface it without understanding what produced it.
 */
data class MSightSimpleWarning(
    override val timestampMillis: Long,
    override val eventId: String? = null,
    val message: String
) : MSightWarningEvent(timestampMillis)

/**
 * One decoded SAE J2735 SDSM frame from a roadside sensor near this device: everything that
 * sensor saw at one instant.
 *
 * Object positions in [objects] are metre offsets from [refPos], not absolute coordinates — see
 * [SdsmDetectedObject.pos].
 *
 * @property sensorName MSight Cloud's logical name for the stream this frame came from.
 * @property deviceName Roadside device hosting that sensor.
 * @property captureTimestamp Edge-server capture time, Unix epoch seconds (fractional).
 * @property creationTimestamp Edge-server message-creation time, Unix epoch seconds (fractional).
 * @property frameId Sensor's frame identifier.
 * @property msgCnt J2735 message counter, 0–127, wraps.
 * @property sdsmTimestamp The sensor's own frame time. Preferred over [captureTimestamp] for
 *   frame identity, since the edge stamps arrive a few hundred milliseconds later.
 * @property refPos Reference position that [objects] offsets are measured from.
 */
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

/**
 * A routine SAE J2735 SPaT update for a nearby intersection.
 *
 * MSight Cloud rate-limits this stream to roughly 2 Hz. It is the steady-state source of signal
 * state; [MSightCriticalSpatEvent] exists to beat it to the punch on a phase change.
 *
 * Most applications should consume [MSightSignalStateEvent] instead, which resolves these
 * messages against intersection geometry into the colour facing the driver.
 */
data class MSightSpatEvent(
    override val timestampMillis: Long,
    override val eventId: String? = null,
    val sensorName: String,
    val deviceName: String,
    val captureTimestamp: Double,
    val creationTimestamp: Double,
    val frameId: String,
    /** Optional human-readable intersection name (from message-level field). */
    val intersectionName: String?,
    /** Message count (0–127, wraps). */
    val msgCnt: Int,
    /** Optional name from inside the spat object. */
    val name: String?,
    /** The single intersection carried in this SPAT message. */
    val intersection: SpatIntersection
) : MSightEvent(timestampMillis)

/**
 * A low-latency SPaT update, pushed by MSight Cloud only when the phase actually changed.
 *
 * It carries the same [SpatIntersection] payload as [MSightSpatEvent] but travels a shorter
 * path, so it lands before the rate-limited regular stream reports the same change. A consumer
 * that renders both must expect the regular stream to trail it with a few stale frames still
 * carrying the *old* colour; the library's own signal processor suppresses those to stop the
 * display flickering back.
 */
data class MSightCriticalSpatEvent(
    override val timestampMillis: Long,
    override val eventId: String? = null,
    val sensorName: String,
    val deviceName: String,
    val captureTimestamp: Double,
    val creationTimestamp: Double,
    /** Optional human-readable intersection name (from message-level field). */
    val intersectionName: String?,
    /** Optional name from inside the spat object. */
    val name: String?,
    /** The single intersection carried in this critical SPAT message. */
    val intersection: SpatIntersection
) : MSightEvent(timestampMillis)

/**
 * Base type for warnings about a predicted collision between two road users.
 *
 * @property ttcSeconds Time to collision in seconds, or null when not computed.
 * @property pretSeconds Post-encroachment / required-evasion time in seconds, or null when not
 *   computed.
 */
sealed class MSightConflictWarningEvent(
    override val timestampMillis: Long,
    open val ttcSeconds: Double?,
    open val pretSeconds: Double?
) : MSightWarningEvent(timestampMillis)

/**
 * A predicted vehicle-to-vehicle conflict, with both participants' observed and predicted
 * motion so a host can draw the geometry rather than only announce it.
 *
 * Note: no cloud microservice currently emits this shape over the WebSocket. It is part of the
 * event contract and is produced today by [MSightFakeWarnings] for UI development.
 */
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

/**
 * A predicted conflict between a vehicle and a vulnerable road user (pedestrian, cyclist).
 *
 * Note: as with [MSightTwoVehicleConflictEvent], this is part of the event contract rather than a
 * shape the cloud emits today; [MSightFakeWarnings] produces it for UI development.
 */
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

/**
 * The signal state facing this device right now — the library's own derived output, and the event
 * a driver-facing application should render.
 *
 * Producing it takes three inputs the client already holds: the device's position and heading, the
 * intersection MAP geometry, and the latest SPaT. The client matches the device to an intersection
 * arm, looks up that arm's signal groups, and reports the resulting colours. Enable it with
 * [MSightClient.setSpatEnabled].
 *
 * An event with a null [intersectionName] means "no longer approaching anything" and is the
 * signal to take any signal display down.
 */
data class MSightSignalStateEvent(
    override val timestampMillis: Long,
    override val eventId: String? = null,
    /** null when the device is not approaching any known intersection */
    val intersectionName: String?,
    val straightColor: SignalColor,
    val leftTurnColor: SignalColor,
    /** true when the arm has exactly one total signal group covering all movements */
    val showSingleLight: Boolean = false,
    /** Signal group IDs for the straight movement on the matched arm (empty when no arm matched) */
    val straightSignalGroupIds: List<Int> = emptyList(),
    /** Signal group IDs for the left-turn movement on the matched arm (empty when no arm matched) */
    val leftTurnSignalGroupIds: List<Int> = emptyList()
) : MSightEvent(timestampMillis)
