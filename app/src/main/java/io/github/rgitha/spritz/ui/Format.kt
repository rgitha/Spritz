package io.github.rgitha.spritz.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.rgitha.spritz.ble.AerException
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

fun Throwable.userMessage(): String = when (this) {
    is RefusedException -> "The device refused that"
    is AerException -> message ?: "Couldn't reach the device"
    is SecurityException -> "Bluetooth permission not granted"
    is IllegalStateException -> message ?: "Something went wrong"
    else -> message ?: "Couldn't reach the device"
}

fun ago(millis: Long): String =
    if (millis == 0L) "never"
    else DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

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

/** "8:00 AM–10:00 PM every 40 min", with the end rounded down to the last spray. */
fun Schedule.summary(clock: (Int) -> String) =
    "${clock(startMinute)}–${clock(displayEndMinute)} every $interval min"
