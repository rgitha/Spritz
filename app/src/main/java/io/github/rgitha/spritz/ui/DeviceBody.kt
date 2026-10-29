package io.github.rgitha.spritz.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rgitha.spritz.data.BatteryAge
import io.github.rgitha.spritz.data.BatteryLog
import io.github.rgitha.spritz.data.SavedDevice
import io.github.rgitha.spritz.protocol.Battery
import io.github.rgitha.spritz.protocol.DeviceStatus
import io.github.rgitha.spritz.protocol.INTERVALS
import io.github.rgitha.spritz.protocol.ModeSource
import io.github.rgitha.spritz.protocol.Refill
import io.github.rgitha.spritz.protocol.Schedule
import java.util.Date
import java.util.Locale

/** The current device's screen, below the top bar. */
@Composable
fun DeviceBody(
    d: SavedDevice,
    vm: DeviceViewModel,
    outcomes: Map<String, Outcome>,
    refreshing: Boolean,
    running: Schedule?,
    onSchedules: () -> Unit,
    onDeviceSettings: () -> Unit,
    modifier: Modifier,
) {
    var sprays by remember { mutableStateOf(false) }
    var battery by remember { mutableStateOf(false) }
    val age = BatteryLog.age(d.battery, System.currentTimeMillis())

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
        StatusCard(d, age, onBattery = { battery = true }, onSprays = { sprays = true })
        Button(onClick = vm::spray, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            LabelWithMark(
                "Spray now",
                outcomes[DeviceViewModel.KEY_SPRAY],
                MaterialTheme.typography.titleMedium,
                steady = true,
            )
        }
        AutoCard(d, outcomes, running, onSet = vm::setAuto, onSkip = vm::skipScheduleRun)
        Card(Modifier.fillMaxWidth()) {
            NavRow("Schedules", "Spray on set days and times", onSchedules)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            NavRow("Device settings", "Name, room, widget, info", onDeviceSettings)
        }
    }

    if (sprays && d.status != null) {
        SpraysSheet(d, d.status, outcomes[DeviceViewModel.KEY_REFILL], vm::refillReset) { sprays = false }
    }
    if (battery) {
        BatterySheet(d, age) { battery = false }
    }
}

@Composable
private fun NavRow(title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Days on these batteries: "88 days", ">64 days" when the change wasn't seen, "0 days" at first. */
private fun BatteryAge.short(): String = when {
    days == 0L -> "0 days"
    days == 1L -> if (exact) "1 day" else ">1 day"
    else -> if (exact) "$days days" else ">$days days"
}

@Composable
private fun StatusCard(d: SavedDevice, age: BatteryAge, onBattery: () -> Unit, onSprays: () -> Unit) {
    val s = d.status
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusChip(s?.mode?.shortLabel ?: "Not read yet", active = s?.mode?.isIdle == false)
            Row(Modifier.fillMaxWidth()) {
                val battery = s?.batteryPercent
                Level(
                    icon = { color -> BatteryIcon(battery, color, Modifier.size(28.dp, 46.dp).semantics { contentDescription = "Battery" }) },
                    percent = battery,
                    warn = d.batteryLow,
                    link = s?.let { "${age.short()} used" },
                    onLink = onBattery,
                    modifier = Modifier.weight(1f),
                )
                val refill = d.refillPercent
                Level(
                    icon = { color -> RefillIcon(refill, color, Modifier.size(28.dp, 38.dp).semantics { contentDescription = "Refill" }) },
                    percent = refill,
                    warn = d.refillLow,
                    link = s?.let { "${sprayCount(d, it)} sprays" },
                    onLink = onSprays,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun sprayCount(d: SavedDevice, s: DeviceStatus) = (s.totalSprays - d.refillBaseline).coerceAtLeast(0)

@Composable
private fun StatusChip(text: String, active: Boolean) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val dot = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(28.dp).background(bg, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp),
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** A level icon beside its percentage, with an optional tappable detail under it. */
@Composable
private fun Level(
    icon: @Composable (fill: Color) -> Unit,
    percent: Int?,
    warn: Boolean,
    link: String?,
    onLink: () -> Unit,
    modifier: Modifier,
) {
    val color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        icon(color)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                percent?.let { "$it%" } ?: "–",
                style = MaterialTheme.typography.headlineMedium,
                color = if (warn) color else MaterialTheme.colorScheme.onSurface,
            )
            if (link != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.heightIn(min = 32.dp).clickable(onClick = onLink),
                ) {
                    Text(link, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoCard(
    d: SavedDevice,
    outcomes: Map<String, Outcome>,
    running: Schedule?,
    onSet: (Int?) -> Unit,
    onSkip: () -> Unit,
) {
    val mode = d.status?.mode
    val scheduled = mode?.source == ModeSource.SCHEDULE
    val clock = rememberClock()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Auto mode", style = MaterialTheme.typography.titleMedium)
            Text(
                "Set device for automatic interval spray",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val current = if (mode?.isIdle != false) null else mode.interval
            val options = listOf<Int?>(null) + INTERVALS
            // During a schedule the row shows its interval but can't be changed.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, interval ->
                    val selected = d.status != null && current == interval
                    val outcome = outcomes[DeviceViewModel.autoKey(interval)]
                    SegmentedButton(
                        selected = selected,
                        onClick = { if (current != interval) onSet(interval) },
                        enabled = !scheduled,
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        icon = {
                            // The spinner and cross take the place of the selection check.
                            when (outcome) {
                                Outcome.RUNNING, Outcome.FAILED -> OutcomeMark(outcome)
                                else -> SegmentedButtonDefaults.Icon(active = selected)
                            }
                        },
                        modifier = Modifier.height(48.dp),
                    ) {
                        if (interval == null) {
                            Text("Off", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        } else {
                            Row {
                                Text(
                                    "$interval",
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.alignByBaseline(),
                                )
                                Text(
                                    "min",
                                    fontSize = 11.sp,
                                    modifier = Modifier.alignByBaseline().padding(start = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            when (mode?.source) {
                ModeSource.SCHEDULE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Schedule running",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        running?.let {
                            Text(
                                "${clock(it.startMinute)} to ${clock(it.displayEndMinute)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Button(onClick = onSkip) {
                        LabelWithMark("Skip", outcomes[DeviceViewModel.KEY_SKIP])
                    }
                }
                ModeSource.BUTTON -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "From device button",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Unit
            }
        }
    }
}

/** The spray counters by source, with Reset count for a new refill. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpraysSheet(
    d: SavedDevice,
    s: DeviceStatus,
    resetOutcome: Outcome?,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    fun List<Long>.at(i: Int) = getOrElse(i) { 0L }
    fun split(list: List<Long>, from: Int) =
        INTERVALS.mapIndexed { i, v -> "$v min: ${list.at(from + i)}" }.joinToString(" · ")
    val auto = s.appSprays.drop(1).take(3).sum()
    val rows = listOf(
        Triple("Spray now", "From app", s.appSprays.at(0)),
        Triple("Auto", split(s.appSprays, 1), auto),
        Triple("Schedule", split(s.scheduledSprays, 0), s.scheduledSprays.take(3).sum()),
        Triple("Device button", split(s.buttonSprays, 0), s.buttonSprays.take(3).sum()),
    )
    val primary = MaterialTheme.colorScheme.primary
    val colors = listOf(primary, primary.copy(alpha = 0.75f), primary.copy(alpha = 0.5f), primary.copy(alpha = 0.25f))
    val used = sprayCount(d, s)

    // Opened fully, so Reset count is in view without dragging.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Sprays since refill",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%,d".format(used), style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "of about %,d · %d%% left".format(Refill.CARTRIDGE_SPRAYS, d.refillPercent ?: 0),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                // One bar, split by source in the same order as the rows.
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    drawRoundRect(colors[3].copy(alpha = 0.15f), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f))
                    var x = 0f
                    rows.forEachIndexed { i, (_, _, count) ->
                        val w = size.width * count / Refill.CARTRIDGE_SPRAYS
                        drawRect(colors[i], topLeft = Offset(x, 0f), size = Size(w.coerceAtMost(size.width - x), size.height))
                        x += w
                    }
                }
            }
            rows.forEachIndexed { i, (title, detail, count) ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(10.dp).background(colors[i], CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("%,d".format(count), style = MaterialTheme.typography.titleMedium)
                }
            }
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                LabelWithMark("Reset count (new refill)", resetOutcome, steady = true)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Reset spray count?") },
            text = { Text("Do this after fitting a new refill. The refill level goes back to 100%.") },
            confirmButton = { TextButton(onClick = { confirm = false; onReset() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** How long the current batteries have lasted, with their level over time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatterySheet(d: SavedDevice, age: BatteryAge, onDismiss: () -> Unit) {
    val dayMonth = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM")
    val fmt = java.text.SimpleDateFormat(dayMonth, Locale.getDefault())
    val s = d.status
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Current batteries",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (age.exact || age.days == 0L) age.short() else "At least ${age.days} days",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "since ${fmt.format(Date(age.since))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                if (s?.batteryMv != null) {
                    Text(
                        "Now ${s.batteryPercent}% · %.2f V".format(s.batteryMv / 1000.0),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BatteryChart(d, age.since)
            Row(Modifier.fillMaxWidth().padding(start = 40.dp)) {
                Text(fmt.format(Date(age.since)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("Today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Level against time; breaks where readings are more than two days apart, shaded. */
@Composable
private fun BatteryChart(d: SavedDevice, since: Long) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val gapFill = MaterialTheme.colorScheme.surfaceVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val segments = BatteryLog.segments(d.battery)
    Row(Modifier.fillMaxWidth().height(160.dp)) {
        Column(Modifier.width(40.dp).height(160.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf("100%", "50%", "0%").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = label) }
        }
        Canvas(Modifier.weight(1f).height(160.dp).padding(vertical = 6.dp)) {
            val now = System.currentTimeMillis()
            val span = (now - since).coerceAtLeast(1L).toFloat()
            fun x(at: Long) = size.width * ((at - since) / span).coerceIn(0f, 1f)
            fun y(mv: Int) = size.height * (1f - Battery.percent(mv) / 100f)
            listOf(0f, 0.5f, 1f).forEach {
                drawLine(grid, Offset(0f, size.height * it), Offset(size.width, size.height * it), strokeWidth = 1f)
            }
            for (i in 0 until segments.size - 1) {
                val from = x(segments[i].last().at)
                val to = x(segments[i + 1].first().at)
                drawRect(gapFill, topLeft = Offset(from, 0f), size = Size(to - from, size.height))
            }
            segments.forEach { seg ->
                if (seg.size == 1) {
                    drawCircle(line, radius = 4f, center = Offset(x(seg[0].at), y(seg[0].mv)))
                } else {
                    val path = Path().apply {
                        moveTo(x(seg[0].at), y(seg[0].mv))
                        seg.drop(1).forEach { lineTo(x(it.at), y(it.mv)) }
                    }
                    drawPath(path, line, style = Stroke(width = 5f, cap = StrokeCap.Round))
                }
            }
        }
    }
}

/**
 * A button label with the command's spinner, tick or cross beside it. With [steady],
 * space for the mark is kept on both sides, so the label doesn't move when it appears;
 * for wide buttons with room to spare.
 */
@Composable
fun LabelWithMark(label: String, outcome: Outcome?, style: TextStyle = LocalTextStyle.current, steady: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (steady) Spacer(Modifier.width(MARK_SPACE))
        Text(label, style = style)
        if (steady) {
            Box(Modifier.width(MARK_SPACE), contentAlignment = Alignment.CenterEnd) { OutcomeMark(outcome) }
        } else if (outcome != null) {
            Spacer(Modifier.width(8.dp))
            OutcomeMark(outcome)
        }
    }
}

/** The mark (18 dp) plus the gap before it. */
private val MARK_SPACE = 26.dp
