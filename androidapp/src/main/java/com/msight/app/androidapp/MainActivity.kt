package com.msight.app.androidapp

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.msight.app.androidapp.ui.theme.MsightappclientlibraryTheme
import com.msight.app.client.MSightClient
import com.msight.app.client.MSightClientConfig
import com.msight.app.client.MSightDeviceType
import com.msight.app.client.MSightLocationEvent
import com.msight.app.client.MSightRoadUserType
import com.msight.app.client.MSightSdsmEvent
import com.msight.app.client.MSightSimpleWarning
import com.msight.app.client.MSightSpatEvent
import com.msight.app.client.SdsmDetectedObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.msight.app.client.MSightSignalStateEvent
import com.msight.app.client.SignalColor
import com.msight.app.client.MapRefPoint
import com.msight.app.client.MapLane
import com.msight.app.client.MSightIntersectionMap
import com.google.maps.android.compose.Polygon
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val WARNING_AUTO_DISMISS_MILLIS = 3_000L
private const val WARNING_RESHOW_DELAY_MILLIS = 180L

enum class SdsmFilter(val label: String) {
    OUSTER("Ouster"),
    DERQ("DeRQ"),
    MSIGHT("MSight")
}

sealed class ClientState {
    object Idle : ClientState()
    object Starting : ClientState()
    data class Running(val config: MSightClientConfig) : ClientState()
    data class Error(val message: String) : ClientState()
}

class MainActivity : ComponentActivity() {

    private var clientState by mutableStateOf<ClientState>(ClientState.Idle)
    private var latestLocation by mutableStateOf<MSightLocationEvent?>(null)
    private var latestWarning by mutableStateOf<MSightSimpleWarning?>(null)
    private var warningVisible by mutableStateOf(false)
    private var warningResetKey by mutableStateOf(0)
    private var latestSdsmEvent by mutableStateOf<MSightSdsmEvent?>(null)
    private var activeIntersectionName by mutableStateOf<String?>(null)
    private var straightSignalColor by mutableStateOf(SignalColor.UNKNOWN)
    private var leftSignalColor by mutableStateOf(SignalColor.UNKNOWN)
    private var showSingleLight by mutableStateOf(false)
    private var straightGroupIds by mutableStateOf<List<Int>>(emptyList())
    private var leftGroupIds by mutableStateOf<List<Int>>(emptyList())
    private var showSpatOverlay by mutableStateOf(false)
    private val spatMapCache = mutableStateMapOf<String, MSightIntersectionMap?>()
    private val latestSpatEvents = mutableStateMapOf<String, MSightSpatEvent>()

    private var pendingConfig: MSightClientConfig? = null
    private var activeClient: MSightClient? = null
    private var warningDisplayJob: Job? = null
    private var currentWarningEventId: String? = null
    private var warningPlayer: MediaPlayer? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startPendingClient()
        } else {
            clientState = ClientState.Error("Location permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MsightappclientlibraryTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MSightScreen(
                        clientState = clientState,
                        latestLocation = latestLocation,
                        latestWarning = latestWarning,
                        warningVisible = warningVisible,
                        warningResetKey = warningResetKey,
                        latestSdsmEvent = latestSdsmEvent,
                        activeIntersectionName = activeIntersectionName,
                        straightSignalColor = straightSignalColor,
                        leftSignalColor = leftSignalColor,
                        showSingleLight = showSingleLight,
                        straightGroupIds = straightGroupIds,
                        leftGroupIds = leftGroupIds,
                        showSpatOverlay = showSpatOverlay,
                        spatMapCache = spatMapCache,
                        latestSpatEvents = latestSpatEvents,
                        onStart = { config -> requestPermissionsAndStart(config) },
                        onStop = { stopClient() },
                        onSpatToggle = { enabled ->
                            showSpatOverlay = enabled
                            activeClient?.setSpatEnabled(enabled)
                        },
                        onDismissWarning = {
                            warningVisible = false
                            currentWarningEventId = null
                            warningDisplayJob?.cancel()
                            warningDisplayJob = null
                            stopWarningSound()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        lifecycleScope.launch(Dispatchers.IO) { activeClient?.close() }
        warningPlayer?.release()
        warningPlayer = null
        super.onDestroy()
    }

    private fun requestPermissionsAndStart(config: MSightClientConfig) {
        pendingConfig = config
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            startPendingClient()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startPendingClient() {
        val config = pendingConfig ?: return
        pendingConfig = null
        clientState = ClientState.Starting

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = MSightClient(context = applicationContext, config = config)
                activeClient = client

                launch {
                    client.events.collect { event ->
                        when (event) {
                            is MSightLocationEvent -> {
                                latestLocation = event
                            }

                            is MSightSimpleWarning -> {
                                showWarning(event)
                            }

                            is MSightSdsmEvent -> {
                                latestSdsmEvent = event
                                Log.d("MSight-SDSM", "sensor=${event.sensorName} ts=${event.timestampMillis} objects=${event.objects.size}")
                            }

                            is MSightSpatEvent -> {
                                val intName = event.intersectionName ?: event.intersection.name
                                if (intName != null) {
                                    latestSpatEvents[intName] = event
                                    if (!spatMapCache.containsKey(intName)) {
                                        spatMapCache[intName] = null
                                        Log.d("MSight-SPAT-B", "Initiating map load for intName=$intName")
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            val result = client.loadMapsByName(intName).firstOrNull()
                                            Log.d("MSight-SPAT-B", "Map load result for intName=$intName: ${result?.name ?: "null (no map)"}")
                                            spatMapCache[intName] = result
                                        }
                                    }
                                }
                                Log.d("MSight-SPAT", "sensor=${event.sensorName} name=${event.intersectionName} intId=${event.intersection.id.id} signals=${event.intersection.states.size}")
                            }

                            is MSightSignalStateEvent -> {
                                activeIntersectionName = event.intersectionName
                                straightSignalColor = event.straightColor
                                leftSignalColor = event.leftTurnColor
                                showSingleLight = event.showSingleLight
                                straightGroupIds = event.straightSignalGroupIds
                                leftGroupIds = event.leftTurnSignalGroupIds
                            }

                            else -> {
                                Log.d("MSight", "Received event: $event")
                            }
                        }
                    }
                }

                client.start()
                client.setSpatEnabled(showSpatOverlay)
                clientState = ClientState.Running(config)
            } catch (e: Exception) {
                Log.e("MSight", "Failed to start MSightClient: $e")
                activeClient = null
                clientState = ClientState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun stopClient() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                activeClient?.close()
            } catch (e: Exception) {
                Log.e("MSight", "Error stopping client: $e")
            } finally {
                warningDisplayJob?.cancel()
                warningDisplayJob = null
                currentWarningEventId = null
                stopWarningSound()
                activeClient = null
                clientState = ClientState.Idle
                latestLocation = null
                warningVisible = false
                activeIntersectionName = null
                straightSignalColor = SignalColor.UNKNOWN
                leftSignalColor = SignalColor.UNKNOWN
                showSingleLight = false
                straightGroupIds = emptyList()
                leftGroupIds = emptyList()
                spatMapCache.clear()
                latestSpatEvents.clear()
            }
        }
    }

    private fun showWarning(warning: MSightSimpleWarning) {
        val isSameEvent = warningVisible
                && warning.eventId != null
                && warning.eventId == currentWarningEventId

        warningDisplayJob?.cancel()
        warningDisplayJob = lifecycleScope.launch {
            if (!isSameEvent) {
                // New or different event — hide current banner first if visible
                if (warningVisible) {
                    warningVisible = false
                    delay(WARNING_RESHOW_DELAY_MILLIS)
                }
                latestWarning = warning
                currentWarningEventId = warning.eventId
                warningVisible = true
                Log.d("MSight", "Received warning: $warning")
                startWarningSound()
            } else {
                // Same event still active — extend the timeout, no visual restart
                Log.d("MSight", "Extending warning timeout for event_id=${warning.eventId}")
            }
            warningResetKey++

            delay(WARNING_AUTO_DISMISS_MILLIS)
            warningVisible = false
            currentWarningEventId = null
            stopWarningSound()
        }
    }

    private fun startWarningSound() {
        stopWarningSound()
        warningPlayer = MediaPlayer.create(
            this,
            R.raw.warning_sound
        ).apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = true
            start()
        }
    }

    private fun stopWarningSound() {
        warningPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        warningPlayer = null
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MSightScreen(
    clientState: ClientState,
    latestLocation: MSightLocationEvent?,
    latestWarning: MSightSimpleWarning?,
    warningVisible: Boolean,
    warningResetKey: Int,
    latestSdsmEvent: MSightSdsmEvent?,
    activeIntersectionName: String?,
    straightSignalColor: SignalColor,
    leftSignalColor: SignalColor,
    showSingleLight: Boolean,
    straightGroupIds: List<Int>,
    leftGroupIds: List<Int>,
    showSpatOverlay: Boolean,
    spatMapCache: Map<String, MSightIntersectionMap?>,
    latestSpatEvents: Map<String, MSightSpatEvent>,
    onStart: (MSightClientConfig) -> Unit,
    onStop: () -> Unit,
    onSpatToggle: (Boolean) -> Unit,
    onDismissWarning: () -> Unit
) {
    val isActive = clientState is ClientState.Running || clientState is ClientState.Starting

    // Config state lives here so it survives screen transitions
    var cloudUrl by remember { mutableStateOf("https://7hmptbe8s3.execute-api.us-east-2.amazonaws.com") }
    var appId by remember { mutableStateOf("msight-demo") }
    var clientId by remember { mutableStateOf("client-001") }
    var roadUserType by remember { mutableStateOf(MSightRoadUserType.VEHICLE) }
    var roadUserSubType by remember { mutableStateOf("passenger_car") }
    var deviceType by remember { mutableStateOf(MSightDeviceType.CELLPHONE) }
    var locationFreqHz by remember { mutableStateOf("1.0") }

    if (isActive) {
        ActiveMapScreen(
            clientState = clientState,
            latestLocation = latestLocation,
            latestWarning = latestWarning,
            warningVisible = warningVisible,
            warningResetKey = warningResetKey,
            latestSdsmEvent = latestSdsmEvent,
            activeIntersectionName = activeIntersectionName,
            straightSignalColor = straightSignalColor,
            leftSignalColor = leftSignalColor,
            showSingleLight = showSingleLight,
            straightGroupIds = straightGroupIds,
            leftGroupIds = leftGroupIds,
            showSpatOverlay = showSpatOverlay,
            spatMapCache = spatMapCache,
            latestSpatEvents = latestSpatEvents,
            onStop = onStop,
            onSpatToggle = onSpatToggle,
            onDismissWarning = onDismissWarning
        )
    } else {
        ConfigScreen(
            clientState = clientState,
            cloudUrl = cloudUrl,
            onCloudUrlChange = { cloudUrl = it },
            appId = appId,
            onAppIdChange = { appId = it },
            clientId = clientId,
            onClientIdChange = { clientId = it },
            roadUserType = roadUserType,
            onRoadUserTypeChange = { roadUserType = it },
            roadUserSubType = roadUserSubType,
            onRoadUserSubTypeChange = { roadUserSubType = it },
            deviceType = deviceType,
            onDeviceTypeChange = { deviceType = it },
            locationFreqHz = locationFreqHz,
            onLocationFreqHzChange = { locationFreqHz = it },
            onStart = {
                onStart(
                    MSightClientConfig(
                        cloudUrl = cloudUrl.trim(),
                        appId = appId.trim(),
                        clientId = clientId.trim(),
                        roadUserType = roadUserType,
                        roadUserSubType = roadUserSubType.trim(),
                        deviceType = deviceType,
                        locationUpdateFrequencyHz = locationFreqHz.toDoubleOrNull() ?: 1.0
                    )
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(
    clientState: ClientState,
    cloudUrl: String,
    onCloudUrlChange: (String) -> Unit,
    appId: String,
    onAppIdChange: (String) -> Unit,
    clientId: String,
    onClientIdChange: (String) -> Unit,
    roadUserType: MSightRoadUserType,
    onRoadUserTypeChange: (MSightRoadUserType) -> Unit,
    roadUserSubType: String,
    onRoadUserSubTypeChange: (String) -> Unit,
    deviceType: MSightDeviceType,
    onDeviceTypeChange: (MSightDeviceType) -> Unit,
    locationFreqHz: String,
    onLocationFreqHzChange: (String) -> Unit,
    onStart: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "MSight Client",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            StatusBadge(clientState)

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = cloudUrl,
                onValueChange = onCloudUrlChange,
                label = { Text("Cloud URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = appId,
                onValueChange = onAppIdChange,
                label = { Text("App ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = clientId,
                onValueChange = onClientIdChange,
                label = { Text("Client ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            EnumDropdown(
                label = "Road User Type",
                options = MSightRoadUserType.entries,
                selected = roadUserType,
                onSelect = onRoadUserTypeChange,
                enabled = true,
                displayName = { it.name }
            )

            OutlinedTextField(
                value = roadUserSubType,
                onValueChange = onRoadUserSubTypeChange,
                label = { Text("Road User Sub-Type") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            EnumDropdown(
                label = "Device Type",
                options = MSightDeviceType.entries,
                selected = deviceType,
                onSelect = onDeviceTypeChange,
                enabled = true,
                displayName = { it.name }
            )

            OutlinedTextField(
                value = locationFreqHz,
                onValueChange = onLocationFreqHzChange,
                label = { Text("Location Frequency (Hz)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (clientState is ClientState.Error) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Error: ${clientState.message}",
                        color = Color(0xFFD32F2F),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
            ) {
                Text(
                    text = "Start",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(64.dp))
        }
    }
}

@Composable
private fun ActiveMapScreen(
    clientState: ClientState,
    latestLocation: MSightLocationEvent?,
    latestWarning: MSightSimpleWarning?,
    warningVisible: Boolean,
    warningResetKey: Int,
    latestSdsmEvent: MSightSdsmEvent?,
    activeIntersectionName: String?,
    straightSignalColor: SignalColor,
    leftSignalColor: SignalColor,
    showSingleLight: Boolean,
    straightGroupIds: List<Int>,
    leftGroupIds: List<Int>,
    showSpatOverlay: Boolean,
    spatMapCache: Map<String, MSightIntersectionMap?>,
    latestSpatEvents: Map<String, MSightSpatEvent>,
    onStop: () -> Unit,
    onSpatToggle: (Boolean) -> Unit,
    onDismissWarning: () -> Unit
) {
    var infoPanelVisible by remember { mutableStateOf(false) }
    var sdsmFilter by remember { mutableStateOf(SdsmFilter.OUSTER) }
    val markerCache = remember { HashMap<String, BitmapDescriptor>() }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 5f)
    }

    var hasInitialLocation by remember { mutableStateOf(false) }

    // Stable map: objectID -> (LatLng, SdsmDetectedObject) — updated in-place to avoid marker flash
    val objectPositions = remember { mutableStateMapOf<Int, Pair<LatLng, SdsmDetectedObject>>() }
    // Timestamp of the last frame that was written into objectPositions (for split-frame merging)
    val lastFrameTimestamp = remember { mutableStateOf<Long?>(null) }

    // Clear stale objects whenever the filter selection changes
    LaunchedEffect(sdsmFilter) {
        objectPositions.clear()
        lastFrameTimestamp.value = null
    }

    LaunchedEffect(latestSdsmEvent) {
        val event = latestSdsmEvent
        if (event == null) {
            objectPositions.clear()
            lastFrameTimestamp.value = null
            return@LaunchedEffect
        }
        val showEvent = when (sdsmFilter) {
            SdsmFilter.OUSTER -> event.sensorName.startsWith("ouster", ignoreCase = true)
            SdsmFilter.DERQ -> event.sensorName.startsWith("derq", ignoreCase = true)
            SdsmFilter.MSIGHT -> event.sensorName.startsWith("msight", ignoreCase = true)
        }
        // If this event is from a source we are not showing, ignore it entirely —
        // do NOT clear, so already-displayed objects from the correct source stay visible.
        if (!showEvent) return@LaunchedEffect

        val prev = lastFrameTimestamp.value
        val isSameFrame = prev != null && Math.abs(event.timestampMillis - prev) <= 50L

        if (isSameFrame) {
            // Split fragment of the same logical frame — merge objects into the existing map
            event.objects.forEach { obj ->
                val (lat, lon) = computeObjectLatLon(
                    event.refPos.lat, event.refPos.long,
                    obj.pos.offsetX, obj.pos.offsetY
                )
                objectPositions[obj.objectID] = LatLng(lat, lon) to obj
            }
        } else {
            // New frame — replace the map with only the objects in this message
            val newIds = event.objects.map { it.objectID }.toSet()
            objectPositions.keys.retainAll(newIds)
            event.objects.forEach { obj ->
                val (lat, lon) = computeObjectLatLon(
                    event.refPos.lat, event.refPos.long,
                    obj.pos.offsetX, obj.pos.offsetY
                )
                objectPositions[obj.objectID] = LatLng(lat, lon) to obj
            }
        }
        lastFrameTimestamp.value = event.timestampMillis
    }

    LaunchedEffect(latestLocation) {
        latestLocation?.let { loc ->
            val latLng = LatLng(loc.latitude, loc.longitude)
            if (!hasInitialLocation) {
                hasInitialLocation = true
                cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(latLng, 20f))
            } else {
                cameraPositionState.animate(CameraUpdateFactory.newLatLng(latLng))
            }
        }
    }

    SideEffect {
        val loadedMaps = spatMapCache.values.count { it != null }
        Log.d("MSight-B-UI", "recompose overlay=$showSpatOverlay events=${latestSpatEvents.size} loadedMaps=$loadedMaps")
        latestSpatEvents.forEach { (name, _) ->
            val m = spatMapCache[name]
            Log.d("MSight-B-UI", "  $name map=${m?.name ?: "null"} arms=${m?.arms?.size ?: 0}")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = true,
                mapType = MapType.SATELLITE
            ),
            uiSettings = MapUiSettings(
                myLocationButtonEnabled = false,
                zoomControlsEnabled = false,
                compassEnabled = true
            )
        ) {
            objectPositions.forEach { (id, pair) ->
                val entryLatLng: LatLng = pair.first
                val entryObj: SdsmDetectedObject = pair.second
                key(id) {
                    val markerState = remember { MarkerState(position = entryLatLng) }
                    SideEffect { markerState.position = entryLatLng }
                    val headingBucket = (entryObj.heading / 10.0).toInt() * 10.0
                    val cacheKey = "${entryObj.objectType}_$headingBucket"
                    val markerIcon = markerCache.getOrPut(cacheKey) {
                        BitmapDescriptorFactory.fromBitmap(
                            createObjectMarkerBitmap(entryObj.objectType, headingBucket)
                        )
                    }
                    Marker(
                        state = markerState,
                        icon = markerIcon,
                        anchor = Offset(0.5f, 0.5f),
                        title = "${entryObj.objectType} #${entryObj.objectID}",
                        snippet = "spd=${entryObj.speed} hdg=${String.format(Locale.US, "%.1f", entryObj.heading)}°"
                    )
                }
            }

            // task_B: render one merged rectangle per signal group per arm
            latestSpatEvents.forEach forEachIntersection@{ (name, spatEvent) ->
                val intMap = spatMapCache[name] ?: return@forEachIntersection
                val statesByGroup = spatEvent.intersection.states.associateBy { it.signalGroup }
                intMap.arms.forEach { arm ->
                    // Group ingress lanes by unique signal group; dedup duplicate connections
                    val lanesBySignalGroup = mutableMapOf<Int, MutableList<MapLane>>()
                    arm.ingressLanes.forEach { lane ->
                        lane.connections.map { it.signalGroup }.toSet().forEach { sg ->
                            lanesBySignalGroup.getOrPut(sg) { mutableListOf() }.add(lane)
                        }
                    }
                    lanesBySignalGroup.forEach forEachGroup@{ (sg, lanes) ->
                        val color = statesByGroup[sg]
                            ?.stateTimeSpeed?.firstOrNull()?.eventState
                            ?.let { SignalColor.fromEventState(it) }
                            ?: SignalColor.UNKNOWN
                        val corners = mergedLaneRectangleCorners(intMap.refPoint, lanes)
                            ?: return@forEachGroup
                        Polygon(
                            points = corners,
                            fillColor = color.toMapOverlayFill(),
                            strokeColor = color.toMapOverlayFill(),
                            strokeWidth = 0f
                        )
                    }
                }
            }
        }

        // Top-left: SPaT display toggle
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 12.dp, start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            FloatingActionButton(
                onClick = { onSpatToggle(!showSpatOverlay) },
                containerColor = if (showSpatOverlay) Color(0xFF2E7D32) else Color(0xFF607D8B),
                modifier = Modifier.size(56.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Traffic",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 11.sp
                    )
                    Text(
                        text = "Light",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 11.sp
                    )
                    Text(
                        text = if (showSpatOverlay) "ON" else "OFF",
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 10.sp
                    )
                }
            }
        }

        // Top-right floating button: Info toggle only
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 12.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End
        ) {
            FloatingActionButton(
                onClick = { infoPanelVisible = !infoPanelVisible },
                containerColor = if (infoPanelVisible) Color(0xFF1565C0) else Color(0xFF1976D2),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "Info",
                    tint = Color.White
                )
            }
            // SDSM filter cycle button
            FloatingActionButton(
                onClick = {
                    sdsmFilter = when (sdsmFilter) {
                        SdsmFilter.OUSTER -> SdsmFilter.DERQ
                        SdsmFilter.DERQ -> SdsmFilter.MSIGHT
                        SdsmFilter.MSIGHT -> SdsmFilter.OUSTER
                    }
                },
                containerColor = when (sdsmFilter) {
                    SdsmFilter.OUSTER -> Color(0xFFE65100)
                    SdsmFilter.DERQ -> Color(0xFF0277BD)
                    SdsmFilter.MSIGHT -> Color(0xFF6A1B9A)
                },
                modifier = Modifier.size(48.dp)
            ) {
                Text(
                    text = sdsmFilter.label,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Bottom-center STOP button
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 32.dp)
        ) {
            Button(
                onClick = onStop,
                modifier = Modifier
                    .height(52.dp)
                    .width(160.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 8.dp,
                    pressedElevation = 2.dp
                )
            ) {
                Text(
                    text = "STOP",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 3.sp
                )
            }
        }

        // Info panel slides in from the right
        AnimatedVisibility(
            visible = infoPanelVisible,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 118.dp, end = 8.dp, start = 56.dp),
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(tween(250)),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(tween(200))
        ) {
            InfoPanel(clientState = clientState, latestLocation = latestLocation)
        }

        // Signal light overlay — shown when approaching an intersection and SPaT display is enabled
        if (showSpatOverlay && activeIntersectionName != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 12.dp)
            ) {
                SignalOverlay(
                    intersectionName = activeIntersectionName,
                    straightColor = straightSignalColor,
                    leftColor = leftSignalColor,
                    showSingleLight = showSingleLight,
                    straightGroupIds = straightGroupIds,
                    leftGroupIds = leftGroupIds
                )
            }
        }

        // Warning banner drops in from top with spring bounce
        AnimatedVisibility(
            visible = warningVisible,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp, start = 12.dp, end = 12.dp),
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
                initialOffsetY = { -it }
            ) + fadeIn(tween(180)),
            exit = slideOutVertically(
                animationSpec = tween(200, easing = LinearEasing),
                targetOffsetY = { -it }
            ) + fadeOut(tween(180))
        ) {
            WarningBanner(warning = latestWarning, resetKey = warningResetKey, onDismiss = onDismissWarning)
        }
    }
}

@Composable
private fun InfoPanel(
    clientState: ClientState,
    latestLocation: MSightLocationEvent?
) {
    Card(
        modifier = Modifier.width(260.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF2FFFFFF)),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "STATUS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF546E7A)
            )
            StatusBadge(clientState)

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = "LOCATION",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF546E7A)
            )
            if (latestLocation == null) {
                Text(
                    text = "Awaiting first fix...",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF78909C)
                )
            } else {
                val accuracyText = latestLocation.accuracyMeters
                    ?.let { String.format(Locale.US, "%.1f m", it) } ?: "N/A"
                InfoRow("Lat", formatCoordinate(latestLocation.latitude))
                InfoRow("Lon", formatCoordinate(latestLocation.longitude))
                InfoRow("Provider", latestLocation.provider)
                InfoRow("Accuracy", accuracyText)
                InfoRow("Updated", formatTimestamp(latestLocation.timestampMillis))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF546E7A),
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF212121)
        )
    }
}

@Composable
private fun StatusBadge(clientState: ClientState) {
    val (label, color) = when (clientState) {
        is ClientState.Idle -> "Idle" to Color(0xFF757575)
        is ClientState.Starting -> "Starting..." to Color(0xFFF57C00)
        is ClientState.Running -> "Running" to Color(0xFF2E7D32)
        is ClientState.Error -> "Error: ${clientState.message}" to Color(0xFFD32F2F)
    }

    val transition = rememberInfiniteTransition(label = "status")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .alpha(if (clientState is ClientState.Starting) pulseAlpha else 1f)
                .clip(CircleShape)
                .background(color)
        )
        Text(text = label, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun WarningBanner(
    warning: MSightSimpleWarning?,
    resetKey: Int,
    onDismiss: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "warning")

    val glowAlpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(380, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    val iconPulse by transition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon"
    )

    val progressAnim = remember(resetKey) { Animatable(1f) }
    LaunchedEffect(resetKey) {
        progressAnim.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = WARNING_AUTO_DISMISS_MILLIS.toInt(),
                easing = LinearEasing
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1A0006))
            .border(2.dp, Color(0xFFFF1744).copy(alpha = glowAlpha), RoundedCornerShape(20.dp))
    ) {
        // Pulsing red tint overlay
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color(0xFFFF1744).copy(alpha = glowAlpha * 0.055f))
        )
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Warning icon with pulsing halo
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF1744).copy(alpha = glowAlpha * 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Warning",
                        tint = Color(0xFFFFD600).copy(alpha = iconPulse),
                        modifier = Modifier.size(34.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "⚠  DRIVER ALERT",
                        color = Color(0xFFFF5252),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 3.sp
                    )
                    Text(
                        text = warning?.message ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 22.sp
                    )
                    if (warning != null) {
                        Text(
                            text = formatTimestamp(warning.timestampMillis),
                            color = Color.White.copy(alpha = 0.38f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                TextButton(onClick = onDismiss) {
                    Text(
                        text = "✕",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Countdown drain bar: full → empty over WARNING_AUTO_DISMISS_MILLIS
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.07f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressAnim.value)
                        .fillMaxSize()
                        .background(Color(0xFFFF1744).copy(alpha = 0.88f))
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean,
    displayName: (T) -> String
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = displayName(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            enabled = enabled
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(displayName(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun formatTimestamp(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))

private fun formatCoordinate(value: Double): String =
    String.format(Locale.US, "%.6f", value)

/**
 * Mirrors the JS geolib logic:
 *  Step 1 – move offsetX metres northward  (bearing = 0°)
 *  Step 2 – move offsetY metres eastward   (bearing = 90°)
 */
private fun computeObjectLatLon(
    refLat: Double, refLon: Double,
    offsetX: Double, offsetY: Double
): Pair<Double, Double> {
    val R = 6_371_000.0

    // Step 1: offsetX northward (bearing = 0°  →  only latitude changes)
    val d1 = offsetX / R
    val φ1 = Math.toRadians(refLat)
    val λ1 = Math.toRadians(refLon)
    val φ2 = asin(sin(φ1) * cos(d1) + cos(φ1) * sin(d1))
    // λ unchanged for due-north movement

    // Step 2: offsetY eastward (bearing = 90°  →  cos(90°)=0, sin(90°)=1)
    val d2 = offsetY / R
    val sinφ3 = sin(φ2) * cos(d2)   // cos(90°) term vanishes
    val φ3 = asin(sinφ3)
    val λ3 = λ1 + atan2(sin(d2) * cos(φ2), cos(d2) - sin(φ2) * sinφ3)

    return Pair(Math.toDegrees(φ3), Math.toDegrees(λ3))
}

private fun SignalColor.toComposeColor(): Color = when (this) {
    SignalColor.GREEN   -> Color(0xFF4CAF50)
    SignalColor.YELLOW  -> Color(0xFFFFC107)
    SignalColor.RED     -> Color(0xFFF44336)
    SignalColor.UNKNOWN -> Color(0xFF607D8B)
}

private enum class SignalDirection { STRAIGHT, LEFT }

@Composable
private fun SignalOverlay(
    intersectionName: String,
    straightColor: SignalColor,
    leftColor: SignalColor,
    showSingleLight: Boolean,
    straightGroupIds: List<Int>,
    leftGroupIds: List<Int>
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xE6000000)),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = intersectionName,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            if (showSingleLight) {
                val singleColor = if (straightColor != SignalColor.UNKNOWN) straightColor else leftColor
                val singleIds = if (straightGroupIds.isNotEmpty()) straightGroupIds else leftGroupIds
                SignalArrow(
                    color = singleColor,
                    direction = SignalDirection.STRAIGHT,
                    groupIds = singleIds
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SignalArrow(
                        color = leftColor,
                        direction = SignalDirection.LEFT,
                        groupIds = leftGroupIds
                    )
                    SignalArrow(
                        color = straightColor,
                        direction = SignalDirection.STRAIGHT,
                        groupIds = straightGroupIds
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalArrow(
    color: SignalColor,
    direction: SignalDirection,
    groupIds: List<Int> = emptyList()
) {
    val signalColor = color.toComposeColor()
    val isOn = color != SignalColor.UNKNOWN
    Box(
        modifier = Modifier.size(72.dp),
        contentAlignment = Alignment.Center
    ) {
        // Dark housing — modern rounded-square traffic indicator
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF101012))
                .border(1.5.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
        )
        // Soft colored glow behind the arrow when the signal is on
        if (isOn) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(signalColor.copy(alpha = 0.22f))
            )
        }
        // Arrow glyph
        Canvas(modifier = Modifier.size(42.dp)) {
            val arrowColor = if (isOn) signalColor else Color(0xFF4A4F55)
            when (direction) {
                SignalDirection.STRAIGHT -> drawStraightArrow(arrowColor)
                SignalDirection.LEFT -> drawLeftArrow(arrowColor)
            }
        }
        // Show signal group IDs in the corner only when state is unknown
        if (!isOn && groupIds.isNotEmpty()) {
            Text(
                text = groupIds.joinToString(","),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
            )
        }
    }
}

private fun DrawScope.drawStraightArrow(color: Color) {
    val w = size.width
    val h = size.height
    val strokeW = w * 0.18f
    val cx = w / 2f
    // Shaft from bottom up to the arrowhead base
    drawLine(
        color = color,
        start = Offset(cx, h * 0.92f),
        end = Offset(cx, h * 0.40f),
        strokeWidth = strokeW,
        cap = StrokeCap.Round
    )
    // Filled triangular arrowhead pointing up
    val headHalf = w * 0.26f
    val head = ComposePath().apply {
        moveTo(cx, h * 0.08f)
        lineTo(cx - headHalf, h * 0.44f)
        lineTo(cx + headHalf, h * 0.44f)
        close()
    }
    drawPath(head, color = color)
}

private fun DrawScope.drawLeftArrow(color: Color) {
    val w = size.width
    val h = size.height
    val strokeW = w * 0.18f
    val cy = h * 0.55f
    // L-shaped shaft: up from bottom-right then bend left
    val shaft = ComposePath().apply {
        moveTo(w * 0.78f, h * 0.92f)
        lineTo(w * 0.78f, cy)
        lineTo(w * 0.36f, cy)
    }
    drawPath(
        path = shaft,
        color = color,
        style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    // Filled triangular arrowhead pointing left
    val headHalf = h * 0.20f
    val tipX = w * 0.08f
    val baseX = w * 0.40f
    val head = ComposePath().apply {
        moveTo(tipX, cy)
        lineTo(baseX, cy - headHalf)
        lineTo(baseX, cy + headHalf)
        close()
    }
    drawPath(head, color = color)
}

private fun createObjectMarkerBitmap(objectType: String, heading: Double): Bitmap {
    val size = 64
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = size / 2f
    val cy = size / 2f
    val radius = size * 0.36f

    // Filled circle — colour by type
    paint.color = when (objectType.lowercase()) {
        "vehicle" -> 0xFF1565C0.toInt()
        "vru"     -> 0xFF2E7D32.toInt()
        "animal"  -> 0xFFF9A825.toInt()
        else      -> 0xFFC62828.toInt()
    }
    paint.style = Paint.Style.FILL
    canvas.drawCircle(cx, cy, radius, paint)

    // White border
    paint.color = 0xFFFFFFFF.toInt()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3f
    canvas.drawCircle(cx, cy, radius, paint)

    // White direction arrow pointing in heading direction (0° = north)
    paint.style = Paint.Style.FILL
    val headingRad = Math.toRadians(heading)
    val tipDist = radius * 0.72f
    val tipX = cx + (tipDist * sin(headingRad)).toFloat()
    val tipY = cy - (tipDist * cos(headingRad)).toFloat()
    val baseDist = radius * 0.25f
    val baseX = cx - (baseDist * sin(headingRad)).toFloat()
    val baseY = cy + (baseDist * cos(headingRad)).toFloat()
    val halfBase = radius * 0.28f
    val perpRad = headingRad + Math.PI / 2
    val path = Path()
    path.moveTo(tipX, tipY)
    path.lineTo(
        baseX + (halfBase * sin(perpRad)).toFloat(),
        baseY - (halfBase * cos(perpRad)).toFloat()
    )
    path.lineTo(
        baseX - (halfBase * sin(perpRad)).toFloat(),
        baseY + (halfBase * cos(perpRad)).toFloat()
    )
    path.close()
    canvas.drawPath(path, paint)

    return bitmap
}

private fun offsetToLatLng(ref: MapRefPoint, offsetX: Double, offsetY: Double): LatLng {
    val lat = ref.lat + offsetY / 111320.0
    val lon = ref.lon + offsetX / (111320.0 * cos(Math.toRadians(ref.lat)))
    return LatLng(lat, lon)
}

private fun mergedLaneRectangleCorners(
    ref: MapRefPoint,
    lanes: List<MapLane>,
    heightM: Double = 1.5
): List<LatLng>? {
    // Use the first lane with 2+ nodes to define the approach direction
    val repLane = lanes.firstOrNull { it.nodes.size >= 2 } ?: return null
    val p0 = repLane.nodes[0]
    val p1 = repLane.nodes[1]
    val dx = p1.offsetX - p0.offsetX
    val dy = p1.offsetY - p0.offsetY
    val len = sqrt(dx * dx + dy * dy)
    if (len < 0.01) return null
    val dirX = dx / len
    val dirY = dy / len
    val perpX = -dirY
    val perpY = dirX

    // Project each lane's stop-bar node onto the perp axis (relative to p0)
    // and expand by that lane's half-width to find the total lateral extent
    var minPerp = Double.MAX_VALUE
    var maxPerp = -Double.MAX_VALUE
    for (lane in lanes) {
        val n0 = lane.nodes.firstOrNull() ?: continue
        val halfW = n0.widthM / 2.0
        val perpProj = (n0.offsetX - p0.offsetX) * perpX + (n0.offsetY - p0.offsetY) * perpY
        if (perpProj - halfW < minPerp) minPerp = perpProj - halfW
        if (perpProj + halfW > maxPerp) maxPerp = perpProj + halfW
    }
    if (minPerp == Double.MAX_VALUE) return null

    // Stop-bar edge at p0; near edge extends toward the intersection centre (-dir)
    return listOf(
        offsetToLatLng(ref, p0.offsetX + maxPerp * perpX,                   p0.offsetY + maxPerp * perpY),
        offsetToLatLng(ref, p0.offsetX - dirX * heightM + maxPerp * perpX,  p0.offsetY - dirY * heightM + maxPerp * perpY),
        offsetToLatLng(ref, p0.offsetX - dirX * heightM + minPerp * perpX,  p0.offsetY - dirY * heightM + minPerp * perpY),
        offsetToLatLng(ref, p0.offsetX + minPerp * perpX,                   p0.offsetY + minPerp * perpY)
    )
}

private fun SignalColor.toMapOverlayFill(): Color = when (this) {
    SignalColor.GREEN   -> Color(0f, 0.85f, 0.38f, 0.50f)
    SignalColor.YELLOW  -> Color(1f, 0.88f, 0f, 0.50f)
    SignalColor.RED     -> Color(0.92f, 0.10f, 0.10f, 0.50f)
    SignalColor.UNKNOWN -> Color(0.45f, 0.55f, 0.60f, 0.25f)
}

