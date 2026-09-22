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
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** What kind of road user is carrying this device. `VRU` is a vulnerable road user. */
enum class MSightRoadUserType {
    VEHICLE,
    VRU,
    OTHER
}

/**
 * What kind of device the library is running on.
 *
 * `IVI` is an in-vehicle infotainment head unit. `LOW_POWER_DEVICE` marks a device where battery
 * or thermal budget should be favoured over update rate.
 */
enum class MSightDeviceType {
    CELLPHONE,
    TABLET,
    IVI,
    LOW_POWER_DEVICE,
    OTHER
}

/**
 * Everything [MSightClient] needs to identify itself to MSight Cloud and decide how often to
 * report in.
 *
 * @property cloudUrl Base URL of the MSight Cloud HTTP API, e.g.
 *   `https://abc123.execute-api.us-east-2.amazonaws.com`. This is the `HttpApiUrl` output of a
 *   MSight Cloud deployment; a trailing slash is tolerated. The WebSocket endpoint is discovered
 *   from it at runtime rather than configured separately.
 * @property appId Identifier of the application this client belongs to, registered in MSight
 *   Cloud. The cloud scopes broadcasts by app, so an unregistered `appId` connects successfully
 *   but receives nothing.
 * @property clientId Identifier unique to this device within [appId]. Two live connections
 *   sharing a `clientId` will collide in the cloud's connection registry, so generate one per
 *   install rather than hard-coding it.
 * @property roadUserType What kind of road user carries this device. **Reserved — has no effect
 *   today:** it is neither transmitted nor read, because MSight Cloud's location-update schema
 *   has no field to carry it yet. It stays a required parameter rather than gaining a default so
 *   that the values are already accurate when the cloud can accept them; a default would mean
 *   every existing client silently reporting the same class on the day it starts mattering.
 * @property roadUserSubType Free-text refinement of [roadUserType], e.g. `passenger_car`,
 *   `transit_bus`, `pedestrian`. Reserved — see [roadUserType].
 * @property deviceType What kind of device this is. Reserved — see [roadUserType]. The intended
 *   use is per-device-class behaviour, such as a lower default update rate on
 *   [MSightDeviceType.LOW_POWER_DEVICE].
 * @property locationUpdateFrequencyHz Upper bound on how often a fix is uploaded and emitted, in
 *   updates per second. The platform is asked for fixes as fast as it will supply them and the
 *   surplus is dropped here, so raising this costs bandwidth and cloud calls but not GNSS power.
 *   `0.0` or less disables rate limiting and forwards every fix.
 */
data class MSightClientConfig(
    val cloudUrl: String,
    val appId: String,
    val clientId: String,
    val roadUserType: MSightRoadUserType,
    val roadUserSubType: String,
    val deviceType: MSightDeviceType,
    val locationUpdateFrequencyHz: Double = 1.0
)

/**
 * The library's entry point: one object that keeps a device connected to MSight Cloud and turns
 * what the cloud sends into typed [MSightEvent]s.
 *
 * Constructing an instance opens the connection — the constructor blocks until the WebSocket is
 * established or throws if it cannot be. [start] then begins location reporting, which is what
 * makes the device visible to the cloud's radius-scoped broadcasts; until it is called, the
 * connection is open but the device is nowhere, so nothing will be pushed to it.
 *
 * Internally the client runs five cooperating pieces, all on one [CoroutineScope] that [close]
 * cancels:
 *  - a location emitter, which rate-limits platform GNSS fixes;
 *  - a location uploader, which POSTs each fix so the cloud knows where this client is;
 *  - a WebSocket connection, which receives every server push and reconnects with backoff;
 *  - a map loader, which fetches intersection geometry for the area the device is in;
 *  - a signal processor, which combines geometry, position and SPaT into
 *    [MSightSignalStateEvent].
 *
 * Threading: all callbacks and emissions happen on the client's own dispatcher, not the caller's.
 * A host driving UI from [events] should hop to its main thread. Events are emitted with
 * `tryEmit` into a 64-slot buffer, so a collector that cannot keep up loses events rather than
 * stalling the network reader.
 *
 * Lifecycle: `MSightClient(...)` → [start] → collect [events] → [close]. An instance is not
 * reusable after [close]; construct a new one.
 */
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

    /**
     * The single stream of everything this client produces. Hot: events emitted before a
     * collector subscribes are not replayed.
     */
    val events: SharedFlow<MSightEvent> = _events.asSharedFlow()

    /**
     * The device's own recent track, oldest first, trimmed to the last
     * [TRAJECTORY_HISTORY_MILLIS]. Kept because heading has to be inferred from position history
     * when the device is stopped or slow and GNSS stops reporting a bearing.
     */
    val locationHistory: List<MSightTrajectoryPoint> get() = _locationHistory.toList()

    /**
     * Fetches intersection maps within [radiusMeters] of a point.
     *
     * Independent of the automatic loading the client does as the device moves — call this to
     * pre-load geometry for somewhere the device is not, for example to draw a map of a
     * corridor ahead.
     *
     * Returns an empty list on any failure rather than throwing; the failure is logged.
     */
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

    /**
     * Fetches one intersection map by its MSight Cloud name — the same name that appears as
     * [MSightSpatEvent.intersectionName], which makes this the way to resolve geometry for an
     * intersection whose SPaT arrived before its map was loaded.
     *
     * Returns a list for symmetry with [loadMapsByLocation]; it holds at most one map, and is
     * empty if the name is unknown or the request failed.
     */
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
        // Connect eagerly, so that a constructed client is a connected client and a host does not
        // have to handle a half-live object. runBlocking is deliberate: it makes a failure to
        // reach the cloud surface as a constructor exception the caller can catch, rather than as
        // an error arriving later on the event flow.
        runBlocking {
            initialize()
        }
    }

    /**
     * Tears down any existing session and builds a fresh one, returning once the WebSocket is
     * connected.
     *
     * Called by the constructor; call it again only to force a full reconnect, since the
     * connection already reconnects itself with backoff. Throws if the connection cannot be
     * established, leaving the client stopped.
     */
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
        // Every rate-limited fix fans out to all four consumers plus the public flow. Order
        // matters: history is updated before the signal processor reads it, and the map loader
        // runs before the processor so that geometry for a newly entered area is already on its
        // way when the processor next needs it.
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
            // Re-POST the last fix on every (re)connect. Client locations expire in the cloud,
            // and after a reconnect this device would otherwise be invisible to radius
            // broadcasts until its next scheduled location update.
            onConnected = initializedLocationUploader::resendLastKnownLocation,
            onEvent = { event ->
                if (event is MSightSpatEvent) initializedSignalProcessor.onSpat(event)
                if (event is MSightCriticalSpatEvent) initializedSignalProcessor.onCriticalSpat(event)
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

    /**
     * Begins location reporting.
     *
     * Required for the device to receive anything: MSight Cloud scopes its pushes by radius
     * around live client positions, so a client that never reports a position is never in range
     * of one. The host must already hold location permission.
     */
    fun start() {
        locationUpdateEmitter?.start()
    }

    /**
     * Stops location reporting, closes the WebSocket, and discards all session state including
     * location history.
     *
     * The coroutine scope and HTTP client survive, so [initialize] can rebuild a session; use
     * [close] to release those too.
     */
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

    /** Appends a fix to the rolling track and drops anything older than the retention window. */
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

    /**
     * Turns [MSightSignalStateEvent] production on or off. Off by default.
     *
     * Raw [MSightSpatEvent]s keep flowing regardless — this only controls the derived,
     * device-specific signal state. Switching it off while a signal is being displayed emits one
     * final event with a null intersection name, so a host can take its display down without a
     * special case.
     */
    fun setSpatEnabled(enabled: Boolean) {
        signalProcessor?.setEnabled(enabled)
    }

    /**
     * Releases everything: stops the session, cancels the coroutine scope, and closes the HTTP
     * client. The instance cannot be reused afterwards.
     */
    fun close() {
        stop()
        scope.cancel()
        httpClient.close()
    }
}

/**
 * Wraps [PlatformLocationProvider] with the rate limit from
 * [MSightClientConfig.locationUpdateFrequencyHz].
 *
 * The platform is asked for fixes as fast as it will supply them and the surplus is dropped
 * here, rather than asking the OS for a slower rate. That keeps the rate limit uniform across
 * platforms whose location APIs interpret a requested interval differently, and lets a fix be
 * delivered as soon as the interval elapses instead of waiting on the OS's own schedule.
 *
 * Filtering is by fix timestamp, not arrival time, so a burst of buffered fixes from the OS is
 * thinned correctly rather than passed through.
 */
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

/**
 * Reports the device's position to MSight Cloud.
 *
 * Uploads are fire-and-forget: each one is launched on the client's scope and its failure is
 * logged but not retried, because the next fix supersedes it anyway and a retry queue would only
 * push stale positions. The last fix is retained so it can be re-sent on reconnect — see
 * [resendLastKnownLocation].
 */
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

    /**
     * Re-sends the most recent fix, if there is one. Called on every WebSocket (re)connect so the
     * cloud's short-TTL location record is refreshed immediately rather than at the next fix.
     */
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

    /**
     * Nothing to release — in-flight uploads are cancelled with the client's scope. Kept so the
     * uploader matches the lifecycle shape of the other components.
     */
    fun close() {
    }
}

/**
 * Holds the WebSocket connection over which MSight Cloud pushes everything, and keeps it held.
 *
 * The endpoint is not configured: it is discovered from `GET /system/websocket-url` on each
 * connection attempt, so a deployment can move its WebSocket without clients being rebuilt. The
 * connection then carries `app_id` and `client_id` as query parameters, which is how the cloud
 * registers this device in its connection table.
 *
 * Every push is server-initiated; the client never sends a frame. When the socket drops, the
 * loop re-resolves the URL and reconnects with exponential backoff up to
 * [MAX_RECONNECT_DELAY_MILLIS], indefinitely — a moving vehicle loses connectivity routinely, so
 * a drop is treated as normal rather than as an error to report.
 */
private class MSightWebSocketConnection(
    private val config: MSightClientConfig,
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val onConnected: () -> Unit,
    private val onEvent: (MSightEvent) -> Unit
) {
    /** Connection lifecycle, tracked for diagnostics and to make [initialize] idempotent. */
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

    /**
     * Starts the connection loop and suspends until the first connection succeeds, so that a
     * caller learns about an unreachable cloud immediately. Once connected, the loop keeps
     * running in the background and later drops are handled by reconnection rather than by
     * failing this call again.
     */
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

    /**
     * Starts the connection loop without waiting for it — the non-suspending counterpart to
     * [initialize], for reconnecting after a [close] when no caller is in a position to await
     * the result.
     */
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

    /**
     * Resolve URL → connect → serve frames until the socket closes → back off → repeat, for as
     * long as the scope is active.
     *
     * [connectionReady] is completed on the first successful connection, or completed
     * exceptionally and the loop abandoned if that first attempt fails — an initial failure is
     * reported to the caller rather than retried silently. Once it has been completed, all later
     * failures are retried instead.
     */
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

    /** Asks the deployment where its WebSocket lives. Re-resolved on every attempt. */
    private suspend fun fetchWebSocketUrl(): String {
        val response = client.get(websocketUrlEndpoint)
        val responseBody = response.bodyAsText()

        return extractJsonStringField(
            json = responseBody,
            fieldName = "websocket_url"
        ) ?: error("websocket_url is missing from /system/websocket-url response")
    }

    /**
     * Opens the socket and reads frames until it closes. Returning normally means the peer went
     * away, which the caller treats as a cue to reconnect.
     */
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

/** Doubles the backoff delay, capped so a long outage still retries twice a minute. */
private fun nextReconnectDelayMillis(currentDelayMillis: Long): Long {
    return (currentDelayMillis * 2).coerceAtMost(MAX_RECONNECT_DELAY_MILLIS)
}

/**
 * Keeps intersection geometry loaded for wherever the device currently is.
 *
 * Fetching on every fix would mean a request per second for data that changes almost never, so
 * two conditions suppress the call: being inside the area already covered by a loaded map, and
 * not having moved far since the last fetch. Together they mean a stationary or slow-moving
 * device settles into making no map requests at all.
 *
 * A fetch that returns no maps is not recorded as coverage, so a device that crosses into a
 * mapped area will try again after moving [MAP_REFETCH_DISTANCE_METERS].
 */
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

/**
 * Combines position, intersection geometry and SPaT into [MSightSignalStateEvent] — the signal
 * colour facing this specific driver.
 *
 * The hard part is not the lookup but the stability. Approach detection runs per fix on noisy
 * consumer GNSS, so a naive implementation flickers: the display appears and disappears as the
 * detector's heading check passes and fails. Three mechanisms prevent that, and they are why this
 * class is a state machine rather than a function:
 *
 *  - **Locking in (IDLE → ACTIVE).** An arm must be detected [N_LOCK_FRAMES] times in a row
 *    before anything is displayed, which rejects a momentary match against a crossing arm.
 *  - **Holding on.** Once locked, the arm stays locked even if the detector later names a
 *    different one, and a lost detection only counts toward hiding while the device is actually
 *    moving. Standing at a red light is exactly when heading data degrades and also exactly when
 *    the driver most needs the display, so a stationary loss of detection is ignored.
 *  - **Letting go (ACTIVE → HIDING → IDLE).** [N_HIDE_FRAMES] consecutive losses while moving
 *    means the intersection has been passed, and the display is taken down.
 *
 * The fourth mechanism concerns the two SPaT streams: the critical stream reports a phase change
 * ahead of the rate-limited regular one, whose next frames still carry the old colour. Accepting
 * those would flash the display back to the previous colour, so a few regular updates are
 * suppressed after each critical one.
 */
private class MSightSignalProcessor(
    private val scope: CoroutineScope,
    private val onSignalState: (MSightSignalStateEvent) -> Unit
) {
    /**
     * IDLE: nothing displayed, watching for an arm to lock onto.
     * ACTIVE: an arm is locked and its colours are being emitted.
     * HIDING: the intersection has been passed; a take-down event is pending.
     */
    private enum class DisplayState { IDLE, ACTIVE, HIDING }

    /** Normalized latest-SPaT snapshot, agnostic to which stream produced it. */
    private data class LatestSpat(
        val intersectionName: String,
        val intersection: SpatIntersection,
        val timestampMillis: Long
    )

    private var displayState = DisplayState.IDLE
    private var loadedMaps: List<MSightIntersectionMap> = emptyList()
    private var activeApproach: ApproachResult? = null
    // Latest SPaT snapshot for the active intersection, sourced from either the regular
    // SPaT stream (onSpat) or the critical SPaT stream (onCriticalSpat). Both carry an
    // identical SpatIntersection, so they converge on one internal representation here.
    private var latestSpat: LatestSpat? = null

    // Number of upcoming regular-SPaT updates to ignore. A critical SPaT delivers the new
    // phase faster than the regular stream, so the regular stream may still emit a couple of
    // stale frames carrying the OLD color right after a phase change. Suppressing those
    // prevents a visible color flicker (new → old → new). Reset on each critical SPaT.
    private var suppressRegularSpatUpdates = 0

    // IDLE: count consecutive frames the detector returned the same arm id; promote to ACTIVE
    // after N_LOCK_FRAMES in a row. A null or arm-id change resets the streak.
    private var pendingArmId: Int? = null
    private var pendingFrames = 0

    // ACTIVE: count consecutive null frames while the user is moving; transition to HIDING
    // after N_HIDE_FRAMES. Null frames while stationary are ignored so the lock holds at red.
    private var nullFrames = 0

    private var hideJob: Job? = null
    private var enabled = false

    /**
     * Turns processing on or off. Switching off while a signal is displayed emits a final
     * take-down event first, so the host never has to infer that it should clear its display.
     */
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

    /**
     * Runs approach detection for one fix and advances the state machine.
     *
     * Ignored while HIDING: the take-down is already committed, and re-detecting the arm the
     * device has just left would only make the display reappear behind the driver.
     */
    fun onLocation(locationEvent: MSightLocationEvent, history: List<MSightTrajectoryPoint>) {
        if (!enabled) return
        if (displayState == DisplayState.HIDING) return

        println("INFO: SignalProcessor state=$displayState lat=%.6f lon=%.6f".format(
            locationEvent.latitude, locationEvent.longitude))

        val result = MSightApproachDetector.detectActiveApproach(locationEvent, history, loadedMaps)

        when (displayState) {
            DisplayState.IDLE -> handleIdle(locationEvent, result)
            DisplayState.ACTIVE -> handleActive(locationEvent, result)
            DisplayState.HIDING -> Unit
        }
    }

    /** Builds the same-arm streak and promotes to ACTIVE once it reaches [N_LOCK_FRAMES]. */
    private fun handleIdle(loc: MSightLocationEvent, result: ApproachResult?) {
        if (result == null) {
            pendingArmId = null
            pendingFrames = 0
            return
        }
        val armId = result.arm.armId
        if (pendingArmId == armId) {
            pendingFrames++
        } else {
            pendingArmId = armId
            pendingFrames = 1
        }
        println("INFO: SignalProcessor IDLE pendingArm=$armId frames=$pendingFrames/$N_LOCK_FRAMES")
        if (pendingFrames < N_LOCK_FRAMES) return

        // Promote to ACTIVE — the arm (and therefore its signal groups) is now locked.
        activeApproach = result
        displayState = DisplayState.ACTIVE
        pendingArmId = null
        pendingFrames = 0
        nullFrames = 0
        println("INFO: SignalProcessor IDLE→ACTIVE intersection=${result.intersection.name} armId=$armId")

        val spat = latestSpat
        if (spat != null && spat.intersectionName == result.intersection.name) {
            emitSignalState(loc.timestampMillis, result, spat.intersection)
        } else {
            // Render the overlay shell with UNKNOWN colors until the first matching SPaT arrives.
            val totalGroups = result.arm.straightSignalGroups.union(result.arm.leftTurnSignalGroups).size
            onSignalState(MSightSignalStateEvent(
                timestampMillis = loc.timestampMillis,
                intersectionName = result.intersection.name,
                straightColor = SignalColor.UNKNOWN,
                leftTurnColor = SignalColor.UNKNOWN,
                showSingleLight = totalGroups <= 1,
                straightSignalGroupIds = result.arm.straightSignalGroups.sorted(),
                leftTurnSignalGroupIds = result.arm.leftTurnSignalGroups.sorted()
            ))
        }
    }

    /**
     * Re-emits the locked arm's colours each fix, and counts toward HIDING when detection is
     * lost while moving.
     */
    private fun handleActive(loc: MSightLocationEvent, result: ApproachResult?) {
        if (result != null) {
            nullFrames = 0
            val locked = activeApproach
            if (locked != null && result.arm.armId != locked.arm.armId) {
                println("INFO: SignalProcessor arm locked=%d, ignoring detector arm=%d".format(
                    locked.arm.armId, result.arm.armId))
            }
            val spat = latestSpat
            if (locked != null && spat != null) {
                emitSignalState(loc.timestampMillis, locked, spat.intersection)
            }
            return
        }
        // result == null: only count toward HIDE while the user is moving. This keeps the
        // overlay stable at a red light, where heading may briefly drop out due to GPS jitter.
        val moving = (loc.speedMps ?: 0f) >= MOVING_SPEED_THRESHOLD_MPS
        if (!moving) {
            println("INFO: SignalProcessor ACTIVE result=null but stationary (speed=%.2f), holding lock".format(
                loc.speedMps ?: 0f))
            return
        }
        nullFrames++
        println("INFO: SignalProcessor ACTIVE result=null moving nullFrames=$nullFrames/$N_HIDE_FRAMES")
        if (nullFrames >= N_HIDE_FRAMES) {
            nullFrames = 0
            startHiding()
        }
    }

    /** Feeds a routine SPaT update in. Subject to post-critical suppression. */
    fun onSpat(spatEvent: MSightSpatEvent) {
        onSpatUpdate(
            spatEvent.intersectionName,
            spatEvent.intersection,
            spatEvent.timestampMillis,
            isCritical = false
        )
    }

    /** Feeds a phase-change SPaT update in. Always accepted, and starts the suppression window. */
    fun onCriticalSpat(criticalSpatEvent: MSightCriticalSpatEvent) {
        onSpatUpdate(
            criticalSpatEvent.intersectionName,
            criticalSpatEvent.intersection,
            criticalSpatEvent.timestampMillis,
            isCritical = true
        )
    }

    // Shared handling for both SPaT streams: both carry the same SpatIntersection, so a new
    // snapshot from either re-renders the active intersection's signal colors identically.
    private fun onSpatUpdate(
        intersectionName: String?,
        intersection: SpatIntersection,
        timestampMillis: Long,
        isCritical: Boolean
    ) {
        if (!enabled) return
        val name = intersectionName ?: return
        val approach = activeApproach ?: return
        if (name != approach.intersection.name) return

        if (isCritical) {
            // Authoritative phase-change snapshot — accept it and gate out the trailing
            // stale regular frames that would otherwise flicker the color back.
            suppressRegularSpatUpdates = REGULAR_SPAT_SUPPRESS_AFTER_CRITICAL
        } else if (suppressRegularSpatUpdates > 0) {
            suppressRegularSpatUpdates--
            return
        }

        latestSpat = LatestSpat(name, intersection, timestampMillis)

        if (displayState == DisplayState.ACTIVE) {
            emitSignalState(timestampMillis, approach, intersection)
        }
    }

    /** Cancels a pending take-down. Called when the whole session is being torn down. */
    fun cancel() {
        hideJob?.cancel()
        hideJob = null
    }

    /** Resolves the locked arm's signal groups against a SPaT snapshot and emits the result. */
    private fun emitSignalState(
        timestampMillis: Long,
        result: ApproachResult,
        intersection: SpatIntersection
    ) {
        val colors = MSightApproachDetector.extractArmSignals(result.arm, intersection)
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

    /**
     * Enters HIDING and schedules the take-down event. The delay is a seam for holding the
     * display briefly after the stop bar is crossed; it is currently near-zero.
     */
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

    /** Returns to IDLE, dropping the locked arm, the SPaT snapshot and all counters. */
    private fun clearState() {
        hideJob?.cancel()
        hideJob = null
        displayState = DisplayState.IDLE
        activeApproach = null
        latestSpat = null
        suppressRegularSpatUpdates = 0
        pendingArmId = null
        pendingFrames = 0
        nullFrames = 0
    }
}

/**
 * Turns one raw WebSocket frame into a typed event, or null if it is not a message this version
 * understands.
 *
 * Every push arrives in an envelope — `{app_id, event_id, message, server_timestamp}` — whose
 * `message` holds the actual payload. Both are searched for the discriminator field, because the
 * cloud's internal pipelines place it in slightly different spots.
 *
 * Unrecognised messages return null rather than throwing: the cloud can add message types at any
 * time, and an older client must keep working when one appears.
 */
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
            CRITICAL_SPAT_MESSAGE_TYPE -> parseCriticalSpatEvent(payloadJson, eventId)
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

/**
 * Parses a free-text warning, falling back to the envelope's `server_timestamp` when the payload
 * carries no timestamp of its own, and to the local clock when neither does.
 */
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

/**
 * Extracts a nested JSON object as raw text by brace matching, without building a document.
 *
 * Used to lift `message` out of the push envelope before deciding what it is, so the payload can
 * be handed to whichever parser turns out to be the right one.
 *
 * Brace matching ignores string literals, so an object containing a `{` or `}` inside a string
 * value would be cut short. That is acceptable here because the field it is applied to is always
 * the envelope's `message`, whose immediate structure the cloud controls.
 */
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

/**
 * Builds the body for `POST /v1/clients/location/update`.
 *
 * Optional fields are omitted rather than sent as null, because the cloud's schema validates
 * them as optional-but-typed and would reject a null. Written by hand instead of with
 * kotlinx.serialization to keep that omission explicit and avoid a serializer for a single
 * outbound shape.
 */
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

/**
 * Converts an update frequency in Hz to the minimum interval between updates. A non-positive
 * frequency yields `0`, which disables rate limiting.
 */
private fun Double.toMinUpdateIntervalMillis(): Long {
    if (this <= 0.0) {
        return 0L
    }

    return (1000.0 / this).roundToLong().coerceAtLeast(1L)
}

/**
 * Maps a platform location-provider name onto the cloud's `LocationSource` enum. Anything
 * unrecognised becomes `unknown`, since an out-of-enum value would fail schema validation.
 */
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

/**
 * Reads a top-level-ish string field out of raw JSON by regex, for the few cases where a full
 * parse is not worth it — the envelope's discriminator and ids.
 *
 * Finds the first match anywhere in the text, nesting included, and does not handle escaped
 * quotes inside the value. Fine for the identifier fields it is used on; not a general accessor.
 */
private fun extractJsonStringField(json: String, fieldName: String): String? {
    val pattern = Regex("\"" + Regex.escape(fieldName) + "\"\\s*:\\s*\"([^\"]+)\"")
    return pattern.find(json)?.groupValues?.getOrNull(1)
}

/**
 * Converts the SDSM's own broken-down timestamp to epoch milliseconds.
 *
 * `offset` is minutes east of UTC, and `toInstant(UTC)` treats the components as if they were
 * already UTC, so the offset has to be subtracted afterwards to recover true UTC.
 *
 * Returns null if the components do not form a valid date — a malformed frame should be skipped,
 * not crash the reader.
 */
private fun sdsmTimestampToMillis(ts: SdsmTimestamp): Long? {
    return runCatching {
        val secondInt = ts.second.toInt()
        val nanoOfSecond = ((ts.second - secondInt) * 1_000_000_000.0).roundToLong().toInt()
        val ldt = LocalDateTime(ts.year, ts.month, ts.day, ts.hour, ts.minute, secondInt, nanoOfSecond)
        // ts.offset is minutes east of UTC; toInstant(UTC) treats components as UTC, so subtract offset
        ldt.toInstant(TimeZone.UTC).toEpochMilliseconds() - ts.offset * 60_000L
    }.getOrNull()
}

// Logging goes to stdout via println, which is the only output every Kotlin Multiplatform target
// shares. On Android this surfaces in Logcat. A host wanting structured logging should replace
// these two functions with a delegate of its own.

private fun logInfo(message: String) {
    println("INFO: $message")
}

private fun logError(message: String) {
    println("ERROR: $message")
}

/** Renders a throwable with its full cause chain, since the root cause is usually the useful one. */
private fun describeThrowable(throwable: Throwable): String {
    val segments = mutableListOf<String>()
    var current: Throwable? = throwable

    while (current != null) {
        segments += current.toString()
        current = current.cause
    }

    return segments.joinToString(" <- caused by ")
}

// ── MSight Cloud API paths ───────────────────────────────────────────────────
// All relative to MSightClientConfig.cloudUrl. See the deployment's own OpenAPI document at
// GET /system/docs for the full contract.

private const val LOCATION_UPDATE_PATH = "/v1/clients/location/update"
private const val WEBSOCKET_URL_PATH = "/system/websocket-url"
private const val MAP_SEARCH_PATH = "/v1/maps/search"
private const val MAP_BY_NAME_PATH = "/v1/maps/"

// ── WebSocket reconnection ───────────────────────────────────────────────────

private const val INITIAL_RECONNECT_DELAY_MILLIS = 1_000L
private const val MAX_RECONNECT_DELAY_MILLIS = 30_000L

// ── Message type discriminators ──────────────────────────────────────────────
// Values of the `type` field the cloud sets on each push. `msight_simple_warning` belongs to the
// older `message_type` field, still accepted for compatibility with existing microservices.

private const val SIMPLE_WARNING_MESSAGE_TYPE = "msight_simple_warning"
private const val SDSM_MESSAGE_TYPE = "sdsm"
private const val SPAT_MESSAGE_TYPE = "spat"
private const val CRITICAL_SPAT_MESSAGE_TYPE = "critical_spat"

// ── Map loading ──────────────────────────────────────────────────────────────

/** Radius passed to the cloud's map search — comfortably more than one intersection's extent. */
private const val MAP_FETCH_RADIUS_METERS = 150

/**
 * How close to a loaded map's centre counts as "already covered". Deliberately smaller than
 * [MAP_FETCH_RADIUS_METERS] so the next fetch is triggered before the device leaves the area the
 * current maps describe.
 */
private const val MAP_LOADED_ZONE_METERS = 100

/** Minimum movement between fetches when outside every loaded map's coverage. */
private const val MAP_REFETCH_DISTANCE_METERS = 50.0

// ── Signal state machine ─────────────────────────────────────────────────────

/**
 * How much recent track to retain. Two minutes is enough to hold the last clearly-moving fix
 * through a full red phase, which is what heading inference falls back on when stopped.
 */
private const val TRAJECTORY_HISTORY_MILLIS = 120_000L

/** Delay between deciding the intersection has been passed and emitting the take-down event. */
private const val POST_PASS_HIDE_DELAY_MILLIS = 50L

// Consecutive same-arm detections required to promote IDLE → ACTIVE.
private const val N_LOCK_FRAMES = 3
// Consecutive null detections (while moving) required to promote ACTIVE → HIDING.
private const val N_HIDE_FRAMES = 3
// Speed threshold below which a null detection is ignored — keeps the overlay locked at red.
private const val MOVING_SPEED_THRESHOLD_MPS = 2.0f
// Regular-SPaT updates to ignore after a critical SPaT delivers a new phase, to avoid the
// flicker caused by trailing stale frames on the slower regular stream.
private const val REGULAR_SPAT_SUPPRESS_AFTER_CRITICAL = 2

/**
 * Unknown keys are ignored throughout, so that a cloud deployment adding a field to any payload
 * does not break clients already in the field.
 */
private val lenientJson = Json { ignoreUnknownKeys = true }

// ── Message parsing ──────────────────────────────────────────────────────────
// Each parser returns null on a malformed or incomplete message rather than throwing, so one bad
// frame is logged and skipped instead of killing the WebSocket reader.

/** Parses an `sdsm` push into an [MSightSdsmEvent]. */
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

/** Parses a routine `spat` push. Logs which field was missing when it fails, since a silently
 * dropped SPaT is otherwise hard to distinguish from a signal that is simply not reporting. */
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

/** Parses a `critical_spat` push. Same payload as [parseSpatEvent] minus the frame id. */
private fun parseCriticalSpatEvent(messageJson: String, eventId: String? = null): MSightCriticalSpatEvent? {
    return try {
        val msg = lenientJson.parseToJsonElement(messageJson).jsonObject

        val spatObj = msg["spat"]?.jsonObject ?: run {
            logError("parseCriticalSpatEvent: missing 'spat' key")
            return null
        }
        val captureTimestamp = msg["capture_timestamp"]?.jsonPrimitive?.doubleOrNull ?: run {
            logError("parseCriticalSpatEvent: missing 'capture_timestamp'")
            return null
        }
        val timestampMillis = (captureTimestamp * 1000.0).roundToLong()

        val intersection = parseSpatIntersection(spatObj) ?: run {
            logError("parseCriticalSpatEvent: parseSpatIntersection returned null, spatObj keys=${spatObj.keys}")
            return null
        }
        val sensorName = msg["sensor_name"]?.jsonPrimitive?.contentOrNull ?: run {
            logError("parseCriticalSpatEvent: missing 'sensor_name'")
            return null
        }
        val deviceName = msg["device_name"]?.jsonPrimitive?.contentOrNull ?: run {
            logError("parseCriticalSpatEvent: missing 'device_name'")
            return null
        }
        val creationTimestamp = msg["creation_timestamp"]?.jsonPrimitive?.doubleOrNull ?: run {
            logError("parseCriticalSpatEvent: missing 'creation_timestamp'")
            return null
        }

        MSightCriticalSpatEvent(
            timestampMillis = timestampMillis,
            eventId = eventId,
            sensorName = sensorName,
            deviceName = deviceName,
            captureTimestamp = captureTimestamp,
            creationTimestamp = creationTimestamp,
            intersectionName = msg["intersection_name"]?.jsonPrimitive?.contentOrNull,
            name = spatObj["name"]?.jsonPrimitive?.contentOrNull,
            intersection = intersection
        )
    } catch (e: Exception) {
        logError("parseCriticalSpatEvent failed: ${describeThrowable(e)}")
        null
    }
}

/** Parses the `intersection` body shared by both SPaT streams. */
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

/**
 * Parses one signal group's state list. A `timing` block missing its mandatory `minEndTime` is
 * treated as absent rather than as a reason to drop the state — the phase colour is still usable
 * without a countdown.
 */
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

/**
 * Parses one detected object out of an SDSM's `objects` array.
 *
 * `detObjOptData` is a J2735 CHOICE, encoded in JSON as a two-element array of
 * `[discriminator, value]`. Only the `detVeh` branch carries anything the library uses (vehicle
 * class and bounding box); other branches are skipped.
 */
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

/** `GET /v1/maps/search` — every intersection map within [radiusMeters] of a point. */
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

/** `GET /v1/maps/{name}` — one intersection map by name. */
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

/**
 * Parses whichever of the map-response shapes came back.
 *
 * The two map endpoints wrap their results differently — a radius search returns `{"maps": [...]}`
 * while a name lookup returns `{"status": ..., "map": {...}}` — so both are accepted, along with a
 * bare array or a bare map object, and all normalise to a list.
 */
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

/**
 * Parses one map record into an [MSightIntersectionMap].
 *
 * Only the first intersection in the record's `intersections` array is read: MSight Cloud stores
 * one intersection per named map, so a second entry would not be addressable by name anyway.
 *
 * Anything missing the geometry the library needs — reference point, lane set, intersection id —
 * yields null, since a partially parsed map would silently produce wrong approach matches.
 */
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

/**
 * Parses one lane, flattening J2735's relative node deltas into absolute offsets from the
 * intersection reference point.
 *
 * Three running totals are accumulated along the node chain, because J2735 encodes each as a
 * delta from the previous node: position (`x`/`y`), lane width (`dWidth`, starting from the
 * intersection default) and elevation (`dElevation`, starting from the reference point). Doing
 * this once here is what lets [MSightApproachDetector] treat a lane as plain geometry.
 *
 * Crosswalk lanes return null — they are not lanes a vehicle approaches on.
 */
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

/**
 * Parses one `connectsTo` entry, keeping only its signal group. Which egress lane the movement
 * leads to is not retained — the library needs to know what governs the movement, not where it
 * goes.
 */
private fun parseMapLaneConnection(connObj: JsonObject): MapLaneConnection? {
    return try {
        val signalGroup = connObj["signalGroup"]?.jsonPrimitive?.intOrNull ?: return null
        MapLaneConnection(signalGroup = signalGroup)
    } catch (e: Exception) {
        null
    }
}

/**
 * Groups lanes into arms and works out which signal groups govern which movement.
 *
 * J2735 records that an ingress lane connects to some egress lane under some signal group, but
 * not that the movement is "a left turn". The classification here recovers that from lane
 * ordering, using the fact that a left-turn pocket sits at the inside edge of the roadway:
 *
 *  - Its **left** neighbour is an *egress* lane — the opposing direction, across the centreline.
 *  - A through lane's left neighbour is either another ingress lane or nothing at all.
 *
 * A lane whose neighbours match neither pattern (typically a shared through/left lane, with
 * egress on the left *and* on the right) has its signal groups added to both movements, so it is
 * reported rather than dropped.
 *
 * Right turns are not classified: the geometry does not distinguish a right-turn lane from a
 * through lane the same way, and a right turn is usually permitted on the through phase anyway.
 */
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
            ingressLanes = ingressLanes,
            egressLanes = egressLanes,
            straightSignalGroups = straight,
            leftTurnSignalGroups = leftTurn,
            rightTurnSignalGroups = rightTurn
        )
    }
}

/** Great-circle distance between two WGS 84 points, in metres. */
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