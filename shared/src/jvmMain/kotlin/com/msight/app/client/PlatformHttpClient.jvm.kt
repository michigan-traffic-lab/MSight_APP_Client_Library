package com.msight.app.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO

/**
 * JVM engine: Ktor's own CIO, which needs no native dependency and supports WebSockets — enough
 * for the desktop smoke test this target exists for.
 */
actual fun createPlatformHttpClient(
    configure: HttpClientConfig<*>.() -> Unit
): HttpClient {
    return HttpClient(CIO) {
        configure()
    }
}