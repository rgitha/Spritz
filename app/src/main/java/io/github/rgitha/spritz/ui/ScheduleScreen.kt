package io.github.rgitha.spritz.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
            )
        },
        bottomBar = {
            // Shown while there's something to save, and until the save's outcome clears.
            val saveOutcome = outcomes[ScheduleViewModel.KEY_SAVE]
            if (dirty || saveOutcome != null) SaveBar(dirty, saveOutcome, vm::save)
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
private fun SaveBar(dirty: Boolean, outcome: Outcome?, onSave: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                if (dirty) "Not saved to device" else "Saved to device",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onSave, enabled = dirty && outcome != Outcome.RUNNING) {
                Text("Save")
                if (outcome != null) {
                    Spacer(Modifier.width(8.dp))
                    OutcomeMark(outcome)
                }
            }
        }
    }
}

/** Time range over interval on the left; the week on the right, so rows line up. */
@Composable
private fun ScheduleItem(s: Schedule, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val clock = rememberClock()
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                // Breaks only after the dash when a 12-hour range doesn't fit on one line.
                Text(
                    "${clock(s.startMinute).unbroken()}\u00A0– ${clock(s.displayEndMinute).unbroken()}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${s.interval} min",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            WeekDots(s.dayMask)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private fun String.unbroken() = replace(' ', '\u00A0')

/** Monday to Sunday, filled where the schedule runs. */
@Composable
private fun WeekDots(mask: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = daysLabel(mask) },
    ) {
        DayOfWeek.entries.forEach { day ->
            val on = mask and Schedule.dayBit(day) != 0
            DayMark(day, on, Modifier.size(20.dp), MaterialTheme.typography.labelSmall)
        }
    }
}

/** A day's letter in a circle: filled when [on], outlined when not. */
@Composable
private fun DayMark(day: DayOfWeek, on: Boolean, modifier: Modifier, style: androidx.compose.ui.text.TextStyle) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(CircleShape)
            .then(if (on) Modifier.background(colors.primary) else Modifier.border(1.dp, colors.outline, CircleShape)),
    ) {
        Text(
            day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
            style = style,
            color = if (on) colors.onPrimary else colors.onSurfaceVariant,
        )
    }
}

private val DAY_PRESETS = listOf("Weekdays" to 0b1111100, "Weekends" to 0b0000011, "Every day" to 0b1111111)

private class TimePreset(val label: String, val start: Int, val end: Int)

private val TIME_PRESETS = listOf(
    TimePreset("Morning", 7 * 60, 10 * 60),
    TimePreset("Afternoon", 12 * 60, 15 * 60),
    TimePreset("Evening", 17 * 60, 20 * 60),
)

/** A field's heading with its shortcuts on the right, kept small so the fields stay the main controls. */
@Composable
private fun Heading(title: String, shortcuts: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { shortcuts() }
    }
}

@Composable
private fun ShortcutChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.secondaryContainer else Color.Transparent,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.height(24.dp).wrapContentHeight().padding(horizontal = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditor(initial: Schedule?, others: List<Schedule>, onDismiss: () -> Unit, onDone: (Schedule) -> Unit) {
    var days by remember { mutableIntStateOf(initial?.dayMask ?: 0x7F) }
    val clock = rememberClock()
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    // A new schedule starts on the first time preset that's free on every day.
    val preset = remember {
        TIME_PRESETS.firstOrNull { Schedule.windowErrors(0x7F, it.start, it.end, 20, others, clock).isEmpty() }
            ?: TIME_PRESETS.first()
    }
    val initialStart = initial?.startMinute ?: preset.start
    val start = rememberTimePickerState(initialStart / 60, initialStart % 60, is24Hour = is24)
    // The device stores a spray count; editing shows the end it works out to.
    val initialEnd = initial?.displayEndMinute ?: preset.end
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
                Heading("Days") {
                    DAY_PRESETS.forEach { (label, mask) ->
                        ShortcutChip(label, selected = days == mask) { days = mask }
                    }
                }
                // One row; the circles shrink to fit a narrow dialog.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        val bit = Schedule.dayBit(day)
                        DayMark(
                            day,
                            on = days and bit != 0,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(max = 40.dp)
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .semantics { contentDescription = day.getDisplayName(TextStyle.FULL, Locale.getDefault()) }
                                .toggleable(days and bit != 0, role = Role.Checkbox) { days = days xor bit },
                        )
                    }
                }
                Heading("Time") {
                    TIME_PRESETS.forEach { p ->
                        ShortcutChip(p.label, selected = startMin == p.start && endMin == p.end) {
                            start.hour = p.start / 60
                            start.minute = p.start % 60
                            end.hour = p.end / 60
                            end.minute = p.end % 60
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Start", style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(onClick = { picking = start }, modifier = Modifier.fillMaxWidth()) {
                            Text(clock(startMin), maxLines = 1)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("End", style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(onClick = { picking = end }, modifier = Modifier.fillMaxWidth()) {
                            Text(clock(endMin), maxLines = 1)
                        }
                    }
                }
                Text("Interval", style = MaterialTheme.typography.labelLarge)
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
