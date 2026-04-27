package com.msight.app.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

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