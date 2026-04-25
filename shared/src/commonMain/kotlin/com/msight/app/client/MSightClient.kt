package com.msight.app.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.math.roundToLong

enum class MSightRoadUserType {
    VEHICLE,
    VRU,
    OTHER
}

enum class MSightDeviceType {
    CELLPHONE,
    TABLET,
    IVI,
    LOW_POWER_DEVICE,
    OTHER
}

data class MSightClientConfig(
    val cloudUrl: String,
    val appId: String,
    val clientId: String,
    val roadUserType: MSightRoadUserType,
    val roadUserSubType: String,
    val deviceType: MSightDeviceType,
    val locationUpdateFrequencyHz: Double = 1.0
)

class MSightClient(
    private val context: PlatformContext,
    val config: MSightClientConfig
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient = HttpClient {
        install(WebSockets)
    }

    private var locationUploader: MSightLocationUploader? = null
    private var locationUpdateEmitter: MSightLocationUpdateEmitter? = null
    private var webSocketConnection: MSightWebSocketConnection? = null

    init {
        runBlocking {
            initialize()
        }
    }

    suspend fun initialize() {
        stop()

        val initializedLocationUploader = MSightLocationUploader(
            config = config,
            client = httpClient,
            scope = scope
        )

        locationUploader = initializedLocationUploader
        locationUpdateEmitter = MSightLocationUpdateEmitter(
            context = context,
            updateFrequencyHz = config.locationUpdateFrequencyHz,
            onLocation = initializedLocationUploader::upload
        )
        webSocketConnection = MSightWebSocketConnection(
            config = config,
            client = httpClient,
            scope = scope
        )

        try {
            webSocketConnection?.initialize()
        } catch (throwable: Throwable) {
            logError(
                "MSightClient failed to initialize websocket connection: ${describeThrowable(throwable)}"
            )
            stop()
            throw throwable
        }
    }

    fun start() {
        locationUpdateEmitter?.start()
    }

    fun stop() {
        locationUpdateEmitter?.stop()
        webSocketConnection?.close()
        locationUploader?.close()
        locationUpdateEmitter = null
        webSocketConnection = null
        locationUploader = null
    }

    fun close() {
        stop()
        scope.cancel()
        httpClient.close()
    }
}

private class MSightLocationUpdateEmitter(
    context: PlatformContext,
    updateFrequencyHz: Double,
    private val onLocation: (MSightLocationEvent) -> Unit
) {
    private val provider = PlatformLocationProvider(context)
    private val minUpdateIntervalMillis = updateFrequencyHz.toMinUpdateIntervalMillis()
    private var lastDeliveredTimestampMillis: Long? = null

    fun start() {
        provider.start { locationEvent ->
            if (shouldDeliver(locationEvent.timestampMillis)) {
                lastDeliveredTimestampMillis = locationEvent.timestampMillis
                onLocation(locationEvent)
            }
        }
    }

    fun stop() {
        provider.stop()
        lastDeliveredTimestampMillis = null
    }

    private fun shouldDeliver(timestampMillis: Long): Boolean {
        val previousTimestampMillis = lastDeliveredTimestampMillis ?: return true
        return timestampMillis - previousTimestampMillis >= minUpdateIntervalMillis
    }
}

private class MSightLocationUploader(
    private val config: MSightClientConfig,
    private val client: HttpClient,
    private val scope: CoroutineScope
) {
    private val locationUpdateUrl = config.cloudUrl.trimEnd('/') + LOCATION_UPDATE_PATH

    fun upload(locationEvent: MSightLocationEvent) {
        scope.launch {
            runCatching {
                val response = client.post(locationUpdateUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        TextContent(
                            buildLocationUpdatePayload(config, locationEvent),
                            ContentType.Application.Json
                        )
                    )
                }

                logInfo(
                    "MSightLocationUploader response: status=${response.status.value}, body=${response.bodyAsText()}"
                )
            }
        }
    }

    fun close() {
    }
}

private class MSightWebSocketConnection(
    private val config: MSightClientConfig,
    private val client: HttpClient,
    private val scope: CoroutineScope
) {
    enum class State {
        NO_URL_ASSIGNED,
        URL_ASSIGNED,
        CONNECTED,
        DISCONNECTED,
        CONNECTION_FAILED
    }

    private var lifecycleJob: Job? = null
    private val websocketUrlEndpoint = config.cloudUrl.trimEnd('/') + WEBSOCKET_URL_PATH
    private var websocketUrl: String? = null
    private var state: State = State.NO_URL_ASSIGNED

    suspend fun initialize() {
        if (state == State.CONNECTED) {
            return
        }

        if (lifecycleJob != null) {
            error("MSightWebSocketConnection initialization already in progress; state=$state")
        }

        val connectionReady = CompletableDeferred<Unit>()

        lifecycleJob = scope.launch {
            runCatching {
                val resolvedWebSocketUrl = fetchWebSocketUrl()
                websocketUrl = resolvedWebSocketUrl
                state = State.URL_ASSIGNED
                logInfo("MSightWebSocketConnection obtained websocket url: $resolvedWebSocketUrl, state=$state")
                connectInternal(resolvedWebSocketUrl, connectionReady)
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    state = State.DISCONNECTED
                    connectionReady.cancel(throwable)
                    return@onFailure
                }

                state = State.CONNECTION_FAILED
                logError(
                    "MSightWebSocketConnection initialization failed: state=$state, error=${describeThrowable(throwable)}"
                )
                connectionReady.completeExceptionally(throwable)
            }.also {
                lifecycleJob = null
            }
        }

        connectionReady.await()
    }

    fun connect() {
        val resolvedWebSocketUrl = websocketUrl
            ?: error("MSightWebSocketConnection cannot connect before URL assignment; state=$state")
        if (lifecycleJob != null || state == State.CONNECTED) {
            return
        }

        lifecycleJob = scope.launch {
            runCatching {
                connectInternal(resolvedWebSocketUrl, null)
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    state = State.DISCONNECTED
                    return@onFailure
                }

                state = State.CONNECTION_FAILED
                logError(
                    "MSightWebSocketConnection failed: state=$state, url=$resolvedWebSocketUrl, error=${describeThrowable(throwable)}"
                )
            }.also {
                lifecycleJob = null
            }
        }
    }

    fun close() {
        lifecycleJob?.cancel()
        lifecycleJob = null
        state = State.DISCONNECTED
    }

    private suspend fun fetchWebSocketUrl(): String {
        val response = client.get(websocketUrlEndpoint)
        val responseBody = response.bodyAsText()

        return extractJsonStringField(
            json = responseBody,
            fieldName = "websocket_url"
        ) ?: error("websocket_url is missing from /system/websocket-url response")
    }

    private suspend fun connectInternal(
        resolvedWebSocketUrl: String,
        connectionReady: CompletableDeferred<Unit>?
    ) {
        val connectUrl = URLBuilder(resolvedWebSocketUrl).apply {
            parameters.append("app_id", config.appId)
            parameters.append("client_id", config.clientId)
        }.buildString()

        client.webSocket(urlString = connectUrl) {
            state = State.CONNECTED
            logInfo("MSightWebSocketConnection connected: url=$connectUrl, state=$state")
            connectionReady?.complete(Unit)
            awaitCancellation()
        }

        if (state == State.CONNECTED) {
            state = State.DISCONNECTED
        }
    }
}

private fun buildLocationUpdatePayload(
    config: MSightClientConfig,
    locationEvent: MSightLocationEvent
): String {
    val locationFields = buildList {
        add(jsonField("lat", locationEvent.latitude))
        add(jsonField("lon", locationEvent.longitude))
        locationEvent.altitudeMeters?.let { add(jsonField("alt", it)) }
        locationEvent.accuracyMeters?.let { add(jsonField("horizontal_accuracy_m", it)) }
        locationEvent.speedMps?.let { add(jsonField("speed_mps", it)) }
        locationEvent.bearingDegrees?.let { add(jsonField("heading_deg", it)) }
        add(jsonField("source", locationEvent.provider.toLocationSource()))
    }

    return """
        {
          "app_id": ${jsonString(config.appId)},
          "client_id": ${jsonString(config.clientId)},
          "timestamp": ${jsonString(Instant.fromEpochMilliseconds(locationEvent.timestampMillis).toString())},
          "location": {
            ${locationFields.joinToString(",\n            ")}
          }
        }
    """.trimIndent()
}

private fun Double.toMinUpdateIntervalMillis(): Long {
    if (this <= 0.0) {
        return 0L
    }

    return (1000.0 / this).roundToLong().coerceAtLeast(1L)
}

private fun String.toLocationSource(): String {
    return when (lowercase()) {
        "gps" -> "gps"
        "network" -> "network"
        "fused" -> "fused"
        "manual" -> "manual"
        else -> "unknown"
    }
}

private fun jsonField(name: String, value: Number): String =
    "${jsonString(name)}: $value"

private fun jsonField(name: String, value: String): String =
    "${jsonString(name)}: ${jsonString(value)}"

private fun jsonString(value: String): String {
    val escaped = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    return "\"$escaped\""
}

private fun extractJsonStringField(json: String, fieldName: String): String? {
    val pattern = Regex("\"" + Regex.escape(fieldName) + "\"\\s*:\\s*\"([^\"]+)\"")
    return pattern.find(json)?.groupValues?.getOrNull(1)
}

private fun logInfo(message: String) {
    println("INFO: $message")
}

private fun logError(message: String) {
    println("ERROR: $message")
}

private fun describeThrowable(throwable: Throwable): String {
    val segments = mutableListOf<String>()
    var current: Throwable? = throwable

    while (current != null) {
        segments += current.toString()
        current = current.cause
    }

    return segments.joinToString(" <- caused by ")
}

private const val LOCATION_UPDATE_PATH = "/v1/clients/location/update"
private const val WEBSOCKET_URL_PATH = "/system/websocket-url"