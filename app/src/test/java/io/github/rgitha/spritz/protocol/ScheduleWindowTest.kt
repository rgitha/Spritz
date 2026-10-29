package io.github.rgitha.spritz.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleWindowTest {
    private fun min(h: Int, m: Int) = h * 60 + m
    private val everyDay = 0x7F

    @Test fun endRoundsDownToWholeIntervals() {
        val s = Schedule.fromWindow(everyDay, min(8, 0), min(22, 30), 40)
        assertEquals(21, s.sprays)
        assertEquals(min(22, 0), s.endMinute)
        assertTrue(Schedule.windowErrors(everyDay, min(8, 0), min(22, 30), 40, emptyList()).isEmpty())
    }

    @Test fun endEqualToStartIsRefused() {
        val errors = Schedule.windowErrors(everyDay, min(8, 0), min(8, 0), 20, emptyList())
        assertEquals(1, errors.size)
        assertTrue(errors[0].startsWith("The end time must be later than the start time"))
    }

    @Test fun windowShorterThanOneIntervalIsRefused() {
        val errors = Schedule.windowErrors(everyDay, min(8, 0), min(8, 30), 40, emptyList())
        assertEquals(listOf("The window 08:00–08:30 is shorter than one 40-minute interval."), errors)
    }

    @Test fun overlapUsesTheRoundedEnd() {
        // 08:00–08:50 every 20 min sprays twice, so it really ends at 08:40.
        val existing = listOf(Schedule.fromWindow(64, min(8, 0), min(8, 50), 20))
        assertTrue(Schedule.windowErrors(64, min(8, 45), min(9, 30), 20, existing).isEmpty())
        assertEquals(
            listOf("This overlaps schedule 08:00–08:40."),
            Schedule.windowErrors(64 or 32, min(8, 30), min(9, 30), 20, existing),
        )
        // Different days never overlap.
        assertTrue(Schedule.windowErrors(32, min(8, 0), min(9, 0), 20, existing).isEmpty())
    }

    @Test fun otherChecks() {
        assertEquals(listOf("Pick at least one day."), Schedule.windowErrors(0, min(8, 0), min(9, 0), 20, emptyList()))
        val ten = (0 until 10).map { Schedule.fromWindow(1, min(it, 0), min(it, 30), 10) }
        assertEquals(
            listOf("The device holds at most 10 schedules."),
            Schedule.windowErrors(64, min(12, 0), min(13, 0), 10, ten),
        )
    }

    @Test fun bytesRoundTripThroughTheDisplayedEnd() {
        val bytes = byteArrayOf(68, 7, 30, 6, 20)
        val read = Schedule.fromBytes(bytes)!!
        val shownEnd = read.displayEndMinute
        assertEquals(min(9, 30), shownEnd)
        val again = Schedule.fromWindow(read.dayMask, read.startMinute, shownEnd, read.interval)
        assertArrayEquals(bytes, again.toBytes())
    }

    @Test fun findsTheRunningSchedule() {
        val weekdays = Schedule.fromWindow(64 or 32 or 16 or 8 or 4, min(8, 0), min(22, 0), 40)
        val sunday = Schedule.fromWindow(1, min(10, 0), min(12, 0), 20)
        val list = listOf(weekdays, sunday)
        assertEquals(weekdays, Schedule.running(list, java.time.DayOfWeek.MONDAY, min(12, 0), 40))
        assertEquals(sunday, Schedule.running(list, java.time.DayOfWeek.SUNDAY, min(11, 0), null))
        // Wrong interval, wrong day, or outside the window: nothing.
        assertEquals(null, Schedule.running(list, java.time.DayOfWeek.MONDAY, min(12, 0), 20))
        assertEquals(null, Schedule.running(list, java.time.DayOfWeek.SATURDAY, min(12, 0), 40))
        assertEquals(null, Schedule.running(list, java.time.DayOfWeek.SUNDAY, min(13, 0), 20))
    }
}
