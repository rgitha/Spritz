package io.github.rgitha.spritz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class BatteryLogTest {
    private val utc = ZoneOffset.UTC
    private val day = 24 * 60 * 60 * 1000L
    private fun add(h: BatteryHistory, dayNo: Long, mv: Int, hour: Int = 9) =
        BatteryLog.add(h, dayNo * day + hour * 3_600_000L, mv, utc)

    @Test fun keepsOneReadingADay() {
        var h = add(BatteryHistory(), 0, 3000)
        h = add(h, 0, 2990, hour = 18)
        h = add(h, 1, 2980)
        assertEquals(listOf(3000, 2980), h.points.map { it.mv })
    }

    @Test fun bigRiseToNinetyPlusMeansNewBatteries() {
        var h = add(BatteryHistory(), 0, 2600) // 57%
        h = add(h, 5, 3100) // 100%: +43 points
        assertEquals(1, h.points.size)
        assertEquals(5 * day + 9 * 3_600_000L, h.replacedAt)
    }

    @Test fun smallRiseOrLowLevelIsNotAChange() {
        var h = add(BatteryHistory(), 0, 2800) // 86%
        h = add(h, 1, 3100) // 100%: only +14
        assertNull(h.replacedAt)
        h = add(BatteryHistory(), 0, 2450) // 14%
        h = add(h, 1, 2700) // 78%: big rise but below 90%
        assertNull(h.replacedAt)
        assertEquals(2, h.points.size)
    }

    @Test fun ageIsExactOnlyAfterAChangeWasSeen() {
        val now = 10 * day + 12 * 3_600_000L
        val seen = add(add(BatteryHistory(), 0, 2600), 2, 3100)
        val age = BatteryLog.age(seen, now, utc)!!
        assertTrue(age.exact)
        assertEquals(8, age.days)

        val unseen = add(BatteryHistory(), 3, 3000)
        val atLeast = BatteryLog.age(unseen, now, utc)!!
        assertFalse(atLeast.exact)
        assertEquals(7, atLeast.days)

        assertNull(BatteryLog.age(BatteryHistory(), now, utc))
        assertNull(BatteryLog.age(add(BatteryHistory(), 10, 3000), now, utc))
    }

    @Test fun gapsSplitTheChart() {
        var h = BatteryHistory()
        listOf(0L, 1, 2, 7, 8).forEach { h = add(h, it, 3000) }
        assertEquals(listOf(3, 2), BatteryLog.segments(h).map { it.size })
    }
}
