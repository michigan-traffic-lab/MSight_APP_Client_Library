package com.msight.app.androidapp

import android.Manifest
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.msight.app.androidapp.ui.theme.MsightappclientlibraryTheme
import com.msight.app.client.MSightClient
import com.msight.app.client.MSightClientConfig
import com.msight.app.client.MSightDeviceType
import com.msight.app.client.MSightLocationEvent
import com.msight.app.client.MSightRoadUserType
import com.msight.app.client.MSightSimpleWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        setContent {
            MsightappclientlibraryTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MSightScreen(
                        clientState = clientState,
                        latestLocation = latestLocation,
                        latestWarning = latestWarning,
                        warningVisible = warningVisible,
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
                                Log.d(
                                    "MSight",
                                    "Location update: lat=${event.latitude}, lon=${event.longitude}, accuracy=${event.accuracyMeters}, provider=${event.provider}"
                                )
                            }

                            is MSightSimpleWarning -> {
                                showWarning(event)
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
    onStart: (MSightClientConfig) -> Unit,
    onStop: () -> Unit,
    onDismissWarning: () -> Unit
) {
    val isActive = clientState is ClientState.Running || clientState is ClientState.Starting

    var cloudUrl by remember { mutableStateOf("https://7hmptbe8s3.execute-api.us-east-2.amazonaws.com") }
    var appId by remember { mutableStateOf("msight-demo") }
    var clientId by remember { mutableStateOf("client-001") }
    var roadUserType by remember { mutableStateOf(MSightRoadUserType.VEHICLE) }
    var roadUserSubType by remember { mutableStateOf("passenger_car") }
    var deviceType by remember { mutableStateOf(MSightDeviceType.CELLPHONE) }
    var locationFreqHz by remember { mutableStateOf("1.0") }

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

            LatestLocationCard(location = latestLocation)

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = cloudUrl,
                onValueChange = { cloudUrl = it },
                label = { Text("Cloud URL") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isActive,
                singleLine = true
            )

            OutlinedTextField(
                value = appId,
                onValueChange = { appId = it },
                label = { Text("App ID") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isActive,
                singleLine = true
            )

            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Client ID") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isActive,
                singleLine = true
            )

            EnumDropdown(
                label = "Road User Type",
                options = MSightRoadUserType.entries,
                selected = roadUserType,
                onSelect = { roadUserType = it },
                enabled = !isActive,
                displayName = { it.name }
            )

            OutlinedTextField(
                value = roadUserSubType,
                onValueChange = { roadUserSubType = it },
                label = { Text("Road User Sub-Type") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isActive,
                singleLine = true
            )

            EnumDropdown(
                label = "Device Type",
                options = MSightDeviceType.entries,
                selected = deviceType,
                onSelect = { deviceType = it },
                enabled = !isActive,
                displayName = { it.name }
            )

            OutlinedTextField(
                value = locationFreqHz,
                onValueChange = { locationFreqHz = it },
                label = { Text("Location Frequency (Hz)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isActive,
                singleLine = true
            )

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = {
                    if (isActive) {
                        onStop()
                    } else {
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
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (clientState) {
                        is ClientState.Running -> Color(0xFFD32F2F)
                        is ClientState.Starting -> Color(0xFF616161)
                        else -> Color(0xFF1976D2)
                    }
                ),
                enabled = clientState !is ClientState.Starting
            ) {
                Text(
                    text = when (clientState) {
                        is ClientState.Running -> "Stop Client"
                        is ClientState.Starting -> "Starting..."
                        else -> "Start Client"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // Extra bottom padding so warning banner doesn't permanently obscure content
            Spacer(modifier = Modifier.height(64.dp))
        }

        // Warning banner slides in from top over the scrollable content
        AnimatedVisibility(
            visible = warningVisible,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp, start = 12.dp, end = 12.dp),
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(tween(300)),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(tween(200))
        ) {
            WarningBanner(
                warning = latestWarning,
                onDismiss = onDismissWarning
            )
        }
    }
}

@Composable
private fun LatestLocationCard(location: MSightLocationEvent?) {
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F8FF)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Latest Location",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(
                    onClick = {
                        val currentLocation = location ?: return@TextButton
                        clipboardManager.setText(
                            AnnotatedString(
                                formatPythonLocationSnippet(currentLocation)
                            )
                        )
                    },
                    enabled = location != null
                ) {
                    Text("Copy")
                }
            }
            if (location == null) {
                Text(
                    text = "No location update received yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF546E7A)
                )
            } else {
                val accuracyText = location.accuracyMeters
                    ?.let { String.format(Locale.US, "%.1f m", it) }
                    ?: "N/A"

                Text("Latitude: ${formatCoordinate(location.latitude)}")
                Text("Longitude: ${formatCoordinate(location.longitude)}")
                Text("Provider: ${location.provider}")
                Text("Accuracy: $accuracyText")
                Text("Updated: ${formatWarningTimestamp(location.timestampMillis)}")
            }
        }
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
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .alpha(pulseAlpha)
                    .clip(CircleShape)
                    .background(Color(0xFFE65100))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WARNING RECEIVED",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFE65100)
                )
                if (warning != null) {
                    Text(
                        text = warning.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF4E2800)
                    )
                    Text(
                        text = formatWarningTimestamp(warning.timestampMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF795548)
                    )
                }
            }
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = Color(0xFFE65100), fontWeight = FontWeight.Bold)
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

private fun formatWarningTimestamp(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))

private fun formatCoordinate(value: Double): String =
    String.format(Locale.US, "%.6f", value)

private fun formatPythonLocationSnippet(location: MSightLocationEvent): String {
    return buildString {
        appendLine("ORIGIN_LAT = ${formatCoordinate(location.latitude)}")
        append("ORIGIN_LON = ${formatCoordinate(location.longitude)}")
    }
}
