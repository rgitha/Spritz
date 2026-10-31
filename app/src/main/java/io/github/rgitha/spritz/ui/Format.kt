package io.github.rgitha.spritz.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.rgitha.spritz.ble.AerException
import io.github.rgitha.spritz.ble.BusyException
import io.github.rgitha.spritz.ble.RefusedException
import io.github.rgitha.spritz.protocol.Schedule
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** A minute of the day in the phone's time format, 12- or 24-hour. */
fun clockText(context: Context, minuteOfDay: Int): String {
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(java.util.Calendar.MINUTE, minuteOfDay % 60)
    }
    return android.text.format.DateFormat.getTimeFormat(context).format(cal.time)
}

/** [clockText] bound to the current context, for composables. */
@Composable
fun rememberClock(): (Int) -> String {
    val context = LocalContext.current
    return remember(context) { { m: Int -> clockText(context, m) } }
}

/** One or two words for why a command failed, where there's no room for more. */
fun Throwable.shortReason(): String = when (this) {
    is RefusedException -> "Refused"
    is SecurityException -> "No permission"
    is AerException -> when (kind) {
        AerException.Kind.NOT_IN_RANGE -> "Not in range"
        AerException.Kind.BLUETOOTH_OFF -> "Bluetooth off"
        AerException.Kind.NO_PERMISSION -> "No permission"
        AerException.Kind.NO_REPLY -> "No reply"
        AerException.Kind.OTHER -> "Failed"
    }
    else -> "Failed"
}

fun Throwable.userMessage(): String = when (this) {
    is RefusedException -> "The device refused that"
    is BusyException -> message ?: "The device is busy"
    is AerException -> message ?: "Couldn't reach the device"
    is SecurityException -> "Bluetooth permission not granted"
    // Anything else is a system error; its message would only be a class name.
    else -> "Couldn't reach the device"
}

/** How long ago [millis] was, briefly: "Just now", "5 min ago", "3 h ago", "2 d ago". */
fun ago(millis: Long): String {
    val minutes = ((System.currentTimeMillis() - millis) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

/** "Mon–Fri", "Every day", "Weekends" or "Mon, Wed, Sat". */
fun daysLabel(mask: Int): String {
    val days = DayOfWeek.entries.filter { mask and Schedule.dayBit(it) != 0 }
    fun short(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    return when {
        days.size == 7 -> "Every day"
        days == DayOfWeek.entries.take(5) -> "Weekdays"
        days == listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
        days.isEmpty() -> "No days"
        else -> days.joinToString(", ") { short(it) }
    }
}
