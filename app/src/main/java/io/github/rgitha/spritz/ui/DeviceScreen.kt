package io.github.rgitha.spritz.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rgitha.spritz.R
import io.github.rgitha.spritz.data.SavedDevice
import io.github.rgitha.spritz.data.SpritzRepository
import io.github.rgitha.spritz.notify.Notifier
import io.github.rgitha.spritz.protocol.INTERVALS
import io.github.rgitha.spritz.protocol.ModeSource
import io.github.rgitha.spritz.protocol.StopTarget
import io.github.rgitha.spritz.widget.SprayWidget
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class DeviceViewModel(app: Application, val address: String) : AndroidViewModel(app) {
    private val repo = SpritzRepository.get(app)
    val device = repo.store.device(address)
    val settings = repo.store.settings

    /** What's in progress, shown on the screen; null when idle. */
    val busy = MutableStateFlow<String?>(null)
    val messages = Channel<String>(Channel.BUFFERED)

    init { refresh() }

    fun refresh() = op("Reading status…") { repo.refresh(address) }

    fun spray() = op("Spraying…", done = "Sprayed") { repo.spray(address) }

    fun setAuto(interval: Int?) = if (interval == null) {
        op("Stopping auto mode…", done = "Auto mode off") { repo.stopAuto(address) }
    } else {
        op("Starting auto mode…", done = "Auto mode every $interval min") { repo.spray(address, interval) }
    }

    fun skipScheduleRun() =
        op("Stopping…", done = "Skipped the rest of today's run") { repo.stopRun(address, StopTarget.CURRENT_RUN) }

    fun refillReset() = op("Resetting refill…", done = "Refill reset") { repo.refillReset(address) }

    fun rename(name: String) = viewModelScope.launch { repo.store.rename(address, name.trim()) }

    fun useForWidget() = viewModelScope.launch {
        repo.store.updateSettings { it.copy(widgetDevice = address) }
        SprayWidget.refresh(getApplication())
        messages.send("The widget now sprays this device")
    }

    fun remove(then: () -> Unit) = viewModelScope.launch {
        repo.removeDevice(address)
        SprayWidget.refresh(getApplication())
        then()
    }

    private fun op(label: String, done: String? = null, block: suspend () -> Unit) {
        if (busy.value != null) return
        busy.value = label
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { done?.let { messages.send(it) } }
                .onFailure { messages.send(it.userMessage()) }
            busy.value = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(address: String, onBack: () -> Unit, onSchedules: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: DeviceViewModel = viewModel(key = address) { DeviceViewModel(app, address) }
    val device by vm.device.collectAsState(initial = null)
    val settings by vm.settings.collectAsState(initial = null)
    val busy by vm.busy.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.receiveAsFlow().collect { snackbar.showSnackbar(it) } }

    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmRefill by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device?.name ?: "") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = busy == null) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                        val isWidget = settings?.widgetDevice == address
                        DropdownMenuItem(
                            text = { Text(if (isWidget) "Used by the widget" else "Use for the widget") },
                            enabled = !isWidget,
                            onClick = { menu = false; vm.useForWidget() },
                        )
                        DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; confirmRemove = true })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val d = device ?: return@Scaffold
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = gap,
        ) {
            if (busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(busy!!, style = MaterialTheme.typography.bodySmall)
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Updated ${ago(d.statusAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusCard(d)
            Button(
                onClick = vm::spray,
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                if (busy == "Spraying…") CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Text("Spray now", style = MaterialTheme.typography.titleMedium)
            }
            AutoCard(d, enabled = busy == null, onSet = vm::setAuto, onSkip = vm::skipScheduleRun)
            Card(onClick = onSchedules, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Schedules") },
                    supportingContent = { Text("Spray on set days and times, up to 10 windows") },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                )
            }
            OutlinedButton(onClick = { confirmRefill = true }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                Text("Refill reset (after a new cartridge)")
            }
            d.status?.firmware?.let {
                Text("Firmware $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(device?.name ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.rename(name); renaming = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmRefill) {
        AlertDialog(
            onDismissRequest = { confirmRefill = false },
            title = { Text("Reset refill?") },
            text = { Text("Do this after fitting a new cartridge. The refill level goes back to 100%.") },
            confirmButton = { TextButton(onClick = { confirmRefill = false; vm.refillReset() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmRefill = false }) { Text("Cancel") } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${device?.name}?") },
            text = { Text("Schedules stay on the device and keep running.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.remove(onBack) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
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
private fun AutoCard(d: SavedDevice, enabled: Boolean, onSet: (Int?) -> Unit, onSkip: () -> Unit) {
    val mode = d.status?.mode
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Auto mode", style = MaterialTheme.typography.titleMedium)
            if (mode?.source == ModeSource.SCHEDULE) {
                Text(
                    "A schedule is running. Stop it for today to use manual control; it comes back tomorrow.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onSkip, enabled = enabled) { Text("Skip the rest of today's run") }
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
                        SegmentedButton(
                            selected = d.status != null && current == interval,
                            onClick = { if (current != interval) onSet(interval) },
                            enabled = enabled,
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(interval?.let { "$it min" } ?: "Off") }
                    }
                }
            }
        }
    }
}
