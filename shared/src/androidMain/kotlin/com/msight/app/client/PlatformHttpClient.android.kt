package com.msight.app.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android engine: OkHttp, with connection-failure retries enabled.
 *
 * Retries matter on mobile — a request issued as the radio switches networks fails on a stale
 * connection that OkHttp can simply re-establish, and the alternative here is a dropped location
 * update.
 */
actual fun createPlatformHttpClient(
    configure: HttpClientConfig<*>.() -> Unit
): HttpClient {
    return HttpClient(OkHttp) {
        engine {
            config {
                retryOnConnectionFailure(true)
            }
        }
        configure()
    }
}