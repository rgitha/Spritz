package io.github.rgitha.spritz.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.rgitha.spritz.ble.AerScanner
import io.github.rgitha.spritz.ble.BlePermissions
import io.github.rgitha.spritz.ble.FoundDevice
import io.github.rgitha.spritz.data.DeviceStore
import io.github.rgitha.spritz.data.SpritzRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(onBack: () -> Unit, onAdded: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DeviceStore.get(context) }
    val repo = remember { SpritzRepository.get(context) }
    val saved by store.devices.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // Re-checked on resume, so returning from system settings updates the screen.
    var recheck by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) recheck++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val hasPermission = remember(recheck) { BlePermissions.granted(context) }
    val btOn = remember(recheck) { BlePermissions.adapter(context)?.isEnabled == true }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recheck++
    }
    val askEnable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        recheck++
    }

    var found by remember { mutableStateOf<List<FoundDevice>>(emptyList()) }
    /** The found device being named, before it's saved. */
    var settingUp by remember { mutableStateOf<FoundDevice?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    // The scan pauses while a device is being set up: scanning slows a connection.
    /** Bumped by "Scan again" to start a new scan. */
    var scanRound by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    LaunchedEffect(hasPermission, btOn, settingUp == null, scanRound) {
        if (!hasPermission || !btOn || settingUp != null) return@LaunchedEffect
        scanError = null
        scanning = true
        try {
            withTimeoutOrNull(SCAN_MS) {
                AerScanner.scan(context)
                    .catch { scanError = it.message ?: "Scan failed" }
                    .collect { found = it }
            }
        } finally {
            scanning = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Add device") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = gap) {
            when {
                !hasPermission -> Prompt(
                    "Spritz needs Bluetooth permission to find your aer device." +
                        if (android.os.Build.VERSION.SDK_INT < 31) " Android asks for location for this; Spritz doesn't use it." else "",
                    "Allow",
                ) { askPermission.launch(BlePermissions.required) }

                !btOn -> Prompt("Bluetooth is off.", "Turn on") {
                    askEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }

                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
                        if (scanning) {
                            ScanPulse()
                        } else if (settingUp == null) {
                            OutlinedButton(onClick = { scanRound++ }) {
                                Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Scan again")
                            }
                        }
                    }
                    scanError?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    // Devices already added are left out.
                    val savedAddresses = saved.map { it.address }.toSet()
                    val fresh = found.filter { it.address !in savedAddresses }
                    if (found.isNotEmpty() && fresh.isEmpty()) {
                        Text(
                            "No new devices found. The devices nearby are already added.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(fresh, key = { it.address }) { d ->
                            Card(
                                onClick = { settingUp = d },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                ListItem(
                                    headlineContent = { Text(d.name) },
                                    supportingContent = { Text("${d.address} · signal ${signal(d.rssi)}") },
                                    trailingContent = { Text("Add", color = MaterialTheme.colorScheme.primary) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Read the chosen device's status while it's being named, once the scan has stopped.
    LaunchedEffect(settingUp?.address) {
        val address = settingUp?.address ?: return@LaunchedEffect
        delay(300)
        repo.prefetch(address)
    }
    settingUp?.let { d ->
        SetupDialog(
            address = d.address,
            initialName = d.name,
            others = saved,
            onDismiss = { settingUp = null },
            onConfirm = { name, room ->
                settingUp = null
                scope.launch {
                    store.add(d.address, name, room)
                    repo.adopt(d.address)
                    store.updateSettings { it.copy(lastDevice = d.address) }
                    onAdded()
                }
            },
        )
    }
}

/** A scan lasts this long; then "Scan again" appears. Android also limits how often scans start. */
private const val SCAN_MS = 30_000L

/** A slowly pulsing Bluetooth icon: listening, not something to wait for. */
@Composable
private fun ScanPulse() {
    val pulse = rememberInfiniteTransition(label = "scan")
    val alpha by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "alpha",
    )
    Icon(
        Icons.Default.Bluetooth,
        contentDescription = "Scanning",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp).alpha(alpha),
    )
}

private fun signal(rssi: Int) = when {
    rssi >= -60 -> "strong"
    rssi >= -80 -> "good"
    else -> "weak"
}

@Composable
private fun Prompt(text: String, action: String, onClick: () -> Unit) {
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text)
        Button(onClick = onClick) { Text(action) }
        Spacer(Modifier.height(8.dp))
    }
}
