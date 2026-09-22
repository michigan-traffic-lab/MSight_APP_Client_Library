package com.msight.app.client

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The platform handle a location provider needs in order to be constructed.
 *
 * On Android this is a `typealias` for `android.content.Context`; on targets with no such
 * concept it is an empty abstract class that callers subclass with a singleton object. Declaring
 * it as an `expect abstract class` is what lets `MSightClient`'s constructor stay in common code.
 */
expect abstract class PlatformContext

/**
 * Per-platform source of GNSS fixes.
 *
 * Implementations push every fix the OS reports; rate limiting against
 * [MSightClientConfig.locationUpdateFrequencyHz] happens upstream in [MSightClient], so an
 * implementation should request the highest accuracy and rate the platform will give it.
 *
 * Acquiring location permission is the host application's responsibility — [start] assumes it
 * has already been granted.
 */
expect class PlatformLocationProvider(context: PlatformContext) {
    /** Begins delivering fixes to [onLocation]. Calling this twice without [stop] is a no-op. */
    fun start(onLocation: (MSightLocationEvent) -> Unit)

    /** Stops delivery and releases the underlying OS subscription. */
    fun stop()
}

/**
 * A thin [SharedFlow] wrapper around [PlatformLocationProvider], for hosts that want the device's
 * own position stream without running a full [MSightClient].
 *
 * Fixes are not rate limited and nothing is sent to MSight Cloud. Emission uses `tryEmit`, so a
 * slow collector drops fixes rather than blocking the location callback.
 */
class LocationEmitter(
    context: PlatformContext
) {
    private val provider = PlatformLocationProvider(context)

    private val _events = MutableSharedFlow<MSightLocationEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    val events: SharedFlow<MSightLocationEvent> = _events.asSharedFlow()

    fun start() {
        provider.start { event ->
            _events.tryEmit(event)
        }
    }

    fun stop() {
        provider.stop()
    }
}
