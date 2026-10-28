package com.resqmesh.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var pendingSosText = ""
    private var bgInfo by mutableStateOf("")
    private var batteryExempt by mutableStateOf(false)

    private val gpsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) {
                MeshEngine.sendWithGps(pendingSosText)
            } else {
                MeshEngine.noLocationPermission()
                MeshEngine.sendSos(pendingSosText, null, "NONE")
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Notification permission is optional: the mesh still runs, but alerts are hidden.
            val denied = result.filter { !it.value && it.key != Manifest.permission.POST_NOTIFICATIONS }.keys
            if (denied.isNotEmpty()) {
                MeshEngine.addEvent("WARN", "Required permissions denied", denied.joinToString())
            } else {
                startMesh()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MeshEngine.init(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    ResqScreen(
                        bgInfo = bgInfo,
                        batteryExempt = batteryExempt,
                        onNameChange = { MeshEngine.setName(it) },
                        onStart = { requestPermissionsAndStart() },
                        onStop = { stopMesh() },
                        onSend = { requestGpsAndSend(it) },
                        onSendWithoutGps = { MeshEngine.sendSos(it, null, "NONE") },
                        onOpenMap = { lat, lon -> openMap(lat, lon) },
                        onBatteryExemption = { requestBatteryExemption() },
                        onClearData = { MeshEngine.clearData() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBackgroundInfo()
    }

    private fun refreshBackgroundInfo() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        val saver = pm.isPowerSaveMode
        batteryExempt = ignoring
        bgInfo = "Battery optimisation exemption: ${if (ignoring) "granted" else "NOT granted (the system may stop the mesh)"}. " +
            "Battery saver: ${if (saver) "ON (may restrict the mesh)" else "off"}."
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return permissions.distinct()
    }

    private fun optionalPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsAndStart() {
        val missing = (requiredPermissions() + optionalPermissions()).filter { !hasPermission(it) }
        if (missing.isEmpty()) startMesh() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startMesh() {
        runCatching { MeshService.start(this) }.onFailure {
            // Fall back to running inside the app process only (stops when the app is killed).
            MeshEngine.addEvent("WARN", "Background service unavailable", it.localizedMessage ?: "Running in foreground only.")
            MeshEngine.start()
        }
    }

    private fun stopMesh() {
        MeshService.stop(this)
        MeshEngine.stop()
    }

    private fun requestGpsAndSend(message: String) {
        val clean = message.trim()
        if (clean.isEmpty()) {
            MeshEngine.addEvent("SYSTEM", "SOS not sent", "Enter a short message first.")
            return
        }
        pendingSosText = clean
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) && !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            gpsPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        MeshEngine.sendWithGps(clean)
    }

    private fun openMap(latitude: Double, longitude: Double) {
        // Opens an installed maps app. Offline maps must be downloaded in that app beforehand.
        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(RESQMesh%20SOS)")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure {
                MeshEngine.addEvent(
                    "WARN", "Could not open a map app",
                    "Use COPY or DIRECTION instead. Coordinates: $latitude, $longitude", latitude, longitude
                )
            }
    }
}

private fun statusLabel(r: SosRecord): String = when (r.status) {
    "QUEUED" -> "QUEUED: saved, waiting for a peer"
    "SENT" -> "SENT: handed to transport, waiting for ACK"
    "ACKNOWLEDGED" -> "ACKNOWLEDGED by ${r.ackedBy.joinToString(", ")}"
    "EXPIRED" -> "EXPIRED: never acknowledged"
    else -> r.status
}

@Composable
private fun ResqScreen(
    bgInfo: String,
    batteryExempt: Boolean,
    onNameChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onSendWithoutGps: (String) -> Unit,
    onOpenMap: (Double, Double) -> Unit,
    onBatteryExemption: () -> Unit,
    onClearData: () -> Unit
) {
    var sosText by remember { mutableStateOf("Need assistance. Please check this location.") }
    val clipboard = LocalClipboardManager.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val events = MeshEngine.events
    val records = MeshEngine.sosRecords
    val status = MeshEngine.status
    val running = MeshEngine.running

    MeshEngine.pendingPeers.lastOrNull()?.let { peer ->
        AlertDialog(
            onDismissRequest = { MeshEngine.rejectPeer(peer) },
            title = { Text("Verify nearby device") },
            text = {
                Text("Device: ${peer.name}\nVerification code: ${peer.digits}\n\nOnly accept if the other phone shows the same code and you intend to connect.")
            },
            confirmButton = { TextButton(onClick = { MeshEngine.acceptPeer(peer) }) { Text("Code matches — connect") } },
            dismissButton = { TextButton(onClick = { MeshEngine.rejectPeer(peer) }) { Text("Reject") } }
        )
    }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text("RESQMesh", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "OFFLINE SOS  •  FIELD TEST",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Prototype only — not a certified emergency service. Keep normal emergency options available.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Device", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = MeshEngine.localName,
                            onValueChange = onNameChange,
                            label = { Text("Unique device name (restart mesh after changing)") },
                            singleLine = true,
                            enabled = !running,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (status.contains("failed", true) || status.contains("denied", true))
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        Text("Location: ${MeshEngine.gpsStatus}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onStart, enabled = !running, modifier = Modifier.weight(1f)) { Text("Start mesh") }
                            OutlinedButton(onClick = onStop, enabled = running, modifier = Modifier.weight(1f)) { Text("Stop") }
                        }
                        Text(
                            "Connected peers: ${MeshEngine.peers.size}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (MeshEngine.peers.isNotEmpty()) {
                            MeshEngine.peers.forEach { peer -> Text("• $peer", style = MaterialTheme.typography.bodySmall) }
                        } else {
                            Text("No peers connected yet.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Background operation", style = MaterialTheme.typography.titleMedium)
                        Text(bgInfo, style = MaterialTheme.typography.bodySmall)
                        if (!batteryExempt) {
                            OutlinedButton(onClick = onBatteryExemption, modifier = Modifier.fillMaxWidth()) {
                                Text("Allow background operation")
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Send an SOS", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = sosText,
                            onValueChange = { sosText = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            label = { Text("Emergency message") },
                            placeholder = { Text("Describe what help is needed") }
                        )
                        Button(onClick = { onSend(sosText) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Broadcast SOS with GPS")
                        }
                        OutlinedButton(onClick = { onSendWithoutGps(sosText) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Send without GPS")
                        }
                        Text(
                            "A peer receipt confirms app-to-app delivery only. It does not contact emergency services.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            item { Text("Delivery status", style = MaterialTheme.typography.titleLarge) }
            if (records.isEmpty()) {
                item { Text("No SOS sent from this phone yet.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(records, key = { it.id }) { r ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when (r.status) {
                                "ACKNOWLEDGED" -> MaterialTheme.colorScheme.tertiaryContainer
                                "EXPIRED" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(statusLabel(r), style = MaterialTheme.typography.titleSmall)
                            Text(r.text, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${timeFormat.format(Date(r.createdAt))} · attempts: ${r.attempts} · location: ${r.locationSource}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Event log", style = MaterialTheme.typography.titleLarge)
                    Text("${events.size} events", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (events.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("No events yet", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Connection changes, SOS sends, received messages, GPS status and receipt acknowledgements will appear here.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else {
                items(events, key = { it.id }) { event ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when (event.kind) {
                                "SOS" -> MaterialTheme.colorScheme.primaryContainer
                                "ACK" -> MaterialTheme.colorScheme.tertiaryContainer
                                "WARN" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            val stamp = if (event.time > 0L) timeFormat.format(Date(event.time)) else "--:--:--"
                            Text("$stamp  •  ${event.kind}  •  ${event.text}", style = MaterialTheme.typography.titleSmall)
                            Text(event.detail, style = MaterialTheme.typography.bodyMedium)
                            val lat = event.latitude
                            val lon = event.longitude
                            if (lat != null && lon != null) {
                                Text("Coordinates: $lat, $lon", style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = { onOpenMap(lat, lon) }) { Text("OPEN MAP") }
                                    TextButton(onClick = { clipboard.setText(AnnotatedString("$lat, $lon")) }) { Text("COPY") }
                                    TextButton(onClick = { MeshEngine.directionTo(lat, lon) }) { Text("DIRECTION") }
                                }
                            }
                        }
                    }
                }
            }

            item {
                OutlinedButton(onClick = onClearData, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear saved SOS, history and trusted peers")
                }
            }
        }
    }
}
