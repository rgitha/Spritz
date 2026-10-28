package io.github.rgitha.spritz.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rgitha.spritz.data.SpritzRepository
import io.github.rgitha.spritz.protocol.INTERVALS
import io.github.rgitha.spritz.protocol.Schedule
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

class ScheduleViewModel(app: Application, val address: String) : AndroidViewModel(app) {
    private val repo = SpritzRepository.get(app)

    /** The list as edited; null until read from the device. */
    val schedules = MutableStateFlow<List<Schedule>?>(null)
    /** True when [schedules] differs from what's on the device. */
    val dirty = MutableStateFlow(false)
    val busy = MutableStateFlow<String?>(null)
    val messages = Channel<String>(Channel.BUFFERED)

    init { load() }

    fun load() = op("Reading schedules…") {
        schedules.value = repo.readSchedules(address)
        dirty.value = false
    }

    fun put(index: Int?, s: Schedule) {
        val list = schedules.value.orEmpty().toMutableList()
        if (index == null) list += s else list[index] = s
        schedules.value = list.sortedWith(compareBy({ it.startMinute }, { -it.dayMask }))
        dirty.value = true
    }

    fun delete(index: Int) {
        schedules.value = schedules.value.orEmpty().filterIndexed { i, _ -> i != index }
        dirty.value = true
    }

    fun save() {
        val list = schedules.value ?: return
        val problems = list.flatMap { it.problems() } + Schedule.listProblems(list)
        if (problems.isNotEmpty()) {
            viewModelScope.launch { messages.send(problems.first()) }
            return
        }
        op("Saving to the device…", done = "Schedules saved") {
            repo.writeSchedules(address, list)
            schedules.value = repo.readSchedules(address)
            dirty.value = false
        }
    }

    /** Skips the rest of today's run of the schedule at [index], as stored on the device. */
    fun skipToday(index: Int) = op("Stopping…", done = "Skipped for today") { repo.stopRun(address, index) }

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
fun ScheduleScreen(address: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ScheduleViewModel = viewModel(key = "schedules-$address") { ScheduleViewModel(app, address) }
    val list by vm.schedules.collectAsState()
    val dirty by vm.dirty.collectAsState()
    val busy by vm.busy.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.receiveAsFlow().collect { snackbar.showSnackbar(it) } }

    /** null: closed; -1: new schedule; otherwise the index being edited. */
    var editing by remember { mutableStateOf<Int?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }

    val back = { if (dirty) confirmLeave = true else onBack() }
    androidx.activity.compose.BackHandler(enabled = dirty) { confirmLeave = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schedules") },
                navigationIcon = { BackButton(back) },
                actions = {
                    TextButton(onClick = vm::save, enabled = dirty && busy == null) { Text("Save") }
                },
            )
        },
        floatingActionButton = {
            if (list != null && list!!.size < Schedule.MAX) {
                ExtendedFloatingActionButton(
                    onClick = { editing = -1 },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add schedule") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(busy!!, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 4.dp))
            }
            val l = list
            when {
                l == null && busy == null -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = gap) {
                        Text("Couldn't read the schedules.")
                        Button(onClick = vm::load) { Text("Try again") }
                    }
                }
                l == null -> Unit
                l.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No schedules. Add one to spray on set days and times; all schedules run together.",
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = gap,
                ) {
                    item {
                        if (dirty) {
                            Text(
                                "Not saved yet. Tap Save to send these to the device.",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    itemsIndexed(l) { i, s ->
                        ScheduleItem(
                            s,
                            canSkip = !dirty && busy == null,
                            onEdit = { editing = i },
                            onDelete = { vm.delete(i) },
                            onSkip = { vm.skipToday(i) },
                        )
                    }
                }
            }
        }
    }

    editing?.let { index ->
        val initial = if (index >= 0) list?.getOrNull(index) else null
        ScheduleEditor(
            initial = initial,
            others = list.orEmpty().filterIndexed { i, _ -> i != index },
            onDismiss = { editing = null },
            onDone = { vm.put(if (index >= 0) index else null, it); editing = null },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Discard changes?") },
            text = { Text("Your changes haven't been sent to the device.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun ScheduleItem(s: Schedule, canSkip: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onSkip: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(daysLabel(s.dayMask)) },
            supportingContent = { Text(s.summary()) },
            trailingContent = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(
                            text = { Text("Skip today's run") },
                            enabled = canSkip,
                            onClick = { menu = false; onSkip() },
                        )
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditor(initial: Schedule?, others: List<Schedule>, onDismiss: () -> Unit, onDone: (Schedule) -> Unit) {
    var days by remember { mutableIntStateOf(initial?.dayMask ?: 0x7F) }
    val time = rememberTimePickerState(initial?.hour ?: 8, initial?.minute ?: 0)
    var sprays by remember { mutableIntStateOf(initial?.sprays ?: 6) }
    var interval by remember { mutableIntStateOf(initial?.interval ?: 20) }
    var pickingTime by remember { mutableStateOf(false) }

    val s = Schedule(days, time.hour, time.minute, sprays, interval)
    val problems = s.problems() + others.filter { it.overlaps(s) }.map { "Overlaps ${daysLabel(it.dayMask)} ${it.summary()}" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New schedule" else "Edit schedule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = gap) {
                Text("Days", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        val bit = Schedule.dayBit(day)
                        FilterChip(
                            selected = days and bit != 0,
                            onClick = { days = days xor bit },
                            label = { Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault())) },
                        )
                    }
                }
                Text("Start", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { pickingTime = true }) { Text(minuteOfDay(s.startMinute)) }
                Text("Every", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    INTERVALS.forEachIndexed { i, v ->
                        SegmentedButton(
                            selected = interval == v,
                            onClick = { interval = v },
                            shape = SegmentedButtonDefaults.itemShape(i, INTERVALS.size),
                        ) { Text("$v min") }
                    }
                }
                Text("Sprays", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { sprays-- }, enabled = sprays > 1) {
                        Icon(Icons.Default.Remove, contentDescription = "Fewer")
                    }
                    Text("$sprays", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { sprays++ }, enabled = sprays < 255) {
                        Icon(Icons.Default.Add, contentDescription = "More")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("ends ${minuteOfDay(s.endMinute)}", style = MaterialTheme.typography.bodyMedium)
                }
                problems.forEach {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = problems.isEmpty(), onClick = { onDone(s) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingTime) {
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            text = { TimePicker(state = time) },
            confirmButton = { TextButton(onClick = { pickingTime = false }) { Text("OK") } },
        )
    }
}
