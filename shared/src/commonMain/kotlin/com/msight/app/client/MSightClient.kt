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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

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
    private var mapLoader: MSightMapLoader? = null
    private var signalProcessor: MSightSignalProcessor? = null
    private val _locationHistory = ArrayDeque<MSightTrajectoryPoint>()

    val events: SharedFlow<MSightEvent> = _events.asSharedFlow()
    val locationHistory: List<MSightTrajectoryPoint> get() = _locationHistory.toList()

    suspend fun loadMapsByLocation(
        lat: Double,
        lon: Double,
        radiusMeters: Int = MAP_FETCH_RADIUS_METERS
    ): List<MSightIntersectionMap> {
        val baseUrl = config.cloudUrl.trimEnd('/')
        return runCatching {
            fetchMapsByLocation(httpClient, baseUrl, lat, lon, radiusMeters)
        }.getOrElse { throwable ->
            logError("loadMapsByLocation failed: ${describeThrowable(throwable)}")
            emptyList()
        }
    }

    suspend fun loadMapsByName(intersectionName: String): List<MSightIntersectionMap> {
        val baseUrl = config.cloudUrl.trimEnd('/')
        return runCatching {
            fetchMapsByName(httpClient, baseUrl, intersectionName)
        }.getOrElse { throwable ->
            logError("loadMapsByName failed: ${describeThrowable(throwable)}")
            emptyList()
        }
    }

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
        val initializedSignalProcessor = MSightSignalProcessor(
            scope = scope,
            onSignalState = { event -> _events.tryEmit(event) }
        )
        val initializedMapLoader = MSightMapLoader(
            config = config,
            client = httpClient,
            scope = scope,
            onMapsLoaded = { maps ->
                initializedSignalProcessor.onMapsLoaded(maps)
            }
        )

        locationUploader = initializedLocationUploader
        mapLoader = initializedMapLoader
        signalProcessor = initializedSignalProcessor
        locationUpdateEmitter = MSightLocationUpdateEmitter(
            context = context,
            updateFrequencyHz = config.locationUpdateFrequencyHz,
            onLocation = { locationEvent ->
                initializedLocationUploader.upload(locationEvent)
                updateLocationHistory(locationEvent)
                initializedMapLoader.onLocation(locationEvent)
                initializedSignalProcessor.onLocation(locationEvent, _locationHistory.toList())
                _events.tryEmit(locationEvent)
            }
        )
        webSocketConnection = MSightWebSocketConnection(
            config = config,
            client = httpClient,
            scope = scope,
            onConnected = initializedLocationUploader::resendLastKnownLocation,
            onEvent = { event ->
                if (event is MSightSpatEvent) initializedSignalProcessor.onSpat(event)
                _events.tryEmit(event)
            }
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
        signalProcessor?.cancel()
        locationUpdateEmitter = null
        webSocketConnection = null
        locationUploader = null
        mapLoader = null
        signalProcessor = null
        _locationHistory.clear()
    }

    private fun updateLocationHistory(event: MSightLocationEvent) {
        _locationHistory.addLast(
            MSightTrajectoryPoint(
                timestampMillis = event.timestampMillis,
                latitude = event.latitude,
                longitude = event.longitude,
                altitudeMeters = event.altitudeMeters,
                speedMps = event.speedMps,
                headingDegrees = event.bearingDegrees
            )
        )
        val cutoff = event.timestampMillis - TRAJECTORY_HISTORY_MILLIS
        while (_locationHistory.isNotEmpty() && _locationHistory.first().timestampMillis < cutoff) {
            _locationHistory.removeFirst()
        }
    }

    fun setSpatEnabled(enabled: Boolean) {
        signalProcessor?.setEnabled(enabled)
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

private class MSightMapLoader(
    private val config: MSightClientConfig,
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val onMapsLoaded: (List<MSightIntersectionMap>) -> Unit
) {
    private val baseUrl = config.cloudUrl.trimEnd('/')
    private var lastFetchLat: Double? = null
    private var lastFetchLon: Double? = null
    private var loadedMapCenters: List<Pair<Double, Double>> = emptyList()

    fun onLocation(locationEvent: MSightLocationEvent) {
        val lat = locationEvent.latitude
        val lon = locationEvent.longitude

        // Skip fetch if the current location is still within the search radius of any
        // already-loaded map. There is no new intersection to discover inside a zone we
        // already covered, so the existing maps remain valid.
        if (loadedMapCenters.any { (cLat, cLon) ->
                haversineDistanceMeters(lat, lon, cLat, cLon) <= MAP_LOADED_ZONE_METERS
            }) return

        // Outside all loaded map areas — only fetch if we have moved far enough from the
        // last fetch point to avoid hammering the server while walking in an uncovered zone.
        val prevLat = lastFetchLat
        val prevLon = lastFetchLon
        if (prevLat != null && prevLon != null &&
            haversineDistanceMeters(prevLat, prevLon, lat, lon) < MAP_REFETCH_DISTANCE_METERS) {
            return
        }
        lastFetchLat = lat
        lastFetchLon = lon
        scope.launch { fetchAndNotify(lat, lon) }
    }

    private suspend fun fetchAndNotify(lat: Double, lon: Double) {
        runCatching {
            fetchMapsByLocation(client, baseUrl, lat, lon, MAP_FETCH_RADIUS_METERS)
        }.onSuccess { maps ->
            if (maps.isNotEmpty()) {
                loadedMapCenters = maps.map { it.centerLat to it.centerLon }
                onMapsLoaded(maps)
            }
        }.onFailure { throwable ->
            logError("MSightMapLoader fetch failed: ${describeThrowable(throwable)}")
        }
    }
}

private class MSightSignalProcessor(
    private val scope: CoroutineScope,
    private val onSignalState: (MSightSignalStateEvent) -> Unit
) {
    private enum class DisplayState { IDLE, ACTIVE, HIDING }

    private var displayState = DisplayState.IDLE
    private var loadedMaps: List<MSightIntersectionMap> = emptyList()
    private var activeApproach: ApproachResult? = null
    private var latestSpatEvent: MSightSpatEvent? = null
    private var nullResultFrames = 0
    private var lastEmitMillis = 0L
    private var hideJob: Job? = null
    private var enabled = false

    fun setEnabled(enabled: Boolean) {
        if (!enabled && this.enabled && displayState != DisplayState.IDLE) {
            onSignalState(MSightSignalStateEvent(
                timestampMillis = currentTimeMillis(),
                intersectionName = null,
                straightColor = SignalColor.UNKNOWN,
                leftTurnColor = SignalColor.UNKNOWN
            ))
            clearState()
        }
        this.enabled = enabled
    }

    fun onMapsLoaded(maps: List<MSightIntersectionMap>) {
        loadedMaps = maps
    }

    fun onLocation(locationEvent: MSightLocationEvent, history: List<MSightTrajectoryPoint>) {
        if (!enabled) return
        if (displayState == DisplayState.HIDING) return

        println("INFO: SignalProcessor state=$displayState lat=%.6f lon=%.6f".format(locationEvent.latitude, locationEvent.longitude))

        val rawResult = MSightApproachDetector.detectActiveApproach(locationEvent, history, loadedMaps)

        // Crossing detection — trip when the user has moved past EITHER the locked arm's
        // stop line OR the live detected arm's stop line.
        //
        // The arm lock applies ONLY to the DISPLAYED signal (activeApproach drives what we
        // show in the overlay). The detector still runs in real time every frame, and the
        // crossing check is allowed to follow the live detected arm. This is required for
        // turn cases: on a sharp left/right turn, the user's component along the locked
        // arm's bearing (a perpendicular line through refPoint) can peak below the 3 m
        // threshold and then plateau/decay as they exit on a perpendicular street, so the
        // locked-arm crossing check would never fire. The live arm's perpendicular (through
        // the same refPoint, oriented along the cross street) does get crossed cleanly as
        // they move down the new street, so HIDE timing stays accurate through any turn.
        val lockedSignedDist = activeApproach?.let { computeSignedApproachDist(locationEvent, it) }
        val liveSignedDist = rawResult?.let { computeSignedApproachDist(locationEvent, it) }
        val crossedLocked = lockedSignedDist != null && lockedSignedDist >= STOP_LINE_CROSSED_THRESHOLD_METERS
        val crossedLive = liveSignedDist != null && liveSignedDist >= STOP_LINE_CROSSED_THRESHOLD_METERS
        println("INFO: SignalProcessor crossing-check lockedDist=%s liveDist=%s threshold=%.1fm lockedArm=%s liveArm=%s".format(
            lockedSignedDist?.let { "%.1fm".format(it) } ?: "n/a",
            liveSignedDist?.let { "%.1fm".format(it) } ?: "n/a",
            STOP_LINE_CROSSED_THRESHOLD_METERS,
            activeApproach?.arm?.armId?.toString() ?: "n/a",
            rawResult?.arm?.armId?.toString() ?: "n/a"
        ))
        if (crossedLocked || crossedLive) {
            val source = if (crossedLocked) "locked arm=${activeApproach?.arm?.armId}"
                         else "live arm=${rawResult?.arm?.armId}"
            println("INFO: SignalProcessor crossed stop line ($source), transitioning state=$displayState→${if (displayState == DisplayState.ACTIVE) "HIDING" else "IDLE"}")
            if (displayState == DisplayState.ACTIVE) startHiding() else clearState()
            return
        }

        // Below threshold for both lines — rawResult is a valid candidate this frame.
        // (The post-filter that used to null this out for crossed arms is no longer needed:
        // any rawResult past its own threshold has already triggered the branch above.)
        val result = rawResult

        when (displayState) {
            DisplayState.IDLE -> {
                if (result != null) {
                    activeApproach = result
                    displayState = DisplayState.ACTIVE
                    val spat = latestSpatEvent
                    if (spat != null) {
                        emitSignalState(locationEvent.timestampMillis, result, spat, force = true)
                    } else {
                        val totalGroups = result.arm.straightSignalGroups.union(result.arm.leftTurnSignalGroups).size
                        onSignalState(MSightSignalStateEvent(
                            timestampMillis = locationEvent.timestampMillis,
                            intersectionName = result.intersection.name,
                            straightColor = SignalColor.UNKNOWN,
                            leftTurnColor = SignalColor.UNKNOWN,
                            showSingleLight = totalGroups <= 1,
                            straightSignalGroupIds = result.arm.straightSignalGroups.sorted(),
                            leftTurnSignalGroupIds = result.arm.leftTurnSignalGroups.sorted()
                        ))
                    }
                }
            }
            DisplayState.ACTIVE -> {
                if (result != null) {
                    nullResultFrames = 0
                    // Arm is locked once ACTIVE — ignore detector arm changes until the
                    // user crosses the locked arm's stop line. Prevents the displayed
                    // signal from flipping when the driver turns into a different arm
                    // of the same intersection.
                    val locked = activeApproach
                    if (locked != null && result.arm.armId != locked.arm.armId) {
                        println("INFO: SignalProcessor arm locked=%d, ignoring detector arm=%d".format(
                            locked.arm.armId, result.arm.armId))
                    }
                    val spat = latestSpatEvent
                    if (locked != null && spat != null) {
                        emitSignalState(locationEvent.timestampMillis, locked, spat, force = false)
                    }
                } else {
                    nullResultFrames++
                    println("INFO: SignalProcessor result=null in ACTIVE nullFrames=$nullResultFrames/${APPROACH_SWITCH_MIN_FRAMES}")
                    if (nullResultFrames >= APPROACH_SWITCH_MIN_FRAMES) {
                        nullResultFrames = 0
                        clearAndReset()
                    }
                }
            }
            DisplayState.HIDING -> Unit
        }
    }

    fun onSpat(spatEvent: MSightSpatEvent) {
        if (!enabled) return
        val name = spatEvent.intersectionName ?: return
        val approach = activeApproach ?: return
        if (name != approach.intersection.name) return

        latestSpatEvent = spatEvent

        if (displayState == DisplayState.ACTIVE) {
            emitSignalState(spatEvent.timestampMillis, approach, spatEvent, force = true)
        }
    }

    fun cancel() {
        hideJob?.cancel()
        hideJob = null
    }

    private fun emitSignalState(
        timestampMillis: Long,
        result: ApproachResult,
        spat: MSightSpatEvent,
        force: Boolean
    ) {
        // if (!force && timestampMillis - lastEmitMillis < SIGNAL_UPDATE_INTERVAL_MILLIS) return
        lastEmitMillis = timestampMillis
        val colors = MSightApproachDetector.extractArmSignals(result.arm, spat)
        val totalGroups = result.arm.straightSignalGroups.union(result.arm.leftTurnSignalGroups).size
        onSignalState(MSightSignalStateEvent(
            timestampMillis = timestampMillis,
            intersectionName = result.intersection.name,
            straightColor = colors.straightColor,
            leftTurnColor = colors.leftTurnColor,
            showSingleLight = totalGroups <= 1,
            straightSignalGroupIds = result.arm.straightSignalGroups.sorted(),
            leftTurnSignalGroupIds = result.arm.leftTurnSignalGroups.sorted()
        ))
    }

    private fun startHiding() {
        displayState = DisplayState.HIDING
        hideJob = scope.launch {
            delay(POST_PASS_HIDE_DELAY_MILLIS)
            onSignalState(MSightSignalStateEvent(
                timestampMillis = currentTimeMillis(),
                intersectionName = null,
                straightColor = SignalColor.UNKNOWN,
                leftTurnColor = SignalColor.UNKNOWN
            ))
            clearState()
        }
    }

    private fun clearAndReset() {
        onSignalState(MSightSignalStateEvent(
            timestampMillis = currentTimeMillis(),
            intersectionName = null,
            straightColor = SignalColor.UNKNOWN,
            leftTurnColor = SignalColor.UNKNOWN
        ))
        clearState()
    }

    private fun clearState() {
        hideJob?.cancel()
        hideJob = null
        displayState = DisplayState.IDLE
        activeApproach = null
        latestSpatEvent = null
        nullResultFrames = 0
        lastEmitMillis = 0L
    }

    /** Signed distance (meters) of the vehicle past the perpendicular stop line through refPoint.
     *  Positive = vehicle is on the "past intersection" side; negative = still approaching. */
    private fun computeSignedApproachDist(
        location: MSightLocationEvent,
        approach: ApproachResult
    ): Double {
        val ref = approach.intersection.refPoint
        val bearingRad = approach.arm.approachBearingDeg * PI / 180.0
        val dirEast = sin(bearingRad)
        val dirNorth = cos(bearingRad)
        val deltaNorthM = (location.latitude - ref.lat) * 111320.0
        val deltaEastM = (location.longitude - ref.lon) * cos(ref.lat * PI / 180.0) * 111320.0
        return deltaEastM * dirEast + deltaNorthM * dirNorth
    }
}

private fun parseSocketMessage(rawMessage: String): MSightEvent? {
    if (!looksLikeJsonObject(rawMessage)) {
        return null
    }

    val payloadJson = extractJsonObjectField(rawMessage, "message") ?: rawMessage
    val eventId = extractJsonStringField(rawMessage, "event_id")

    // New message format: "type" field — check payload first, fall back to envelope
    val type = extractJsonStringField(payloadJson, "type")
        ?: extractJsonStringField(rawMessage, "type")
    if (type != null) {
        logInfo("parseSocketMessage: type=$type payloadLen=${payloadJson.length}")
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

private fun sdsmTimestampToMillis(ts: SdsmTimestamp): Long? {
    return runCatching {
        val secondInt = ts.second.toInt()
        val nanoOfSecond = ((ts.second - secondInt) * 1_000_000_000.0).roundToLong().toInt()
        val ldt = LocalDateTime(ts.year, ts.month, ts.day, ts.hour, ts.minute, secondInt, nanoOfSecond)
        // ts.offset is minutes east of UTC; toInstant(UTC) treats components as UTC, so subtract offset
        ldt.toInstant(TimeZone.UTC).toEpochMilliseconds() - ts.offset * 60_000L
    }.getOrNull()
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
private const val MAP_SEARCH_PATH = "/v1/maps/search"
private const val MAP_BY_NAME_PATH = "/v1/maps/"
private const val MAP_FETCH_RADIUS_METERS = 150
private const val MAP_LOADED_ZONE_METERS = 100
private const val MAP_REFETCH_DISTANCE_METERS = 50.0
private const val TRAJECTORY_HISTORY_MILLIS = 120_000L
private const val STOP_LINE_CROSSED_THRESHOLD_METERS = 3.0
private const val APPROACH_SWITCH_MIN_FRAMES = 3
private const val SIGNAL_UPDATE_INTERVAL_MILLIS = 500L
private const val POST_PASS_HIDE_DELAY_MILLIS = 1_000L

private val lenientJson = Json { ignoreUnknownKeys = true }

private fun parseSdsmEvent(messageJson: String, eventId: String? = null): MSightSdsmEvent? {
    return try {
        val msg = lenientJson.parseToJsonElement(messageJson).jsonObject
        val sdsmObj = msg["sdsm"]?.jsonObject ?: return null

        val captureTimestamp = msg["capture_timestamp"]?.jsonPrimitive?.doubleOrNull ?: return null

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

        // Use the SDSM's own sensor timestamp for frame identity; fall back to capture_timestamp
        // if sDSMTimeStamp is absent. capture_timestamp / creation_timestamp are edge-server stamps
        // and arrive ~350 ms after the sensor time, making them unsuitable for frame merging.
        val timestampMillis = sdsmTimestamp?.let { sdsmTimestampToMillis(it) }
            ?: (captureTimestamp * 1000.0).roundToLong()

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
        logInfo("parseSpatEvent: top-level keys=${msg.keys}")

        val spatObj = msg["spat"]?.jsonObject ?: run {
            logError("parseSpatEvent: missing 'spat' key")
            return null
        }
        val captureTimestamp = msg["capture_timestamp"]?.jsonPrimitive?.doubleOrNull ?: run {
            logError("parseSpatEvent: missing 'capture_timestamp'")
            return null
        }
        val timestampMillis = (captureTimestamp * 1000.0).roundToLong()

        val intersection = parseSpatIntersection(spatObj) ?: run {
            logError("parseSpatEvent: parseSpatIntersection returned null, spatObj keys=${spatObj.keys}")
            return null
        }
        val sensorName = msg["sensor_name"]?.jsonPrimitive?.contentOrNull ?: run {
            logError("parseSpatEvent: missing 'sensor_name'")
            return null
        }
        val deviceName = msg["device_name"]?.jsonPrimitive?.contentOrNull ?: run {
            logError("parseSpatEvent: missing 'device_name'")
            return null
        }
        val creationTimestamp = msg["creation_timestamp"]?.jsonPrimitive?.doubleOrNull ?: run {
            logError("parseSpatEvent: missing 'creation_timestamp'")
            return null
        }

        MSightSpatEvent(
            timestampMillis = timestampMillis,
            eventId = eventId,
            sensorName = sensorName,
            deviceName = deviceName,
            captureTimestamp = captureTimestamp,
            creationTimestamp = creationTimestamp,
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
            name = obj["name"]?.jsonPrimitive?.contentOrNull,
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

// ── Map fetch ────────────────────────────────────────────────────────────────

private suspend fun fetchMapsByLocation(
    client: HttpClient,
    baseUrl: String,
    lat: Double,
    lon: Double,
    radiusMeters: Int
): List<MSightIntersectionMap> {
    val url = "$baseUrl$MAP_SEARCH_PATH?lat=$lat&lon=$lon&radius=$radiusMeters"
    val response = client.get(url)
    return parseMapsResponse(response.bodyAsText())
}

private suspend fun fetchMapsByName(
    client: HttpClient,
    baseUrl: String,
    intersectionName: String
): List<MSightIntersectionMap> {
    val url = "$baseUrl$MAP_BY_NAME_PATH$intersectionName"
    logInfo("fetchMapsByName requesting url=$url")
    val response = client.get(url)
    val body = response.bodyAsText()
    logInfo("fetchMapsByName status=${response.status} bodyPrefix=${body.take(300)}")
    return parseMapsResponse(body)
}

// ── Map parsing ──────────────────────────────────────────────────────────────

private fun parseMapsResponse(body: String): List<MSightIntersectionMap> {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return emptyList()
    return try {
        when {
            trimmed.startsWith("[") -> {
                // JSON array: [{...}, ...]
                lenientJson.parseToJsonElement(trimmed).jsonArray
                    .mapNotNull { parseMapEntry(it.jsonObject) }
            }
            trimmed.startsWith("{") -> {
                val root = lenientJson.parseToJsonElement(trimmed).jsonObject
                // {"maps": [...]} — location search
                // {"status":"ok","map":{...}} — name lookup (singular "map" key)
                // bare map object fallback
                root["maps"]?.jsonArray?.mapNotNull { parseMapEntry(it.jsonObject) }
                    ?: root["map"]?.jsonObject?.let { listOfNotNull(parseMapEntry(it)) }
                    ?: listOfNotNull(parseMapEntry(root))
            }
            else -> emptyList()
        }
    } catch (e: Exception) {
        logError("parseMapsResponse failed: ${describeThrowable(e)}")
        emptyList()
    }
}

private fun parseMapEntry(entry: JsonObject): MSightIntersectionMap? {
    return try {
        val name = entry["name"]?.jsonPrimitive?.contentOrNull ?: return null
        // Location-search format uses center_lat/center_lon; name-lookup format uses lat/lon
        val centerLat = entry["center_lat"]?.jsonPrimitive?.doubleOrNull
            ?: entry["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
        val centerLon = entry["center_lon"]?.jsonPrimitive?.doubleOrNull
            ?: entry["lon"]?.jsonPrimitive?.doubleOrNull ?: return null

        val data = entry["data"]?.jsonObject ?: return null
        val intersectionsArray = data["intersections"]?.jsonArray
            ?.takeIf { it.isNotEmpty() } ?: return null
        val intersection = intersectionsArray[0].jsonObject

        val idObj = intersection["id"]?.jsonObject ?: return null
        val intersectionId = idObj["id"]?.jsonPrimitive?.intOrNull ?: return null
        val intersectionRegion = idObj["region"]?.jsonPrimitive?.intOrNull
        // Name-lookup response has no root "id"; fall back to intersection id
        val dbId = entry["id"]?.jsonPrimitive?.intOrNull ?: intersectionId

        val refPosObj = intersection["refPoint"]?.jsonObject ?: return null
        val refLat = refPosObj["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
        val refLon = refPosObj["long"]?.jsonPrimitive?.doubleOrNull ?: return null
        val refElev = refPosObj["elevation"]?.jsonPrimitive?.doubleOrNull
        val refPoint = MapRefPoint(refLat, refLon, refElev)

        val laneSet = intersection["laneSet"]?.jsonArray ?: return null
        val intersectionLaneWidth = intersection["laneWidth"]?.jsonPrimitive?.doubleOrNull ?: 3.5
        val lanes = laneSet.mapNotNull { parseMapLane(it.jsonObject, intersectionLaneWidth, refElev) }

        MSightIntersectionMap(
            dbId = dbId,
            name = name,
            intersectionId = intersectionId,
            intersectionRegion = intersectionRegion,
            refPoint = refPoint,
            centerLat = centerLat,
            centerLon = centerLon,
            laneWidthM = intersectionLaneWidth,
            arms = buildMapArms(lanes)
        )
    } catch (e: Exception) {
        logError("parseMapEntry failed: ${describeThrowable(e)}")
        null
    }
}

private fun parseMapLane(laneObj: JsonObject, intersectionLaneWidth: Double, refElevationM: Double?): MapLane? {
    return try {
        // Skip crosswalks
        val laneType = laneObj["laneAttributes"]?.jsonObject
            ?.get("laneType")?.jsonArray
            ?.getOrNull(0)?.jsonPrimitive?.contentOrNull
        if (laneType == "crosswalk") return null

        val armId = laneObj["arm_id"]?.jsonPrimitive?.intOrNull ?: return null
        val laneID = laneObj["laneID"]?.jsonPrimitive?.intOrNull ?: return null
        val isIngress = laneObj.containsKey("ingressApproach")

        // nodeList = ["nodes", [{delta:[type,{x,y}], attributes?}, ...]]
        val nodesArray = laneObj["nodeList"]?.jsonArray
            ?.getOrNull(1)?.jsonArray ?: return null

        val nodes = buildList {
            var cumX = 0.0
            var cumY = 0.0
            var cumWidth = intersectionLaneWidth
            var cumElev: Double? = refElevationM
            for (element in nodesArray) {
                val nodeObj = element.jsonObject
                val deltaArray = nodeObj["delta"]?.jsonArray ?: continue
                val xyObj = deltaArray.getOrNull(1)?.jsonObject ?: continue
                val dx = xyObj["x"]?.jsonPrimitive?.doubleOrNull ?: continue
                val dy = xyObj["y"]?.jsonPrimitive?.doubleOrNull ?: continue
                cumX += dx
                cumY += dy
                val attrs = nodeObj["attributes"]?.jsonObject
                val dWidth = attrs?.get("dWidth")?.jsonPrimitive?.doubleOrNull
                if (dWidth != null) cumWidth += dWidth
                val dElev = attrs?.get("dElevation")?.jsonPrimitive?.doubleOrNull
                if (dElev != null) cumElev = (cumElev ?: 0.0) + dElev
                add(MapLaneNode(cumX, cumY, cumWidth, cumElev))
            }
        }

        val connections = if (isIngress) {
            laneObj["connectsTo"]?.jsonArray
                ?.mapNotNull { parseMapLaneConnection(it.jsonObject) }
                ?: emptyList()
        } else {
            emptyList()
        }

        val leftNeighborId = (laneObj["left_neighbor"] as? JsonObject)?.get("laneID")?.jsonPrimitive?.intOrNull
        val rightNeighborId = (laneObj["right_neighbor"] as? JsonObject)?.get("laneID")?.jsonPrimitive?.intOrNull

        MapLane(
            laneID = laneID, armId = armId, isIngress = isIngress,
            nodes = nodes, connections = connections,
            leftNeighborId = leftNeighborId, rightNeighborId = rightNeighborId
        )
    } catch (e: Exception) {
        null
    }
}

private fun parseMapLaneConnection(connObj: JsonObject): MapLaneConnection? {
    return try {
        val signalGroup = connObj["signalGroup"]?.jsonPrimitive?.intOrNull ?: return null
        MapLaneConnection(signalGroup = signalGroup)
    } catch (e: Exception) {
        null
    }
}

private fun buildMapArms(lanes: List<MapLane>): List<MapArm> {
    val laneById = lanes.associateBy { it.laneID }
    return lanes.groupBy { it.armId }.map { (armId, armLanes) ->
        val ingressLanes = armLanes.filter { it.isIngress }
        val egressLanes = armLanes.filter { !it.isIngress }
        val straight = mutableSetOf<Int>()
        val leftTurn = mutableSetOf<Int>()
        val rightTurn = mutableSetOf<Int>()
        for (lane in ingressLanes) {
            val laneSignalGroups = lane.connections.map { it.signalGroup }
            val leftNeighbor = lane.leftNeighborId?.let { laneById[it] }
            val rightNeighbor = lane.rightNeighborId?.let { laneById[it] }
            // Left-turn: left neighbor is egress, right neighbor is ingress or absent
            val isLeftTurn = leftNeighbor != null && !leftNeighbor.isIngress &&
                             (rightNeighbor == null || rightNeighbor.isIngress)
            // Straight: left neighbor is ingress or absent, right neighbor is ingress or absent
            val isStraight = (leftNeighbor == null || leftNeighbor.isIngress) &&
                             (rightNeighbor == null || rightNeighbor.isIngress)
            when {
                isLeftTurn -> leftTurn.addAll(laneSignalGroups)
                isStraight -> straight.addAll(laneSignalGroups)
                else       -> { straight.addAll(laneSignalGroups); leftTurn.addAll(laneSignalGroups) }
            }
        }
        MapArm(
            armId = armId,
            approachBearingDeg = computeApproachBearing(ingressLanes),
            ingressLanes = ingressLanes,
            egressLanes = egressLanes,
            straightSignalGroups = straight,
            leftTurnSignalGroups = leftTurn,
            rightTurnSignalGroups = rightTurn
        )
    }
}

/** Bearing (0–360°, clockwise from north) of a vehicle traveling toward the stop bar. */
private fun computeApproachBearing(ingressLanes: List<MapLane>): Double {
    val lane = ingressLanes.firstOrNull { it.nodes.size >= 2 } ?: return 0.0
    val first = lane.nodes.first()   // stop bar — nearest to intersection
    val last  = lane.nodes.last()    // far end of the approach
    val dx = first.offsetX - last.offsetX   // east component
    val dy = first.offsetY - last.offsetY   // north component
    return (atan2(dx, dy) * (180.0 / PI) + 360.0) % 360.0
}

private fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6_371_000.0
    val phi1 = lat1 * PI / 180.0
    val phi2 = lat2 * PI / 180.0
    val dPhi = (lat2 - lat1) * PI / 180.0
    val dLambda = (lon2 - lon1) * PI / 180.0
    val sinHalfDPhi = sin(dPhi / 2)
    val sinHalfDLambda = sin(dLambda / 2)
    val a = sinHalfDPhi * sinHalfDPhi + cos(phi1) * cos(phi2) * sinHalfDLambda * sinHalfDLambda
    return R * 2.0 * asin(sqrt(a))
}