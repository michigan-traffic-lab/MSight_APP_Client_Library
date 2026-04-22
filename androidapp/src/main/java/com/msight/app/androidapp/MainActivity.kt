package com.msight.app.androidapp

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.msight.app.client.LocationEmitter
import com.msight.app.client.LocationEvent
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val trials = mutableStateListOf<TrialRecord>()
    private var currentTrialPoints = mutableListOf<RecordedPoint>()
    private var currentTrialStartMillis: Long? = null
    private var isRecording by mutableStateOf(false)

    private var latestLatitude by mutableStateOf<Double?>(null)
    private var latestLongitude by mutableStateOf<Double?>(null)
    private var latestTimestampMillis by mutableStateOf<Long?>(null)

    private lateinit var emitter: LocationEmitter

    private var pendingExportTrial by mutableStateOf<TrialRecord?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineGranted || coarseGranted) {
            emitter.start()
        } else {
            Log.e("MSight", "Location permission denied")
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        val trial = pendingExportTrial ?: return@registerForActivityResult
        if (uri != null) {
            exportTrialToCsv(uri, trial)
        }
        pendingExportTrial = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        emitter = LocationEmitter(applicationContext)

        lifecycleScope.launch {
            emitter.events.collect { event: com.msight.app.client.MSightLocationEvent ->
                latestLatitude = event.latitude
                latestLongitude = event.longitude
                latestTimestampMillis = event.timestampMillis

                if (isRecording) {
                    currentTrialPoints.add(
                        RecordedPoint(
                            timestampMillis = event.timestampMillis,
                            latitude = event.latitude,
                            longitude = event.longitude
                        )
                    )
                }

                Log.d("MSight", "Location event: $event")
            }
        }

        ensureLocationPermissionAndStartEmitter()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "MSight Trial Recorder",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        if (isRecording) {
                            RecordingBanner(
                                pointCount = currentTrialPoints.size
                            )
                        } else {
                            AssistChip(
                                onClick = {},
                                label = { Text("Idle") }
                            )
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Latest Location",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text("Lat: ${latestLatitude?.let { formatCoord(it) } ?: "N/A"}")
                                Text("Lon: ${latestLongitude?.let { formatCoord(it) } ?: "N/A"}")
                                Text("Timestamp: ${latestTimestampMillis?.let { formatTime(it) } ?: "N/A"}")
                            }
                        }

                        Button(
                            onClick = { toggleRecording() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) Color(0xFFD32F2F) else Color(0xFF2E7D32)
                            )
                        ) {
                            Text(
                                text = if (isRecording) "Stop Trial" else "Start Trial",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        HorizontalDivider()

                        Text(
                            text = "Trials",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(trials.reversed(), key = { it.id }) { trial ->
                                TrialCard(
                                    trial = trial,
                                    onExport = { exportTrial(trial) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        emitter.stop()
        super.onDestroy()
    }

    private fun ensureLocationPermissionAndStartEmitter() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            emitter.start()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun toggleRecording() {
        if (!isRecording) {
            currentTrialPoints = mutableListOf()
            currentTrialStartMillis = System.currentTimeMillis()
            isRecording = true
            return
        }

        val startedAt = currentTrialStartMillis ?: System.currentTimeMillis()
        val endedAt = System.currentTimeMillis()

        trials.add(
            TrialRecord(
                id = "trial_${trials.size + 1}",
                startedAtMillis = startedAt,
                endedAtMillis = endedAt,
                points = currentTrialPoints.toList()
            )
        )

        currentTrialPoints = mutableListOf()
        currentTrialStartMillis = null
        isRecording = false
    }

    private fun exportTrial(trial: TrialRecord) {
        pendingExportTrial = trial
        exportLauncher.launch("${trial.id}.csv")
    }

    private fun exportTrialToCsv(uri: Uri, trial: TrialRecord) {
        val csv = buildString {
            appendLine("timestampMillis,latitude,longitude")
            trial.points.forEach { point ->
                appendLine("${point.timestampMillis},${point.latitude},${point.longitude}")
            }
        }

        contentResolver.openOutputStream(uri)?.use { output ->
            output.write(csv.toByteArray())
            output.flush()
        }

        Log.d("MSight", "Exported CSV for ${trial.id}")
    }

    private fun formatCoord(value: Double): String {
        return String.format(Locale.US, "%.7f", value)
    }

    private fun formatTime(timestampMillis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return formatter.format(Date(timestampMillis))
    }
}

@androidx.compose.runtime.Composable
private fun RecordingBanner(pointCount: Int) {
    val transition = rememberInfiniteTransition(label = "recording")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .alpha(pulseAlpha)
                    .background(Color.Red, CircleShape)
            )

            Column {
                Text(
                    text = "Recording in progress",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFB71C1C)
                )
                Text(
                    text = "Captured points: $pointCount",
                    color = Color(0xFFB71C1C)
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TrialCard(
    trial: TrialRecord,
    onExport: () -> Unit
) {
    val durationSeconds = remember(trial) {
        val end = trial.endedAtMillis ?: trial.startedAtMillis
        ((end - trial.startedAtMillis) / 1000.0)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = trial.id,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text("Started: ${formatTimeStatic(trial.startedAtMillis)}")
            Text("Ended: ${trial.endedAtMillis?.let { formatTimeStatic(it) } ?: "N/A"}")
            Text("Points: ${trial.points.size}")
            Text("Duration: ${String.format(Locale.US, "%.1f s", durationSeconds)}")

            Spacer(modifier = Modifier.height(4.dp))

            Button(onClick = onExport) {
                Text("Export CSV")
            }
        }
    }
}

private fun formatTimeStatic(timestampMillis: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    return formatter.format(Date(timestampMillis))
}