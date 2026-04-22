package com.msight.app.client

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

expect abstract class PlatformContext

expect class PlatformLocationProvider(context: PlatformContext) {
    fun start(onLocation: (MSightLocationEvent) -> Unit)
    fun stop()
}

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
