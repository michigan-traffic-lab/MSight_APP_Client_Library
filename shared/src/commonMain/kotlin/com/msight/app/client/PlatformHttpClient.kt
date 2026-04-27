package com.msight.app.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

expect fun createPlatformHttpClient(
    configure: HttpClientConfig<*>.() -> Unit = {}
): HttpClient