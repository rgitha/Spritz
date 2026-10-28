package io.github.rgitha.spritz.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ProtocolTest {

    @Test fun statusFrameMatchesDeviceCapture() {
        assertEquals("bf626d54186b626d4e189dff", Messages.status().toHex())
    }

    @Test fun sprayOnceFrameMatchesDeviceCapture() {
        assertEquals("bf626d541868626d4e189a62724900ff", Messages.spray().toHex())
    }

    @Test fun setTimeUsesEightByteEpochAndInvertedOffset() {
        val frame = Messages.setTime(1_700_000_000_000, TimeZone.getTimeZone("Asia/Kolkata"))
        assertTrue(frame.toHex().contains("62655" + "01b000000006553f100"))
        val reply = Reply.parse(Cbor.decode(frame).first)!!
        assertEquals(-330L, reply.fields["tZ"])
        assertEquals(0L, reply.fields["tD"])
    }

    @Test fun setTimeIncludesDstSaving() {
        val reply = Reply.parse(Cbor.decode(Messages.setTime(0, TimeZone.getTimeZone("Europe/London"))).first)!!
        assertEquals(0L, reply.fields["tZ"])
        assertEquals(3_600_000L, reply.fields["tD"])
    }

    @Test fun decodesFragmentsOnlyWhenComplete() {
        val full = "bf626d54189d6361636b18c8ff".hexToBytes()
        for (cut in 1 until full.size) {
            try {
                Cbor.decode(full.copyOf(cut))
                throw AssertionError("decoded a partial item at $cut")
            } catch (_: Cbor.Incomplete) {
            }
        }
        val reply = Reply.parse(Cbor.decode(full).first)!!
        assertEquals(157, reply.type)
        assertEquals(200L, reply.ack)
    }

    @Test fun recognisesRefusal() {
        val refusal = Reply(mapOf("mT" to 157L, "mN" to 162L, "ack" to 100L))
        assertTrue(refusal.isRefusalOf(162))
        assertFalse(refusal.isRefusalOf(154))
        assertFalse(Reply(mapOf("mT" to 157L, "ack" to 200L)).isRefusalOf(157))
    }

    @Test fun parsesStatus() {
        val m = Cbor.encode(
            Cbor.IndefiniteMap(
                listOf(
                    "mT" to 157, "bV" to 2950, "fV" to "3.0.8-IN", "cS" to 122,
                    "sC" to Cbor.IndefiniteMap(
                        listOf("sn" to listOf(5, 1, 2, 3), "sc" to listOf(4, 0, 0), "bn" to listOf(0, 1, 0))
                    ),
                )
            )
        )
        val s = DeviceStatus.from(Reply.parse(Cbor.decode(m).first)!!)
        assertEquals(2950, s.batteryMv)
        assertEquals(93, s.batteryPercent)
        assertEquals("3.0.8-IN", s.firmware)
        assertEquals(Mode(ModeSource.SCHEDULE, 20), s.mode)
        assertEquals(16L, s.totalSprays)
    }

    @Test fun modeCodes() {
        assertTrue(Mode.fromCode(0).isIdle)
        assertTrue(Mode.fromCode(111).isIdle)
        assertEquals(Mode(ModeSource.APP, 40), Mode.fromCode(114))
        assertEquals(Mode(ModeSource.BUTTON, 10), Mode.fromCode(131))
    }

    @Test fun batteryCurve() {
        assertEquals(100, Battery.percent(3200))
        assertEquals(100, Battery.percent(3100))
        assertEquals(57, Battery.percent(2600))
        assertEquals(43, Battery.percent(2550))
        assertEquals(0, Battery.percent(2400))
        assertEquals(0, Battery.percent(2300))
    }

    @Test fun refill() {
        assertEquals(100, Refill.percent(0))
        assertEquals(50, Refill.percent(1100))
        assertEquals(0, Refill.percent(5000))
        assertEquals(100, Refill.percent(3000, baseline = 3000))
    }

    @Test fun scheduleBytesRoundTrip() {
        val s = Schedule(dayMask = 64 or 4, hour = 7, minute = 30, sprays = 6, interval = 20)
        assertArrayEquals(byteArrayOf(68, 7, 30, 6, 20), s.toBytes())
        assertEquals(s, Schedule.fromBytes(s.toBytes()))
        assertEquals(9 * 60 + 30, s.endMinute)
    }

    @Test fun setSchedulesFrameRoundTrips() {
        val list = listOf(Schedule(127, 8, 0, 3, 10), Schedule(1, 20, 0, 2, 40))
        val reply = Reply.parse(Cbor.decode(Messages.setSchedules(list)).first)!!
        assertEquals(103, reply.type)
        assertEquals(list, Schedule.listFrom(reply))
    }

    @Test fun scheduleValidation() {
        assertTrue(Schedule(64, 23, 0, 7, 10).problems().isNotEmpty()) // past midnight
        assertTrue(Schedule(64, 23, 0, 6, 10).problems().isEmpty()) // ends at 24:00
        assertTrue(Schedule(0, 8, 0, 1, 10).problems().isNotEmpty())
        val a = Schedule(64, 8, 0, 3, 20) // 8:00–9:00 Mon
        assertTrue(a.overlaps(Schedule(64 or 32, 8, 50, 1, 10)))
        assertFalse(a.overlaps(Schedule(64, 9, 0, 1, 10)))
        assertFalse(a.overlaps(Schedule(32, 8, 0, 3, 20)))
    }
}
