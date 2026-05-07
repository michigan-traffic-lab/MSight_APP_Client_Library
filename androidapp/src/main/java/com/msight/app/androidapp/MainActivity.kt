package com.msight.app.androidapp

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val WARNING_AUTO_DISMISS_MILLIS = 3_000L
private const val WARNING_RESHOW_DELAY_MILLIS = 180L

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
    private var latestSdsmEvent by mutableStateOf<MSightSdsmEvent?>(null)

    private var pendingConfig: MSightClientConfig? = null
    private var activeClient: MSightClient? = null
    private var warningDisplayJob: Job? = null

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
                        latestSdsmEvent = latestSdsmEvent,
                        onStart = { config -> requestPermissionsAndStart(config) },
                        onStop = { stopClient() },
                        onDismissWarning = { warningVisible = false }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        lifecycleScope.launch(Dispatchers.IO) { activeClient?.close() }
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
                                Log.d(
                                    "MSight-SDSM",
                                    "SDSM sensor=${event.sensorName} frameId=${event.frameId} " +
                                    "msgCnt=${event.msgCnt} objects=${event.objects.size} " +
                                    "refPos=(${event.refPos.lat},${event.refPos.long})"
                                )
                            }

                            else -> Unit
                        }
                    }
                }

                client.start()
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
                activeClient = null
                clientState = ClientState.Idle
                latestLocation = null
                warningVisible = false
            }
        }
    }

    private fun showWarning(warning: MSightSimpleWarning) {
        warningDisplayJob?.cancel()
        warningDisplayJob = lifecycleScope.launch {
            if (warningVisible) {
                warningVisible = false
                delay(WARNING_RESHOW_DELAY_MILLIS)
            }

            latestWarning = warning
            warningVisible = true
            Log.d("MSight", "Received warning: $warning")

            delay(WARNING_AUTO_DISMISS_MILLIS)
            warningVisible = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MSightScreen(
    clientState: ClientState,
    latestLocation: MSightLocationEvent?,
    latestWarning: MSightSimpleWarning?,
    warningVisible: Boolean,
    latestSdsmEvent: MSightSdsmEvent?,
    onStart: (MSightClientConfig) -> Unit,
    onStop: () -> Unit,
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
            latestSdsmEvent = latestSdsmEvent,
            onStop = onStop,
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
    latestSdsmEvent: MSightSdsmEvent?,
    onStop: () -> Unit,
    onDismissWarning: () -> Unit
) {
    var infoPanelVisible by remember { mutableStateOf(false) }
    val markerCache = remember { HashMap<String, BitmapDescriptor>() }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 5f)
    }

    var hasInitialLocation by remember { mutableStateOf(false) }
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
            latestSdsmEvent?.let { event ->
                event.objects.forEach { obj ->
                    val (lat, lon) = computeObjectLatLon(
                        event.refPos.lat, event.refPos.long,
                        obj.pos.offsetX, obj.pos.offsetY
                    )
                    val headingBucket = (obj.heading / 10.0).toInt() * 10.0
                    val cacheKey = "${obj.objectType}_$headingBucket"
                    val markerIcon = markerCache.getOrPut(cacheKey) {
                        BitmapDescriptorFactory.fromBitmap(
                            createObjectMarkerBitmap(obj.objectType, headingBucket)
                        )
                    }
                    Marker(
                        state = MarkerState(position = LatLng(lat, lon)),
                        icon = markerIcon,
                        anchor = Offset(0.5f, 0.5f),
                        title = "${obj.objectType} #${obj.objectID}",
                        snippet = "spd=${obj.speed} hdg=${String.format(Locale.US, "%.1f", obj.heading)}°"
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
            WarningBanner(warning = latestWarning, onDismiss = onDismissWarning)
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

    var progress by remember(warning) { mutableStateOf(1f) }
    LaunchedEffect(warning) {
        val steps = 60
        val stepDelay = WARNING_AUTO_DISMISS_MILLIS / steps
        for (i in 1..steps) {
            delay(stepDelay)
            progress = 1f - i / steps.toFloat()
        }
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
                        .fillMaxWidth(progress)
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
