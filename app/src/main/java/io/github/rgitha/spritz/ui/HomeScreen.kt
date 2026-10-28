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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.rgitha.spritz.notify.Notifier
import io.github.rgitha.spritz.protocol.INTERVALS
import io.github.rgitha.spritz.protocol.ModeSource
import io.github.rgitha.spritz.protocol.StopTarget
import io.github.rgitha.spritz.widget.SprayWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class DeviceViewModel(app: Application, val address: String) : AndroidViewModel(app) {
    private val repo = SpritzRepository.get(app)
    val device = repo.store.device(address)
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

    fun skipScheduleRun() = commands.run(KEY_SKIP) { repo.stopRun(address, StopTarget.CURRENT_RUN) }

    fun refillReset() = commands.run(KEY_REFILL) { repo.refillReset(address) }

    fun edit(name: String, room: String?) = viewModelScope.launch { repo.store.edit(address, name, room) }

    fun useForWidget() = viewModelScope.launch {
        repo.store.updateSettings { it.copy(widgetDevice = address) }
        SprayWidget.refresh(getApplication())
        commands.toast("The widget now sprays this device")
    }

    fun remove(then: () -> Unit) = viewModelScope.launch {
        repo.removeDevice(address)
        SprayWidget.refresh(getApplication())
        then()
    }

    companion object {
        const val STALE_MS = 60_000L
        const val KEY_SPRAY = "spray"
        const val KEY_SKIP = "skip"
        const val KEY_REFILL = "refill"
        fun autoKey(interval: Int?) = "auto:${interval ?: 0}"
    }
}

/**
 * The app's only home: the current device, with a switcher on its name listing every
 * device (grouped by room, each with Spray) and Add device. With no devices, an empty
 * state. After a removal, a blank "Removed" screen until another device is picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAdd: () -> Unit,
    onSchedules: (String) -> Unit,
    onSettings: () -> Unit,
    onLog: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val repo = remember { SpritzRepository.get(app) }
    val devices by repo.store.devices.collectAsState(initial = null)
    val settings by repo.store.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val switcherCommands = remember(scope) { Commands(scope, app) }
    val switcherOutcomes by switcherCommands.outcomes.collectAsState()

    // The device just removed, shown struck through until another one is picked.
    var removedName by rememberSaveable { mutableStateOf<String?>(null) }
    var removedAddress by rememberSaveable { mutableStateOf<String?>(null) }

    val list = devices ?: return
    val s = settings ?: return
    // A device picked elsewhere (a notification tap) ends the Removed screen.
    LaunchedEffect(s.lastDevice) { if (s.lastDevice != null) { removedName = null; removedAddress = null } }
    if (list.isEmpty()) {
        EmptyHome(onAdd = onAdd, onSettings = onSettings)
        return
    }
    val current = if (removedName != null) null
    else list.find { it.address == s.lastDevice } ?: list.first()

    val vm: DeviceViewModel? = current?.let { d ->
        viewModel(key = d.address) { DeviceViewModel(app, d.address) }
    }
    val outcomes by (vm?.commands?.outcomes ?: switcherCommands.outcomes).collectAsState()

    var switcher by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmRefill by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

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
                                        removedName ?: "",
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
                    if (vm != null) {
                        IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                    if (vm != null && current != null) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Edit name and room") }, onClick = { menu = false; editing = true })
                            val isWidget = s.widgetDevice == current.address
                            DropdownMenuItem(
                                text = { Text(if (isWidget) "Used by the widget" else "Use for the widget") },
                                enabled = !isWidget,
                                onClick = { menu = false; vm.useForWidget() },
                            )
                            DropdownMenuItem(text = { Text("Connection log") }, onClick = { menu = false; onLog() })
                            DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; confirmRemove = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (current == null || vm == null) {
            RemovedBody(removedName ?: "", removedAddress ?: "", Modifier.padding(padding))
        } else {
            // Read the status whenever this device comes on screen (opening the app,
            // switching, adding it) or the app returns to the foreground, unless recent.
            LaunchedEffect(current.address) { vm.refreshIfStale(current.statusAt) }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshIfStale(current.statusAt) }
            val refreshing by vm.refreshing.collectAsState()
            DeviceBody(
                current, vm, outcomes, refreshing,
                onSchedules = { onSchedules(current.address) },
                onRefillReset = { confirmRefill = true },
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (editing && current != null && vm != null) {
        SetupDialog(
            address = current.address,
            initialName = current.name,
            initialRoom = current.room,
            others = list.filter { it.address != current.address },
            isNew = false,
            onDismiss = { editing = false },
            onConfirm = { name, room -> vm.edit(name, room); editing = false },
        )
    }
    if (confirmRefill && vm != null) {
        AlertDialog(
            onDismissRequest = { confirmRefill = false },
            title = { Text("Reset refill?") },
            text = { Text("Do this after fitting a new cartridge. The refill level goes back to 100%.") },
            confirmButton = { TextButton(onClick = { confirmRefill = false; vm.refillReset() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmRefill = false }) { Text("Cancel") } },
        )
    }
    if (confirmRemove && current != null && vm != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${current.displayName}?") },
            text = {
                Text("ID ${current.address}. Schedules stay on the device and keep running. You can add it again later.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    removedName = current.displayName
                    removedAddress = current.address
                    vm.remove {}
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** A device's name; the suffix never gets cut off, the base name does. */
@Composable
private fun DeviceName(d: SavedDevice, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier) {
        Text(d.name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        d.suffix?.let { Text(" ($it)", style = style, maxLines = 1) }
    }
}

private fun SavedDevice.statusLine(): String {
    val s = status ?: return "Not read yet"
    val battery = s.batteryPercent?.let { "Battery $it%" } ?: "Battery –"
    return "$battery · Refill $refillPercent% · ${s.mode.label()}"
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
                    Text(
                        d.address,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        d.statusLine(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
private fun DeviceBody(
    d: SavedDevice,
    vm: DeviceViewModel,
    outcomes: Map<String, Outcome>,
    refreshing: Boolean,
    onSchedules: () -> Unit,
    onRefillReset: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = gap,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Updated ${ago(d.statusAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (refreshing) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StatusCard(d)
        Button(onClick = vm::spray, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            LabelWithMark("Spray now", outcomes[DeviceViewModel.KEY_SPRAY], MaterialTheme.typography.titleMedium)
        }
        AutoCard(d, outcomes, onSet = vm::setAuto, onSkip = vm::skipScheduleRun)
        Card(onClick = onSchedules, modifier = Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text("Schedules") },
                supportingContent = { Text("Spray on set days and times, up to 10 windows") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
        }
        OutlinedButton(onClick = onRefillReset, modifier = Modifier.fillMaxWidth()) {
            LabelWithMark("Refill reset (after a new cartridge)", outcomes[DeviceViewModel.KEY_REFILL])
        }
        Text(
            "ID ${d.address}" + (d.status?.firmware?.let { " · Firmware $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
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

@Composable
private fun StatusCard(d: SavedDevice) {
    val s = d.status
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = gap) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                val battery = s?.batteryPercent
                Meter(
                    Icons.Default.Battery5Bar, "Battery", battery,
                    detail = when {
                        s?.batteryCritical == true -> "Flat: replace both AA cells"
                        s?.batteryMv != null -> "%.2f V".format(s.batteryMv / 1000.0)
                        else -> null
                    },
                    warn = s?.batteryCritical == true || (battery ?: 100) <= Notifier.LOW_BATTERY_PERCENT,
                    modifier = Modifier.weight(1f),
                )
                val refill = d.refillPercent
                Meter(
                    Icons.Default.WaterDrop, "Refill", refill,
                    detail = s?.let { "${(it.totalSprays - d.refillBaseline).coerceAtLeast(0)} sprays used" },
                    warn = (refill ?: 100) <= Notifier.LOW_REFILL_PERCENT,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(s?.mode?.label() ?: "Not read yet", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoCard(d: SavedDevice, outcomes: Map<String, Outcome>, onSet: (Int?) -> Unit, onSkip: () -> Unit) {
    val mode = d.status?.mode
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Auto mode", style = MaterialTheme.typography.titleMedium)
            if (mode?.source == ModeSource.SCHEDULE) {
                Text(
                    "A schedule is running. Stop it for today to use manual control; it comes back tomorrow.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onSkip) {
                    LabelWithMark("Skip the rest of today's run", outcomes[DeviceViewModel.KEY_SKIP])
                }
            } else {
                Text(
                    "Sprays at once, then every chosen interval.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val current = if (mode?.isIdle != false) null else mode.interval
                val options = listOf<Int?>(null) + INTERVALS
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, interval ->
                        val selected = d.status != null && current == interval
                        // The spinner and cross take the place of the selection check.
                        val outcome = outcomes[DeviceViewModel.autoKey(interval)]
                        SegmentedButton(
                            selected = selected,
                            onClick = { if (current != interval) onSet(interval) },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                            icon = {
                                when (outcome) {
                                    Outcome.RUNNING, Outcome.FAILED -> OutcomeMark(outcome)
                                    else -> SegmentedButtonDefaults.Icon(active = selected)
                                }
                            },
                        ) { Text(interval?.let { "$it min" } ?: "Off") }
                    }
                }
            }
        }
    }
}

/** A button label with the command's spinner, tick or cross beside it. */
@Composable
private fun LabelWithMark(label: String, outcome: Outcome?, style: TextStyle = LocalTextStyle.current) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = style)
        if (outcome != null) {
            Spacer(Modifier.width(8.dp))
            OutcomeMark(outcome)
        }
    }
}
