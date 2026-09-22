package com.msight.app.client

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll

/**
 * A standalone warning channel a host can push into, independent of any cloud connection.
 *
 * Its purpose is development and testing: warning UI — banners, sounds, overlay geometry — needs
 * to be exercised without waiting for a real conflict to occur on a real road. Construct one,
 * collect [events], and drive it from a debug button.
 *
 * It is not wired into [MSightClient]; cloud-delivered warnings arrive on
 * [MSightClient.events] instead.
 */
class WarningEmitter {
    private val _events = MutableSharedFlow<MSightWarningEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    val events: SharedFlow<MSightWarningEvent> = _events.asSharedFlow()

    fun emit(event: MSightWarningEvent): Boolean {
        return _events.tryEmit(event)
    }

    fun emitFakeTwoVehicleConflict(timestampMillis: Long): Boolean {
        return emit(MSightFakeWarnings.twoVehicleConflict(timestampMillis))
    }

    fun emitFakeVehicleVRUConflict(timestampMillis: Long): Boolean {
        return emit(MSightFakeWarnings.vehicleVRUConflict(timestampMillis))
    }
}

/**
 * Canned conflict warnings with plausible geometry, for exercising warning UI offline.
 *
 * Coordinates are fixed sample values, so the resulting geometry will not sit anywhere near the
 * device's real position — these are for checking that a warning renders, not where it renders.
 */
object MSightFakeWarnings {
    fun twoVehicleConflict(timestampMillis: Long): MSightTwoVehicleConflictEvent {
        return MSightTwoVehicleConflictEvent(
            timestampMillis = timestampMillis,
            ttcSeconds = 2.4,
            pretSeconds = 1.2,
            firstVehicleTrajectory = vehicleTrajectory(
                timestampMillis = timestampMillis,
                startLatitude = 37.4219999,
                startLongitude = -122.0840575,
                speedMps = 12.5f,
                headingDegrees = 90f
            ),
            secondVehicleTrajectory = vehicleTrajectory(
                timestampMillis = timestampMillis,
                startLatitude = 37.42235,
                startLongitude = -122.08355,
                speedMps = 10.2f,
                headingDegrees = 180f
            ),
            firstVehiclePredictedTrajectory = MSightPredictedTrajectory(
                headingDegrees = 92f,
                speedMps = 12.7f,
                curvature = 0.03f
            ),
            secondVehiclePredictedTrajectory = MSightPredictedTrajectory(
                headingDegrees = 182f,
                speedMps = 10.0f,
                curvature = -0.02f
            )
        )
    }

    fun vehicleVRUConflict(timestampMillis: Long): MSightVehicleVRUConflictEvent {
        return MSightVehicleVRUConflictEvent(
            timestampMillis = timestampMillis,
            ttcSeconds = 3.1,
            pretSeconds = 1.7,
            vehicleTrajectory = vehicleTrajectory(
                timestampMillis = timestampMillis,
                startLatitude = 37.4221,
                startLongitude = -122.0841,
                speedMps = 11.8f,
                headingDegrees = 45f
            ),
            vruTrajectory = vruTrajectory(
                timestampMillis = timestampMillis,
                startLatitude = 37.42242,
                startLongitude = -122.08382,
                speedMps = 1.6f,
                headingDegrees = 270f
            ),
            vehiclePredictedTrajectory = MSightPredictedTrajectory(
                headingDegrees = 47f,
                speedMps = 12.1f,
                curvature = 0.01f
            ),
            vruPredictedTrajectory = MSightPredictedTrajectory(
                headingDegrees = 268f,
                speedMps = 1.4f,
                curvature = 0f
            )
        )
    }

    private fun vehicleTrajectory(
        timestampMillis: Long,
        startLatitude: Double,
        startLongitude: Double,
        speedMps: Float,
        headingDegrees: Float
    ): MSightTrajectory {
        return MSightTrajectory(
            points = listOf(
                MSightTrajectoryPoint(
                    timestampMillis = timestampMillis - 2000,
                    latitude = startLatitude - 0.00018,
                    longitude = startLongitude - 0.00018,
                    speedMps = speedMps - 0.8f,
                    headingDegrees = headingDegrees
                ),
                MSightTrajectoryPoint(
                    timestampMillis = timestampMillis - 1000,
                    latitude = startLatitude - 0.00008,
                    longitude = startLongitude - 0.00008,
                    speedMps = speedMps - 0.3f,
                    headingDegrees = headingDegrees
                ),
                MSightTrajectoryPoint(
                    timestampMillis = timestampMillis,
                    latitude = startLatitude,
                    longitude = startLongitude,
                    speedMps = speedMps,
                    headingDegrees = headingDegrees
                )
            )
        )
    }

    private fun vruTrajectory(
        timestampMillis: Long,
        startLatitude: Double,
        startLongitude: Double,
        speedMps: Float,
        headingDegrees: Float
    ): MSightTrajectory {
        return MSightTrajectory(
            points = listOf(
                MSightTrajectoryPoint(
                    timestampMillis = timestampMillis - 2000,
                    latitude = startLatitude,
                    longitude = startLongitude + 0.00004,
                    speedMps = speedMps,
                    headingDegrees = headingDegrees
                ),
                MSightTrajectoryPoint(
                    timestampMillis = timestampMillis - 1000,
                    latitude = startLatitude,
                    longitude = startLongitude + 0.00002,
                    speedMps = speedMps,
                    headingDegrees = headingDegrees
                ),
                MSightTrajectoryPoint(
                    timestampMillis = timestampMillis,
                    latitude = startLatitude,
                    longitude = startLongitude,
                    speedMps = speedMps,
                    headingDegrees = headingDegrees
                )
            )
        )
    }
}