package com.msight.app.client

import kotlinx.serialization.Serializable

/**
 * SAE J2735 SDSM (Sensor Data Sharing Message) data classes.
 *
 * These mirror the decoded SDSM structure that MSight Cloud pushes over the WebSocket stream:
 * the roadside device transmits ASN.1-encoded SDSM, the cloud decodes it, and the client receives
 * the result as JSON. Field names therefore follow the J2735 spelling rather than Kotlin
 * convention, so that they line up with the standard and with the cloud payload.
 *
 * An SDSM describes what one roadside sensor saw in one frame: a reference position
 * ([SdsmRefPos]) plus a list of detected objects ([SdsmDetectedObject]) whose positions are
 * given as offsets from that reference.
 */

/** Reference position of the transmitting sensor, in WGS 84 decimal degrees. */
@Serializable
data class SdsmRefPos(
    val lat: Double,
    /** Longitude. Named `long` to match the J2735 field name in the cloud payload. */
    val long: Double
)

/**
 * A detected object's position relative to the message's reference position, in metres.
 *
 * **The axes are reversed relative to J2735's usual convention:** here `offsetX` is metres
 * *north* and `offsetY` is metres *east*, the opposite way round from [MapLaneNode] and from the
 * east/north ordering the standard otherwise uses. This is a known quirk of the SDSM stream, not
 * a parsing error — the library passes the values through unchanged, and consumers must account
 * for it when converting to coordinates.
 *
 * See `computeObjectLatLon` in the `androidapp` example for a conversion that gets this right.
 * Swapping the pair puts every detection at right angles to where it actually is.
 */
@Serializable
data class SdsmOffset(
    val offsetX: Double,
    val offsetY: Double
)

/** J2735 confidence enumerations for a detected object's position and elevation. */
@Serializable
data class SdsmPosConfidence(
    val pos: String,
    val elevation: String
)

/**
 * The sensor's own timestamp for the frame, as carried in the SDSM's `sDSMTimeStamp`.
 *
 * This is the authoritative frame time: it is set at the sensor, whereas the cloud's
 * `capture_timestamp` and `creation_timestamp` are stamped further downstream and lag it by a
 * few hundred milliseconds.
 *
 * @property second Seconds within the minute, fractional (J2735 encodes milliseconds here).
 * @property offset Minutes east of UTC for the sensor's local time.
 */
@Serializable
data class SdsmTimestamp(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Double,
    val offset: Int
)

/**
 * Positional accuracy of the reference position, as a confidence ellipse.
 *
 * @property semiMajor Semi-major axis in metres.
 * @property semiMinor Semi-minor axis in metres.
 * @property orientation Orientation of the semi-major axis in degrees from north.
 */
@Serializable
data class SdsmRefPosConf(
    val semiMajor: Double,
    val semiMinor: Double,
    val orientation: Double
)

/** Bounding-box dimensions of a detected vehicle, in metres. */
@Serializable
data class SdsmVehicleSize(
    val width: Double,
    val length: Double
)

/**
 * One object detected by the roadside sensor in this frame.
 *
 * @property objectType J2735 object type, e.g. `vehicle`, `vru`, `unknown`.
 * @property objTypeCfd Confidence in [objectType], 0–100.
 * @property objectID Sensor-assigned track id; stable across frames while the track lives.
 * @property measurementTime Offset in milliseconds from the message timestamp to the moment this
 *   object was actually measured. Negative values mean the measurement precedes the message time.
 * @property pos Position as an offset from the message's reference position.
 * @property speed Ground speed in metres per second.
 * @property heading Direction of travel in degrees clockwise from north.
 * @property vehicleSize Bounding box, present only for vehicle detections.
 * @property vehicleClass J2735 vehicle classification code, present only for vehicle detections.
 */
data class SdsmDetectedObject(
    val objectType: String,
    val objTypeCfd: Int,
    val objectID: Int,
    val measurementTime: Double,
    val timeConfidence: String,
    val pos: SdsmOffset,
    val posConfidence: SdsmPosConfidence,
    val speed: Double,
    val speedConfidence: String,
    val heading: Double,
    val headingConf: String,
    val vehicleSize: SdsmVehicleSize?,
    val vehicleClass: Int?
)
