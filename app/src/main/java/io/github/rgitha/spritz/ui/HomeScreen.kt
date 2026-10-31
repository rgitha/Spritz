package io.github.rgitha.spritz.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rgitha.spritz.R
import io.github.rgitha.spritz.data.Naming
import io.github.rgitha.spritz.data.SavedDevice
import io.github.rgitha.spritz.data.SpritzRepository
import io.github.rgitha.spritz.protocol.StopTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class DeviceViewModel(app: Application, val address: String) : AndroidViewModel(app) {
    private val repo = SpritzRepository.get(app)
    val commands = Commands(viewModelScope, app)

    /** True while a status read runs; shown as a small spinner by the "Updated" line. */
    val refreshing = MutableStateFlow(false)

    /** Reads the status; a failure shows a toast and leaves the "Updated" line as it was. */
    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        commands.quiet {
            try {
                repo.refresh(address)
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Reads the status unless the last reading, taken at [statusAt], is recent. */
    fun refreshIfStale(statusAt: Long) {
        if (System.currentTimeMillis() - statusAt >= STALE_MS) refresh()
    }

    fun spray() = commands.run(KEY_SPRAY) { repo.spray(address) }

    fun setAuto(interval: Int?) = commands.run(autoKey(interval)) {
        if (interval == null) repo.stopAuto(address) else repo.spray(address, interval)
    }

    /** Skips the rest of today's scheduled run; the device ignores this outside a run. */
    fun skipScheduleRun() = commands.run(KEY_SKIP) { repo.stopRun(address, StopTarget.CURRENT_RUN) }

    fun refillReset() = commands.run(KEY_REFILL) { repo.refillReset(address) }

    companion object {
        const val STALE_MS = 60_000L
        const val KEY_SPRAY = "spray"
        const val KEY_SKIP = "skip"
        const val KEY_REFILL = "refill"
        fun autoKey(interval: Int?) = "auto:${interval ?: 0}"
    }
}

/** A device that was just removed, shown struck through until another one is picked. */
data class Removed(val name: String, val address: String)

/**
 * The app's only home: the current device, with a switcher on its name listing every
 * device (grouped by room, each with Spray) and Add device. With no devices, an empty
 * state. After a removal ([removed], from Device settings), a blank "Removed" screen
 * until another device is picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    removed: Removed?,
    onAdd: () -> Unit,
    onSchedules: (String) -> Unit,
    onDeviceSettings: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val repo = remember { SpritzRepository.get(app) }
    val devices by repo.store.devices.collectAsState(initial = null)
    val settings by repo.store.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val switcherCommands = remember(scope) { Commands(scope, app) }
    val switcherOutcomes by switcherCommands.outcomes.collectAsState()

    // Kept across recompositions until another device is picked.
    var removedName by rememberSaveable { mutableStateOf<String?>(null) }
    var removedAddress by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(removed) {
        if (removed != null) { removedName = removed.name; removedAddress = removed.address }
    }
    val shownRemoved = removed ?: removedName?.let { Removed(it, removedAddress ?: "") }

    val list = devices ?: return
    val s = settings ?: return
    // A device picked elsewhere (a notification tap) ends the Removed screen.
    LaunchedEffect(s.lastDevice) { if (s.lastDevice != null) { removedName = null; removedAddress = null } }
    if (list.isEmpty()) {
        EmptyHome(onAdd = onAdd, onSettings = onSettings)
        return
    }
    val current = if (shownRemoved != null && s.lastDevice == null) null
    else list.find { it.address == s.lastDevice } ?: list.first()

    val vm: DeviceViewModel? = current?.let { d ->
        viewModel(key = d.address) { DeviceViewModel(app, d.address) }
    }
    val outcomes by (vm?.commands?.outcomes ?: switcherCommands.outcomes).collectAsState()

    var switcher by remember { mutableStateOf(false) }

    fun pick(address: String) {
        switcher = false
        removedName = null
        removedAddress = null
        scope.launch { repo.store.updateSettings { it.copy(lastDevice = address) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { switcher = true }.padding(vertical = 4.dp),
                        ) {
                            Column(Modifier.weight(1f, fill = false)) {
                                current?.room?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (current != null) {
                                    DeviceName(current, style = MaterialTheme.typography.titleLarge)
                                } else {
                                    Text(
                                        shownRemoved?.name ?: "",
                                        style = MaterialTheme.typography.titleLarge,
                                        textDecoration = TextDecoration.LineThrough,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch device")
                        }
                        DropdownMenu(
                            expanded = switcher,
                            onDismissRequest = { switcher = false },
                            modifier = Modifier.widthIn(min = 300.dp),
                        ) {
                            Switcher(
                                devices = list,
                                current = current?.address,
                                outcomes = switcherOutcomes,
                                onPick = ::pick,
                                onSpray = { a -> switcherCommands.run(a) { repo.spray(a) } },
                                onAdd = { switcher = false; onAdd() },
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "App settings") }
                },
            )
        },
    ) { padding ->
        if (current == null || vm == null) {
            RemovedBody(shownRemoved?.name ?: "", shownRemoved?.address ?: "", Modifier.padding(padding))
        } else {
            // Read the status whenever this device comes on screen (opening the app,
            // switching, adding it) or the app returns to the foreground, unless recent.
            LaunchedEffect(current.address) { vm.refreshIfStale(current.statusAt) }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshIfStale(current.statusAt) }
            val refreshing by vm.refreshing.collectAsState()
            DeviceBody(
                current, vm, outcomes, refreshing,
                running = repo.runningSchedule(current),
                onSchedules = { onSchedules(current.address) },
                onDeviceSettings = { onDeviceSettings(current.address) },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/** A device's name; the suffix never gets cut off, the base name does. */
@Composable
fun DeviceName(d: SavedDevice, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier) {
        Text(d.name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        d.suffix?.let { Text(" ($it)", style = style, maxLines = 1) }
    }
}

/** The mode (or why the device can't be reached), then battery and refill as icons, each in a fixed width so rows line up. */
@Composable
private fun StatusLine(d: SavedDevice) {
    val s = d.status
    val style = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val reach = rememberReach(d)
    val btOn = bluetoothOn()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(84.dp)) {
            when {
                !bluetoothAllowed() -> Text("No Bluetooth", style = style, color = muted)
                !btOn -> Text("Bluetooth off", style = style, color = muted)
                reach == Reach.DISCONNECTED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(disconnectedDot(), CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text("Disconnected", style = style, color = muted)
                }
                reach == Reach.UNKNOWN || s == null -> WaitingDots(muted, dot = 4.dp, modifier = Modifier.padding(vertical = 6.dp))
                else -> Text(s.mode.shortLabel, style = style, color = muted)
            }
        }
        if (s == null) return@Row
        val battery = if (d.batteryLow) MaterialTheme.colorScheme.error else muted
        Row(Modifier.width(56.dp), verticalAlignment = Alignment.CenterVertically) {
            BatteryIcon(
                s.batteryPercent,
                if (d.batteryLow) battery else MaterialTheme.colorScheme.primary,
                Modifier.size(9.dp, 15.dp),
                outline = battery,
                stroke = 4f,
            )
            Spacer(Modifier.width(4.dp))
            Text(s.batteryPercent?.let { "$it%" } ?: "–", style = style, color = battery)
        }
        val refill = if (d.refillLow) MaterialTheme.colorScheme.error else muted
        RefillIcon(
            d.refillPercent,
            if (d.refillLow) refill else MaterialTheme.colorScheme.primary,
            Modifier.size(11.dp, 15.dp),
            outline = refill,
            stroke = 3.5f,
        )
        Spacer(Modifier.width(4.dp))
        Text(d.refillPercent?.let { "$it%" } ?: "–", style = style, color = refill)
    }
}

/** The switcher's content: devices grouped by room, each with Spray, then Add device. */
@Composable
private fun Switcher(
    devices: List<SavedDevice>,
    current: String?,
    outcomes: Map<String, Outcome>,
    onPick: (String) -> Unit,
    onSpray: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val groups = Naming.grouped(devices)
    // With no rooms at all, there's one unlabelled group.
    val showHeadings = groups.any { it.first != null }
    groups.forEach { (room, members) ->
        if (showHeadings) {
            Text(
                room ?: "No room",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp),
            )
        }
        members.forEach { d ->
            val selected = d.address == current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { onPick(d.address) }
                    .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DeviceName(d, MaterialTheme.typography.bodyLarge, Modifier.weight(1f, fill = false))
                        if (selected) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Current device",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    StatusLine(d)
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { onSpray(d.address) }) {
                    Text("Spray")
                    outcomes[d.address]?.let {
                        Spacer(Modifier.width(8.dp))
                        OutcomeMark(it)
                    }
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    DropdownMenuItem(
        text = { Text("Add device", color = MaterialTheme.colorScheme.primary) },
        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        onClick = onAdd,
    )
}

@Composable
private fun RemovedBody(name: String, address: String, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text("Removed $name", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                "ID $address",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyHome(onAdd: () -> Unit, onSettings: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_label)) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "App settings") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.app_full_name), style = MaterialTheme.typography.titleLarge)
                Text(
                    "Add your Godrej aer Smart Matic to spray, set auto mode and schedules, and get refill and battery alerts.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add device")
                }
            }
        }
    }
}
