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
    val endMinute get() = startMinute + sprays * interval

    fun hasDay(day: DayOfWeek) = dayMask and dayBit(day) != 0

    fun toBytes(): ByteArray =
        byteArrayOf(dayMask.toByte(), hour.toByte(), minute.toByte(), sprays.toByte(), interval.toByte())

    /** Problems that stop this schedule from being uploaded, or empty if none. */
    fun problems(): List<String> = buildList {
        if (dayMask and 0x7F == 0) add("Pick at least one day")
        if (interval !in INTERVALS) add("Interval must be 10, 20 or 40 minutes")
        if (sprays !in 1..255) add("Sprays must be between 1 and 255")
        if (hour !in 0..23 || minute !in 0..59) add("Invalid start time")
        if (endMinute > 24 * 60) add("Can't run past midnight; split it into two schedules")
    }

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

        /** Problems with the whole set: count and overlaps. */
        fun listProblems(list: List<Schedule>): List<String> = buildList {
            if (list.size > MAX) add("The device holds at most $MAX schedules")
            for (i in list.indices) for (j in i + 1 until list.size) {
                if (list[i].overlaps(list[j])) add("Schedules ${i + 1} and ${j + 1} overlap")
            }
        }
    }
}
