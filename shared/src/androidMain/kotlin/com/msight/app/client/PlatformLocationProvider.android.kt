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

actual typealias PlatformContext = Context

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

        fusedClient.lastLocation.addOnSuccessListener { location ->
            location?.let { callback?.invoke(it.toLocationEvent()) }
        }
    }

    actual fun stop() {
        fusedClient.removeLocationUpdates(locationCallback)
        callback = null
    }

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