package com.msight.app.client

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** On Android the platform handle is just a `Context`. */
actual typealias PlatformContext = Context

/**
 * Android location provider, backed by Google Play services' fused location provider rather than
 * the framework's `LocationManager`.
 *
 * Fused is the right choice here because it blends GNSS with sensor and network sources, which
 * keeps fixes coming through the urban-canyon and tunnel conditions that intersection
 * applications actually run in. The cost is a dependency on Play services, so a device without
 * it — many IVI head units, for instance — will need a `LocationManager`-based variant of this
 * class.
 *
 * Fixes are requested at the highest accuracy and with no minimum interval; rate limiting is
 * [MSightClient]'s job. The application `Context` is retained rather than the one passed in, so
 * holding this object cannot leak an `Activity`.
 *
 * The caller must hold `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION` before calling
 * [start] — hence the `MissingPermission` suppression, which asserts that contract rather than
 * ignoring it.
 */
actual class PlatformLocationProvider actual constructor(
    context: PlatformContext
) {
    private val appContext = context.applicationContext
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)

    private var callback: ((MSightLocationEvent) -> Unit)? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                callback?.invoke(location.toLocationEvent())
            }
        }
    }

    @SuppressLint("MissingPermission")
    actual fun start(onLocation: (MSightLocationEvent) -> Unit) {
        callback = onLocation

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            0L
        ).setMinUpdateIntervalMillis(0L)
            .build()

        fusedClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )

        // Deliver the cached last-known fix immediately, so the client can register a position
        // with the cloud without waiting for the first live fix — which can take seconds from a
        // cold GNSS start, during which the device would receive nothing.
        fusedClient.lastLocation.addOnSuccessListener { location ->
            location?.let { callback?.invoke(it.toLocationEvent()) }
        }
    }

    actual fun stop() {
        fusedClient.removeLocationUpdates(locationCallback)
        callback = null
    }

    /**
     * Converts a platform [Location] to the library's own event type.
     *
     * Each optional field is gated on its `has…()` check, because Android reports `0` for an
     * absent speed or bearing rather than nothing — and a false zero speed would make the signal
     * processor treat a moving vehicle as stopped.
     */
    private fun Location.toLocationEvent(): MSightLocationEvent {
        return MSightLocationEvent(
            timestampMillis = time,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = if (hasAltitude()) altitude else null,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            speedMps = if (hasSpeed()) speed else null,
            bearingDegrees = if (hasBearing()) bearing else null,
            provider = provider ?: "fused"
        )
    }
}