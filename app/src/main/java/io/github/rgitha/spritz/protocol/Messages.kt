package io.github.rgitha.spritz.protocol

import java.util.TimeZone

/** Message types. A reply's type is the request's + 50. */
object MsgType {
    const val SET_TIME = 102
    const val SET_SCHEDULES = 103
    const val SPRAY = 104
    const val REFILL_RESET = 105
    const val STOP = 106
    const val STATUS = 107
    const val REMOVE_SCHEDULES = 108
    const val READ_SCHEDULES = 109

    const val STATUS_REPLY = STATUS + 50

    const val ACK_OK = 200L
    const val ACK_REFUSED = 100L

    fun replyTo(request: Int) = request + 50
}

/** Auto-mode intervals the device supports, in minutes. */
val INTERVALS = listOf(10, 20, 40)

/** Stop targets for [Messages.stop]. */
object StopTarget {
    const val CURRENT_RUN = 128
    const val ALL_RUNS = 255
}

/** Builds request frames. Every request is an indefinite-length map with mT and mN. */
object Messages {

    private fun frame(type: Int, vararg fields: Pair<String, Any>): ByteArray =
        Cbor.encode(
            Cbor.IndefiniteMap(
                listOf("mT" to type, "mN" to MsgType.replyTo(type)) + fields
            )
        )

    fun setTime(epochMillis: Long, zone: TimeZone): ByteArray = frame(
        MsgType.SET_TIME,
        "eP" to Cbor.U64(epochMillis / 1000),
        "tZ" to -(zone.rawOffset / 60_000),
        "tD" to zone.dstSavings,
    )

    /** [interval] 0 sprays once; 10, 20 or 40 starts auto mode at that interval. */
    fun spray(interval: Int = 0): ByteArray {
        require(interval == 0 || interval in INTERVALS) { "bad interval $interval" }
        return frame(MsgType.SPRAY, "rI" to interval)
    }

    fun refillReset(): ByteArray = frame(MsgType.REFILL_RESET, "rR" to true)

    fun stopAuto(): ByteArray = frame(MsgType.STOP, "sS" to true)

    /** Stops schedule run [run] (0–9), or [StopTarget.CURRENT_RUN] / [StopTarget.ALL_RUNS]. */
    fun stopRun(run: Int): ByteArray {
        require(run in 0..9 || run == StopTarget.CURRENT_RUN || run == StopTarget.ALL_RUNS)
        return frame(MsgType.STOP, "sS" to run)
    }

    fun status(): ByteArray = frame(MsgType.STATUS)

    fun removeSchedules(): ByteArray = frame(MsgType.REMOVE_SCHEDULES)

    fun readSchedules(): ByteArray = frame(MsgType.READ_SCHEDULES)

    fun setSchedules(schedules: List<Schedule>): ByteArray = frame(
        MsgType.SET_SCHEDULES,
        "S" to Cbor.IndefiniteArray(
            schedules.map { Cbor.IndefiniteMap(listOf("s" to it.toBytes())) }
        ),
    )
}

/** A decoded reply. */
class Reply(val fields: Map<*, *>) {
    val type: Int? = (fields["mT"] as? Long)?.toInt()
    val mN: Int? = (fields["mN"] as? Long)?.toInt()
    val ack: Long? = fields["ack"] as? Long

    /** True if this is the device refusing a request whose expected reply type is [expected]. */
    fun isRefusalOf(expected: Int) =
        type == MsgType.STATUS_REPLY && expected != MsgType.STATUS_REPLY &&
            mN == expected && ack == MsgType.ACK_REFUSED

    companion object {
        fun parse(item: Any?): Reply? = (item as? Map<*, *>)?.let { Reply(it) }
    }
}
