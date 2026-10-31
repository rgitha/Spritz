package io.github.rgitha.spritz.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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

    // Kept across searches, so a device doesn't vanish when a new search starts.
    var found by remember { mutableStateOf<List<FoundDevice>>(emptyList()) }
    /** Added on this visit, shown highlighted. */
    var justAdded by remember { mutableStateOf<Set<String>>(emptySet()) }
    val snackbar = remember { SnackbarHostState() }
    /** The found device being named, before it's saved. */
    var settingUp by remember { mutableStateOf<FoundDevice?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    // The search pauses while a device is being set up: searching slows a connection.
    /** Bumped by "Search again" to start a new search. */
    var scanRound by remember { mutableIntStateOf(0) }
    /** The device being identified; the search pauses meanwhile, and other rows wait. */
    var identifying by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    LaunchedEffect(hasPermission, btOn, settingUp == null && identifying == null, scanRound) {
        if (!hasPermission || !btOn || settingUp != null || identifying != null) return@LaunchedEffect
        scanError = null
        scanning = true
        try {
            withTimeoutOrNull(SCAN_MS) {
                AerScanner.scan(context)
                    .catch { scanError = it.message ?: "Search failed" }
                    .collect { seen ->
                        val fresh = seen.associateBy { it.address }
                        found = found.map { fresh[it.address] ?: it } + seen.filter { s -> found.none { it.address == s.address } }
                    }
            }
        } finally {
            scanning = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Add device") }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = gap,
        ) {
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
                    SearchCard(searching = scanning || settingUp != null || identifying != null, onSearch = { scanRound++ })
                    scanError?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (found.isNotEmpty()) {
                        Text(
                            "Found",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        )
                        // Devices already added stay listed, so each unit can be told apart.
                        Card(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                            found.forEachIndexed { i, d ->
                                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                val added = saved.find { it.address == d.address }
                                val identify: @Composable () -> Unit = {
                                    IdentifyButton(
                                        d.address,
                                        enabled = identifying == null,
                                        onActive = { on -> identifying = if (on) d.address else null },
                                    )
                                }
                                if (added == null) {
                                    FoundRow(d, identify, onAdd = { settingUp = d })
                                } else {
                                    AddedRow(d, added.label, identify, highlight = d.address in justAdded)
                                }
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
                    repo.addDevice(d.address, name, room)
                    repo.adopt(d.address)
                    // Stay while there are more to add; the search starts again by itself.
                    val savedNow = store.devicesNow().map { it.address }.toSet()
                    if (found.all { it.address in savedNow }) {
                        onAdded()
                        return@launch
                    }
                    justAdded = justAdded + d.address
                    val label = store.devicesNow().find { it.address == d.address }?.label ?: name
                    val result = snackbar.showSnackbar("Added $label", actionLabel = "Open", duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) {
                        store.updateSettings { it.copy(lastDevice = d.address) }
                        onAdded()
                    }
                }
            },
        )
    }
}

/** A search lasts this long; then "Search again" appears. Android also limits how often searches start. */
private const val SCAN_MS = 30_000L

/** What the search is doing, with a tip; after it stops, a way to start it again. */
@Composable
private fun SearchCard(searching: Boolean, onSearch: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                ) {
                    if (searching) {
                        SearchPulse()
                    } else {
                        Icon(
                            Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (searching) "Searching nearby…" else "Search stopped", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (searching) {
                            "Keep the device within a few metres. Press its button to wake it."
                        } else {
                            "Not seeing it? Make sure it's nearby, then search again."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!searching) {
                Button(onClick = onSearch, modifier = Modifier.align(Alignment.End)) { Text("Search again") }
            }
        }
    }
}

/** A slowly pulsing Bluetooth icon: listening, not something to wait for. */
@Composable
private fun SearchPulse() {
    val pulse = rememberInfiniteTransition(label = "search")
    val alpha by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "alpha",
    )
    Icon(
        Icons.Default.Bluetooth,
        contentDescription = "Searching",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp).alpha(alpha),
    )
}

@Composable
private fun FoundRow(d: FoundDevice, identify: @Composable () -> Unit, onAdd: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge)
            DeviceLine(d.rssi, d.address)
        }
        identify()
        OutlinedButton(onClick = onAdd) { Text("Add") }
    }
}

/** A device already added: its room and name after the ID. [highlight] marks one added just now. */
@Composable
private fun AddedRow(d: FoundDevice, label: String, identify: @Composable () -> Unit, highlight: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (highlight) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge, color = muted)
            DeviceLine(d.rssi, "${d.address} · $label")
        }
        identify()
        Text(
            "Added",
            style = MaterialTheme.typography.labelLarge,
            color = if (highlight) MaterialTheme.colorScheme.primary else muted,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** Signal bars, then [text]. */
@Composable
private fun DeviceLine(rssi: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SignalBars(rssi)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Three bars: strong (-60 dBm or better), good (to -80 dBm) or weak. */
@Composable
private fun SignalBars(rssi: Int) {
    val lit = when {
        rssi >= -60 -> 3
        rssi >= -80 -> 2
        else -> 1
    }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    val label = listOf("Weak", "Good", "Strong")[lit - 1] + " signal"
    Canvas(Modifier.size(16.dp, 14.dp).semantics { contentDescription = label }) {
        val bar = size.width * 3 / 16
        val step = size.width * 5 / 16
        listOf(5f, 9f, 13f).forEachIndexed { i, h ->
            val height = size.height * h / 14
            drawRoundRect(
                if (i < lit) on else off,
                topLeft = Offset(size.width / 16 + i * step, size.height - height),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 3),
            )
        }
    }
}

@Composable
private fun Prompt(text: String, action: String, onClick: () -> Unit) {
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text)
        Button(onClick = onClick) { Text(action) }
        Spacer(Modifier.height(8.dp))
    }
}
