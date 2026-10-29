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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

class ScheduleViewModel(app: Application, val address: String) : AndroidViewModel(app) {
    private val repo = SpritzRepository.get(app)

    /** The list as edited; null until read from the device. */
    val schedules = MutableStateFlow<List<Schedule>?>(null)
    /** True when [schedules] differs from what's on the device. */
    val dirty = MutableStateFlow(false)
    val loading = MutableStateFlow(false)
    val commands = Commands(viewModelScope, app)

    init { load() }

    fun load() {
        if (loading.value) return
        loading.value = true
        commands.quiet {
            try {
                schedules.value = repo.readSchedules(address)
                dirty.value = false
            } finally {
                loading.value = false
            }
        }
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
        // Each window is checked in the editor; only the count can still be wrong here.
        if (list.size > Schedule.MAX) {
            commands.toast("The device holds at most ${Schedule.MAX} schedules.")
            return
        }
        commands.run(KEY_SAVE) {
            repo.writeSchedules(address, list)
            schedules.value = repo.readSchedules(address)
            dirty.value = false
        }
    }

    companion object {
        const val KEY_SAVE = "save"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(address: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ScheduleViewModel = viewModel(key = "schedules-$address") { ScheduleViewModel(app, address) }
    val list by vm.schedules.collectAsState()
    val dirty by vm.dirty.collectAsState()
    val loading by vm.loading.collectAsState()
    val outcomes by vm.commands.outcomes.collectAsState()

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
                    val saveOutcome = outcomes[ScheduleViewModel.KEY_SAVE]
                    TextButton(onClick = vm::save, enabled = dirty || saveOutcome != null) {
                        Text("Save")
                        if (saveOutcome != null) {
                            Spacer(Modifier.width(8.dp))
                            OutcomeMark(saveOutcome)
                        }
                    }
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
    ) { padding ->
        Column(Modifier.padding(padding)) {
            val l = list
            when {
                l == null && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                l == null -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = gap) {
                        Text("Couldn't read the schedules.")
                        Button(onClick = vm::load) { Text("Try again") }
                    }
                }
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
                        ScheduleItem(s, onEdit = { editing = i }, onDelete = { vm.delete(i) })
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
private fun ScheduleItem(s: Schedule, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val clock = rememberClock()
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(daysLabel(s.dayMask)) },
            supportingContent = { Text(s.summary(clock)) },
            trailingContent = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
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
    val clock = rememberClock()
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val start = rememberTimePickerState(initial?.hour ?: 8, initial?.minute ?: 0, is24Hour = is24)
    // The device stores a spray count; editing shows the end it works out to.
    val initialEnd = initial?.displayEndMinute ?: (10 * 60)
    val end = rememberTimePickerState(initialEnd / 60, initialEnd % 60, is24Hour = is24)
    var interval by remember { mutableIntStateOf(initial?.interval ?: 20) }
    var picking by remember { mutableStateOf<TimePickerState?>(null) }

    val startMin = start.hour * 60 + start.minute
    val endMin = end.hour * 60 + end.minute
    val errors = Schedule.windowErrors(days, startMin, endMin, interval, others, clock)
    val s = Schedule.fromWindow(days, startMin, endMin, interval)

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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Start", style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(onClick = { picking = start }) { Text(clock(startMin)) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("End", style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(onClick = { picking = end }) { Text(clock(endMin)) }
                    }
                }
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
                // The rounding, visible before saving.
                if (s.sprays >= 1 && endMin > startMin) {
                    Text(
                        "Ends at ${clock(s.displayEndMinute)} (${s.sprays} sprays)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                errors.forEach {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = errors.isEmpty(), onClick = { onDone(s) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    picking?.let { state ->
        AlertDialog(
            onDismissRequest = { picking = null },
            text = { TimePicker(state = state) },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("OK") } },
        )
    }
}
