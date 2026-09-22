package com.msight.app.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * Creates the Ktor [HttpClient] used for every MSight Cloud call — REST requests and the
 * WebSocket stream alike.
 *
 * Each platform supplies its own engine (OkHttp on Android, CIO on the JVM) because Ktor has no
 * single engine that works everywhere; [configure] carries the engine-independent setup the
 * library needs, such as installing the WebSockets plugin.
 *
 * Porting the library to a new Kotlin Multiplatform target starts here: provide an `actual` for
 * this function with an engine that target supports (Darwin for iOS/macOS, Js for browser and
 * Node, WinHttp/Curl for the native desktop targets).
 */
expect fun createPlatformHttpClient(
    configure: HttpClientConfig<*>.() -> Unit = {}
): HttpClient
