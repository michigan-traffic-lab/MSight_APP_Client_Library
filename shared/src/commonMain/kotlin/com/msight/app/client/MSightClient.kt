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
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val httpClient = createPlatformHttpClient {
        install(WebSockets)
    }
    private val _events = MutableSharedFlow<MSightEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    private var locationUploader: MSightLocationUploader? = null
    private var locationUpdateEmitter: MSightLocationUpdateEmitter? = null
    private var webSocketConnection: MSightWebSocketConnection? = null

    val events: SharedFlow<MSightEvent> = _events.asSharedFlow()

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
            onLocation = { locationEvent ->
                initializedLocationUploader.upload(locationEvent)
                _events.tryEmit(locationEvent)
            }
        )
        webSocketConnection = MSightWebSocketConnection(
            config = config,
            client = httpClient,
            scope = scope,
            onConnected = initializedLocationUploader::resendLastKnownLocation,
            onEvent = { event -> _events.tryEmit(event) }
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
    private var lastKnownLocationEvent: MSightLocationEvent? = null

    fun upload(locationEvent: MSightLocationEvent) {
        lastKnownLocationEvent = locationEvent
        uploadInternal(locationEvent)
    }

    fun resendLastKnownLocation() {
        val locationEvent = lastKnownLocationEvent ?: return
        logInfo("MSightLocationUploader resending last known location after websocket connect")
        uploadInternal(locationEvent)
    }

    private fun uploadInternal(locationEvent: MSightLocationEvent) {
        scope.launch {
            runCatching {
                client.post(locationUpdateUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        TextContent(
                            buildLocationUpdatePayload(config, locationEvent),
                            ContentType.Application.Json
                        )
                    )
                }
            }.onFailure { throwable ->
                logError(
                    "MSightLocationUploader failed: error=${describeThrowable(throwable)}"
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
    private val scope: CoroutineScope,
    private val onConnected: () -> Unit,
    private val onEvent: (MSightEvent) -> Unit
) {
    enum class State {
        NO_URL_ASSIGNED,
        URL_ASSIGNED,
        CONNECTED,
        DISCONNECTED,
        RECONNECTING,
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
            try {
                runConnectionLoop(connectionReady)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    state = State.DISCONNECTED
                    connectionReady.cancel(throwable)
                    throw throwable
                }

                state = State.CONNECTION_FAILED
                logError(
                    "MSightWebSocketConnection initialization failed: state=$state, error=${describeThrowable(throwable)}"
                )
                if (!connectionReady.isCompleted) {
                    connectionReady.completeExceptionally(throwable)
                }
            } finally {
                lifecycleJob = null
            }
        }

        connectionReady.await()
    }

    fun connect() {
        if (lifecycleJob != null || state == State.CONNECTED || state == State.RECONNECTING) {
            return
        }

        lifecycleJob = scope.launch {
            try {
                runConnectionLoop(connectionReady = null)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    state = State.DISCONNECTED
                    throw throwable
                }

                state = State.CONNECTION_FAILED
                logError(
                    "MSightWebSocketConnection failed: state=$state, error=${describeThrowable(throwable)}"
                )
            } finally {
                lifecycleJob = null
            }
        }
    }

    fun close() {
        lifecycleJob?.cancel()
        lifecycleJob = null
        state = State.DISCONNECTED
    }

    private suspend fun runConnectionLoop(connectionReady: CompletableDeferred<Unit>?) {
        var reconnectDelayMillis = INITIAL_RECONNECT_DELAY_MILLIS

        while (scope.isActive) {
            try {
                val resolvedWebSocketUrl = fetchWebSocketUrl()
                websocketUrl = resolvedWebSocketUrl
                state = State.URL_ASSIGNED
                logInfo("MSightWebSocketConnection obtained websocket url: $resolvedWebSocketUrl, state=$state")

                connectInternal(resolvedWebSocketUrl, connectionReady)
                reconnectDelayMillis = INITIAL_RECONNECT_DELAY_MILLIS

                if (!scope.isActive) {
                    break
                }

                state = State.RECONNECTING
                logInfo(
                    "MSightWebSocketConnection disconnected; retrying in ${reconnectDelayMillis}ms, state=$state"
                )
                delay(reconnectDelayMillis)
                reconnectDelayMillis = nextReconnectDelayMillis(reconnectDelayMillis)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }

                state = State.CONNECTION_FAILED
                logError(
                    "MSightWebSocketConnection connection attempt failed: state=$state, error=${describeThrowable(throwable)}"
                )
                if (connectionReady != null && !connectionReady.isCompleted) {
                    connectionReady.completeExceptionally(throwable)
                    return
                }

                state = State.RECONNECTING
                logInfo(
                    "MSightWebSocketConnection retrying after failure in ${reconnectDelayMillis}ms, state=$state"
                )
                delay(reconnectDelayMillis)
                reconnectDelayMillis = nextReconnectDelayMillis(reconnectDelayMillis)
            }
        }
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
            onConnected()
            if (connectionReady != null && !connectionReady.isCompleted) {
                connectionReady.complete(Unit)
            }

            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> handleTextFrame(frame.readText())
                    is Frame.Binary -> logInfo(
                        "MSightWebSocketConnection received binary frame: size=${frame.data.size}"
                    )
                    else -> Unit
                }
            }
        }

        if (state == State.CONNECTED) {
            state = State.DISCONNECTED
        }
    }

    private fun handleTextFrame(rawMessage: String) {
        val parsedEvent = parseSocketMessage(rawMessage)
        if (parsedEvent == null) {
            logInfo("MSightWebSocketConnection received unparsed message: $rawMessage")
            return
        }

        onEvent(parsedEvent)
    }
}

private fun nextReconnectDelayMillis(currentDelayMillis: Long): Long {
    return (currentDelayMillis * 2).coerceAtMost(MAX_RECONNECT_DELAY_MILLIS)
}

private fun parseSocketMessage(rawMessage: String): MSightEvent? {
    if (!looksLikeJsonObject(rawMessage)) {
        return null
    }

    val payloadJson = extractJsonObjectField(rawMessage, "message") ?: rawMessage
    val eventId = extractJsonStringField(rawMessage, "event_id")

    // New message format: "type" field (used by SDSM and future message types)
    val type = extractJsonStringField(payloadJson, "type")
    if (type != null) {
        return when (type) {
            SDSM_MESSAGE_TYPE -> parseSdsmEvent(payloadJson, eventId)
            SPAT_MESSAGE_TYPE -> parseSpatEvent(payloadJson, eventId)
            else -> null
        }
    }

    // Legacy message format: "message_type" field
    val messageType = extractJsonStringField(payloadJson, "message_type") ?: return null
    return when (messageType) {
        SIMPLE_WARNING_MESSAGE_TYPE -> parseSimpleWarningEvent(
            envelopeJson = rawMessage,
            payloadJson = payloadJson,
            eventId = eventId
        )
        else -> null
    }
}

private fun parseSimpleWarningEvent(
    envelopeJson: String,
    payloadJson: String,
    eventId: String? = null
): MSightSimpleWarning? {
    val message = extractJsonStringField(payloadJson, "message") ?: return null
    val timestampIsoString = extractJsonStringField(payloadJson, "timestamp")
        ?: extractJsonStringField(envelopeJson, "server_timestamp")

    val timestampMillis = timestampIsoString
        ?.let(::parseIsoTimestampMillis)
        ?: currentTimeMillis()

    return MSightSimpleWarning(
        timestampMillis = timestampMillis,
        eventId = eventId,
        message = message
    )
}

private fun parseIsoTimestampMillis(value: String): Long? {
    return runCatching {
        Instant.parse(value).toEpochMilliseconds()
    }.getOrNull()
}

private fun currentTimeMillis(): Long =
    Clock.System.now().toEpochMilliseconds()

private fun looksLikeJsonObject(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.startsWith("{") && trimmed.endsWith("}")
}

private fun extractJsonObjectField(json: String, fieldName: String): String? {
    val fieldToken = "\"$fieldName\""
    val fieldIndex = json.indexOf(fieldToken)
    if (fieldIndex < 0) {
        return null
    }

    val colonIndex = json.indexOf(':', startIndex = fieldIndex + fieldToken.length)
    if (colonIndex < 0) {
        return null
    }

    val objectStartIndex = json.indexOf('{', startIndex = colonIndex + 1)
    if (objectStartIndex < 0) {
        return null
    }

    var depth = 0
    for (index in objectStartIndex until json.length) {
        when (json[index]) {
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) {
                    return json.substring(objectStartIndex, index + 1)
                }
            }
        }
    }

    return null
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
private const val INITIAL_RECONNECT_DELAY_MILLIS = 1_000L
private const val MAX_RECONNECT_DELAY_MILLIS = 30_000L
private const val SIMPLE_WARNING_MESSAGE_TYPE = "msight_simple_warning"
private const val SDSM_MESSAGE_TYPE = "sdsm"
private const val SPAT_MESSAGE_TYPE = "spat"

private val lenientJson = Json { ignoreUnknownKeys = true }

private fun parseSdsmEvent(messageJson: String, eventId: String? = null): MSightSdsmEvent? {
    return try {
        val msg = lenientJson.parseToJsonElement(messageJson).jsonObject
        val sdsmObj = msg["sdsm"]?.jsonObject ?: return null

        val captureTimestamp = msg["capture_timestamp"]?.jsonPrimitive?.doubleOrNull ?: return null
        val timestampMillis = (captureTimestamp * 1000.0).roundToLong()

        val sdsmTimestamp = sdsmObj["sDSMTimeStamp"]?.jsonObject?.let { ts ->
            SdsmTimestamp(
                year = ts["year"]?.jsonPrimitive?.intOrNull ?: return@let null,
                month = ts["month"]?.jsonPrimitive?.intOrNull ?: return@let null,
                day = ts["day"]?.jsonPrimitive?.intOrNull ?: return@let null,
                hour = ts["hour"]?.jsonPrimitive?.intOrNull ?: return@let null,
                minute = ts["minute"]?.jsonPrimitive?.intOrNull ?: return@let null,
                second = ts["second"]?.jsonPrimitive?.doubleOrNull ?: return@let null,
                offset = ts["offset"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }

        val refPos = lenientJson.decodeFromJsonElement<SdsmRefPos>(
            sdsmObj["refPos"] ?: return null
        )
        val refPosXYConf = sdsmObj["refPosXYConf"]?.let {
            lenientJson.decodeFromJsonElement<SdsmRefPosConf>(it)
        }
        val objects = sdsmObj["objects"]?.jsonArray
            ?.mapNotNull { parseDetectedObject(it.jsonObject) }
            ?: emptyList()

        MSightSdsmEvent(
            timestampMillis = timestampMillis,
            eventId = eventId,
            sensorName = msg["sensor_name"]?.jsonPrimitive?.contentOrNull ?: return null,
            deviceName = msg["device_name"]?.jsonPrimitive?.contentOrNull ?: return null,
            captureTimestamp = captureTimestamp,
            creationTimestamp = msg["creation_timestamp"]?.jsonPrimitive?.doubleOrNull ?: return null,
            frameId = msg["frame_id"]?.jsonPrimitive?.content ?: return null,
            msgCnt = sdsmObj["msgCnt"]?.jsonPrimitive?.intOrNull ?: 0,
            sourceId = sdsmObj["sourceID"]?.jsonPrimitive?.contentOrNull ?: "",
            equipmentType = sdsmObj["equipmentType"]?.jsonPrimitive?.contentOrNull,
            sdsmTimestamp = sdsmTimestamp,
            refPos = refPos,
            refPosXYConf = refPosXYConf,
            objects = objects
        )
    } catch (e: Exception) {
        logError("parseSdsmEvent failed: ${describeThrowable(e)}")
        null
    }
}

private fun parseSpatEvent(messageJson: String, eventId: String? = null): MSightSpatEvent? {
    return try {
        val msg = lenientJson.parseToJsonElement(messageJson).jsonObject
        val spatObj = msg["spat"]?.jsonObject ?: return null

        val captureTimestamp = msg["capture_timestamp"]?.jsonPrimitive?.doubleOrNull ?: return null
        val timestampMillis = (captureTimestamp * 1000.0).roundToLong()

        val intersection = parseSpatIntersection(spatObj) ?: return null

        MSightSpatEvent(
            timestampMillis = timestampMillis,
            eventId = eventId,
            sensorName = msg["sensor_name"]?.jsonPrimitive?.contentOrNull ?: return null,
            deviceName = msg["device_name"]?.jsonPrimitive?.contentOrNull ?: return null,
            captureTimestamp = captureTimestamp,
            creationTimestamp = msg["creation_timestamp"]?.jsonPrimitive?.doubleOrNull ?: return null,
            frameId = msg["frame_id"]?.jsonPrimitive?.contentOrNull ?: "",
            intersectionName = msg["intersection_name"]?.jsonPrimitive?.contentOrNull,
            msgCnt = spatObj["msgCnt"]?.jsonPrimitive?.intOrNull ?: 0,
            name = spatObj["name"]?.jsonPrimitive?.contentOrNull,
            intersection = intersection
        )
    } catch (e: Exception) {
        logError("parseSpatEvent failed: ${describeThrowable(e)}")
        null
    }
}

private fun parseSpatIntersection(obj: JsonObject): SpatIntersection? {
    return try {
        val idObj = obj["id"]?.jsonObject ?: return null
        val intersectionId = SpatIntersectionId(
            id = idObj["id"]?.jsonPrimitive?.intOrNull ?: return null,
            region = idObj["region"]?.jsonPrimitive?.intOrNull
        )
        val status = obj["status"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.intOrNull }
            ?: emptyList()
        val states = obj["states"]?.jsonArray
            ?.mapNotNull { parseSpatMovementState(it.jsonObject) }
            ?: emptyList()

        SpatIntersection(
            id = intersectionId,
            revision = obj["revision"]?.jsonPrimitive?.intOrNull ?: 0,
            status = status,
            moy = obj["moy"]?.jsonPrimitive?.intOrNull,
            timeStamp = obj["timeStamp"]?.jsonPrimitive?.intOrNull,
            states = states
        )
    } catch (e: Exception) {
        null
    }
}

private fun parseSpatMovementState(obj: JsonObject): SpatMovementState? {
    return try {
        val signalGroup = obj["signalGroup"]?.jsonPrimitive?.intOrNull ?: return null
        val stateTimeSpeed = obj["state-time-speed"]?.jsonArray
            ?.mapNotNull { entry ->
                val sts = entry.jsonObject
                val eventState = sts["eventState"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val timing = sts["timing"]?.jsonObject?.let { t ->
                    SpatTiming(
                        startTime = t["startTime"]?.jsonPrimitive?.intOrNull,
                        minEndTime = t["minEndTime"]?.jsonPrimitive?.intOrNull ?: return@let null,
                        maxEndTime = t["maxEndTime"]?.jsonPrimitive?.intOrNull,
                        likelyTime = t["likelyTime"]?.jsonPrimitive?.intOrNull,
                        confidence = t["confidence"]?.jsonPrimitive?.intOrNull,
                        nextTime = t["nextTime"]?.jsonPrimitive?.intOrNull
                    )
                }
                SpatStateTimeSpeed(eventState = eventState, timing = timing)
            }
            ?: emptyList()

        SpatMovementState(signalGroup = signalGroup, stateTimeSpeed = stateTimeSpeed)
    } catch (e: Exception) {
        null
    }
}

private fun parseDetectedObject(entry: JsonObject): SdsmDetectedObject? {
    return try {
        val common = entry["detObjCommon"]?.jsonObject ?: return null
        val pos = lenientJson.decodeFromJsonElement<SdsmOffset>(common["pos"] ?: return null)
        val posConf = lenientJson.decodeFromJsonElement<SdsmPosConfidence>(
            common["posConfidence"] ?: return null
        )

        var vehicleSize: SdsmVehicleSize? = null
        var vehicleClass: Int? = null
        val optData = entry["detObjOptData"]?.jsonArray
        if (optData != null && optData.size >= 2) {
            if (optData[0].jsonPrimitive.contentOrNull == "detVeh") {
                val vehObj = optData[1].jsonObject
                vehicleClass = vehObj["vehicleClass"]?.jsonPrimitive?.intOrNull
                vehicleSize = vehObj["size"]?.let {
                    lenientJson.decodeFromJsonElement<SdsmVehicleSize>(it)
                }
            }
        }

        SdsmDetectedObject(
            objectType = common["objType"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            objTypeCfd = common["objTypeCfd"]?.jsonPrimitive?.intOrNull ?: 0,
            objectID = common["objectID"]?.jsonPrimitive?.intOrNull ?: 0,
            measurementTime = common["measurementTime"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            timeConfidence = common["timeConfidence"]?.jsonPrimitive?.contentOrNull ?: "",
            pos = pos,
            posConfidence = posConf,
            speed = common["speed"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            speedConfidence = common["speedConfidence"]?.jsonPrimitive?.contentOrNull ?: "",
            heading = common["heading"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            headingConf = common["headingConf"]?.jsonPrimitive?.contentOrNull ?: "",
            vehicleSize = vehicleSize,
            vehicleClass = vehicleClass
        )
    } catch (e: Exception) {
        null
    }
}