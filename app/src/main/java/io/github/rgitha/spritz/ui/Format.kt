package io.github.rgitha.spritz.ui

import android.text.format.DateUtils
import io.github.rgitha.spritz.ble.AerException
import io.github.rgitha.spritz.ble.RefusedException
import io.github.rgitha.spritz.protocol.Mode
import io.github.rgitha.spritz.protocol.ModeSource
import io.github.rgitha.spritz.protocol.Schedule
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

fun Mode.label(): String {
    val every = interval?.let { " · every $it min" } ?: ""
    return when (source) {
        null -> "Idle"
        ModeSource.APP -> "Auto$every"
        ModeSource.SCHEDULE -> "Schedule running$every"
        ModeSource.BUTTON -> "Auto from device button$every"
    }
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

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

fun minuteOfDay(m: Int): String =
    if (m >= 24 * 60) "midnight" else LocalTime.of(m / 60, m % 60).format(timeFormat)

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

fun Schedule.summary() =
    "${minuteOfDay(startMinute)} – ${minuteOfDay(endMinute)} · $sprays sprays, every $interval min"
