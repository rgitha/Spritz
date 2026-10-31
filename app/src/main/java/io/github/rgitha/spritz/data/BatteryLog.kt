package io.github.rgitha.spritz.data

import io.github.rgitha.spritz.protocol.Battery
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One stored battery reading. */
data class BatteryPoint(val at: Long, val mv: Int)

/**
 * Battery readings for the current batteries only: at most one a day, cleared when
 * new batteries are detected. [replacedAt] is when that was seen, if it has been.
 */
data class BatteryHistory(val points: List<BatteryPoint> = emptyList(), val replacedAt: Long? = null)

/** How long the current batteries have been in; [exact] is false when it's "at least". */
data class BatteryAge(val days: Long, val exact: Boolean, val since: Long)

object BatteryLog {
    /** A rise of more than this many points, ending at [NEW_MIN_PERCENT] or above, means new batteries. */
    const val NEW_JUMP_PERCENT = 10
    const val NEW_MIN_PERCENT = 90

    /** About a year of daily readings. */
    private const val MAX_POINTS = 400

    /** Readings further apart than this are drawn as a break in the chart. */
    const val GAP_MS = 2 * 24 * 60 * 60 * 1000L

    fun add(h: BatteryHistory, at: Long, mv: Int, zone: ZoneId = ZoneId.systemDefault()): BatteryHistory {
        val last = h.points.lastOrNull() ?: return h.copy(points = listOf(BatteryPoint(at, mv)))
        val now = Battery.percent(mv)
        if (now >= NEW_MIN_PERCENT && now - Battery.percent(last.mv) > NEW_JUMP_PERCENT) {
            return BatteryHistory(listOf(BatteryPoint(at, mv)), replacedAt = at)
        }
        if (day(last.at, zone) == day(at, zone)) return h
        return h.copy(points = (h.points + BatteryPoint(at, mv)).takeLast(MAX_POINTS))
    }

    /** How long the current batteries have been in; 0 days, not exact, with no history yet. */
    fun age(h: BatteryHistory, now: Long, zone: ZoneId = ZoneId.systemDefault()): BatteryAge {
        val since = h.replacedAt ?: h.points.firstOrNull()?.at ?: return BatteryAge(0, exact = false, since = now)
        val days = ChronoUnit.DAYS.between(day(since, zone), day(now, zone))
        return BatteryAge(days, exact = h.replacedAt != null, since = since)
    }

    /** The readings split where they're more than [GAP_MS] apart. */
    fun segments(h: BatteryHistory): List<List<BatteryPoint>> {
        val out = mutableListOf<MutableList<BatteryPoint>>()
        for (p in h.points) {
            val seg = out.lastOrNull()
            if (seg == null || p.at - seg.last().at > GAP_MS) out += mutableListOf(p) else seg += p
        }
        return out
    }

    private fun day(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
}
