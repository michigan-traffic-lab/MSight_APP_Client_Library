package com.msight.app.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

actual abstract class PlatformContext

actual class PlatformLocationProvider actual constructor(
    context: PlatformContext
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var locationJob: Job? = null

    actual fun start(onLocation: (MSightLocationEvent) -> Unit) {
        if (locationJob != null) {
            return
        }

        locationJob = scope.launch {
            var latitude = 42.2808
            var longitude = -83.7430

            while (isActive) {
                onLocation(
                    MSightLocationEvent(
                        timestampMillis = System.currentTimeMillis(),
                        latitude = latitude,
                        longitude = longitude,
                        altitudeMeters = 256.0,
                        accuracyMeters = 3.5f,
                        speedMps = 12.1f,
                        bearingDegrees = 87.2f,
                        provider = "gps"
                    )
                )

                latitude += 0.0001
                longitude += 0.0001
                delay(250L)
            }
        }
    }

    actual fun stop() {
        locationJob?.cancel()
        locationJob = null
    }
}