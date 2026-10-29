package io.github.rgitha.spritz.protocol

import java.time.DayOfWeek

/**
 * One schedule window: on the days in [dayMask], spray [sprays] times every
 * [interval] minutes from [hour]:[minute].
 */
data class Schedule(
    val dayMask: Int,
    val hour: Int,
    val minute: Int,
    val sprays: Int,
    val interval: Int,
) {
    val startMinute get() = hour * 60 + minute

    /** When the last spray falls: the end rounded down to a whole interval. */
    val endMinute get() = startMinute + sprays * interval

    /** [endMinute] as shown to the user: never past 23:59. */
    val displayEndMinute get() = minOf(endMinute, LAST_MINUTE)

    fun hasDay(day: DayOfWeek) = dayMask and dayBit(day) != 0

    fun toBytes(): ByteArray =
        byteArrayOf(dayMask.toByte(), hour.toByte(), minute.toByte(), sprays.toByte(), interval.toByte())

    /** Shares a day with [other] and their windows, using the rounded ends, overlap. */
    fun overlaps(other: Schedule): Boolean =
        dayMask and other.dayMask != 0 &&
            startMinute < other.endMinute && other.startMinute < endMinute

    companion object {
        const val MAX = 10

        fun dayBit(day: DayOfWeek): Int = 64 shr (day.value - 1) // Mon 64 … Sun 1

        fun fromBytes(b: ByteArray): Schedule? {
            if (b.size < 5) return null
            val u = b.map { it.toInt() and 0xFF }
            return Schedule(u[0], u[1], u[2], u[3], u[4])
        }

        /** Parses the S array of a read-schedules reply (159). */
        fun listFrom(reply: Reply): List<Schedule> =
            (reply.fields["S"] as? List<*>).orEmpty().mapNotNull { entry ->
                ((entry as? Map<*, *>)?.get("s") as? ByteArray)?.let(::fromBytes)
            }

        const val LAST_MINUTE = 23 * 60 + 59

        /** Sprays for a window from [startMin] to [endMin], rounded down to whole intervals. */
        fun spraysFor(startMin: Int, endMin: Int, interval: Int): Int =
            ((endMin - startMin) / interval).coerceIn(0, 255)

        /** The schedule the device stores for a window given by its start and end times. */
        fun fromWindow(dayMask: Int, startMin: Int, endMin: Int, interval: Int) = Schedule(
            dayMask, startMin / 60, startMin % 60, spraysFor(startMin, endMin, interval), interval,
        )

        /**
         * Why a window can't be saved, as messages for the user; empty if it can.
         * [others] are the schedules it would sit beside (not the one being edited).
         */
        fun windowErrors(
            dayMask: Int,
            startMin: Int,
            endMin: Int,
            interval: Int,
            others: List<Schedule>,
            time: (Int) -> String = ::hhmm,
        ): List<String> =
            buildList {
                if (dayMask and 0x7F == 0) add("Pick at least one day.")
                if (endMin <= startMin) {
                    add(
                        "The end time must be later than the start time on the same day. For a window " +
                            "across midnight, add two schedules: one until 23:59 and one from 00:00 on the following days.",
                    )
                } else if (spraysFor(startMin, endMin, interval) < 1) {
                    add("The window ${time(startMin)}–${time(endMin)} is shorter than one $interval-minute interval.")
                }
                if (others.size + 1 > MAX) add("The device holds at most $MAX schedules.")
                if (endMin > startMin && spraysFor(startMin, endMin, interval) >= 1) {
                    val s = fromWindow(dayMask, startMin, endMin, interval)
                    others.firstOrNull { it.overlaps(s) }?.let {
                        add("This overlaps schedule ${time(it.startMinute)}–${time(it.displayEndMinute)}.")
                    }
                }
            }

        /**
         * The schedule running at [minuteOfDay] on [day] with [interval], if any. The device
         * checks its schedules every 15–20 s, so a minute past the end still counts.
         */
        fun running(list: List<Schedule>, day: DayOfWeek, minuteOfDay: Int, interval: Int?): Schedule? =
            list.firstOrNull {
                it.hasDay(day) && minuteOfDay >= it.startMinute && minuteOfDay <= it.endMinute &&
                    (interval == null || it.interval == interval)
            }

        fun hhmm(minuteOfDay: Int) = "%02d:%02d".format(java.util.Locale.ROOT, minuteOfDay / 60, minuteOfDay % 60)
    }
}
