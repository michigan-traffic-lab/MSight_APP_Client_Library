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
            var latitude = 42.302615
            var longitude = -83.704366

            while (isActive) {
                // println(
                //     "INFO: JVM location update lat=$latitude, lon=$longitude, altitude=256.0, accuracy=3.5"
                // )

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

                // latitude += 0.0001
                // longitude += 0.0001
                delay(250L)
            }
        }
    }

    actual fun stop() {
        locationJob?.cancel()
        locationJob = null
    }
}