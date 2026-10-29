package io.github.rgitha.spritz.protocol

/** Where the current mode came from. */
enum class ModeSource { APP, SCHEDULE, BUTTON }

/** The device's current mode; [source] and [interval] are null when idle. */
data class Mode(val source: ModeSource?, val interval: Int?) {
    val isIdle get() = source == null

    /** Idle, Auto or Schedule; auto mode from the device button counts as Auto. */
    val shortLabel: String
        get() = when (source) {
            null -> "Idle"
            ModeSource.SCHEDULE -> "Schedule"
            else -> "Auto"
        }

    companion object {
        val IDLE = Mode(null, null)

        fun fromCode(code: Int): Mode = when (code) {
            112 -> Mode(ModeSource.APP, 10)
            113 -> Mode(ModeSource.APP, 20)
            114 -> Mode(ModeSource.APP, 40)
            121 -> Mode(ModeSource.SCHEDULE, 10)
            122 -> Mode(ModeSource.SCHEDULE, 20)
            123 -> Mode(ModeSource.SCHEDULE, 40)
            131 -> Mode(ModeSource.BUTTON, 10)
            132 -> Mode(ModeSource.BUTTON, 20)
            133 -> Mode(ModeSource.BUTTON, 40)
            in 120..129 -> Mode(ModeSource.SCHEDULE, null)
            in 130..139 -> Mode(ModeSource.BUTTON, null)
            in 112..119 -> Mode(ModeSource.APP, null)
            else -> IDLE // 0 and 111
        }
    }
}

/** A decoded status reply (157). */
data class DeviceStatus(
    val batteryMv: Int?,
    val firmware: String?,
    val structureVersion: String?,
    val modeCode: Int,
    /** sn: now, auto 10, auto 20, auto 40. */
    val appSprays: List<Long>,
    /** sc: scheduled 10, 20, 40. */
    val scheduledSprays: List<Long>,
    /** bn: button 10, 20, 40. */
    val buttonSprays: List<Long>,
) {
    val mode: Mode get() = Mode.fromCode(modeCode)

    val totalSprays: Long get() = appSprays.sum() + scheduledSprays.sum() + buttonSprays.sum()

    val batteryPercent: Int? get() = batteryMv?.let { Battery.percent(it) }

    val batteryCritical: Boolean get() = batteryMv != null && batteryMv < Battery.MOTOR_STOP_MV

    companion object {
        fun from(reply: Reply): DeviceStatus {
            val f = reply.fields
            val sc = f["sC"] as? Map<*, *> ?: emptyMap<Any, Any>()
            fun longs(key: String) = (sc[key] as? List<*>).orEmpty().map { (it as? Long) ?: 0L }
            return DeviceStatus(
                batteryMv = (f["bV"] as? Long)?.toInt(),
                firmware = f["fV"]?.let(::text),
                structureVersion = f["sV"]?.let(::text),
                modeCode = (f["cS"] as? Long)?.toInt() ?: 0,
                appSprays = longs("sn"),
                scheduledSprays = longs("sc"),
                buttonSprays = longs("bn"),
            )
        }

        private fun text(v: Any): String = when (v) {
            is ByteArray -> String(v, Charsets.UTF_8)
            else -> v.toString()
        }
    }
}

object Battery {
    const val MOTOR_STOP_MV = 2400

    private val CURVE = listOf(
        3100 to 100, 3000 to 95, 2900 to 91, 2800 to 86, 2700 to 78,
        2600 to 57, 2500 to 29, 2450 to 14, 2400 to 0,
    )

    /** Maps the voltage of 2 AA cells to a percentage along the measured curve. */
    fun percent(mv: Int): Int {
        if (mv >= CURVE.first().first) return 100
        if (mv <= CURVE.last().first) return 0
        for (i in 0 until CURVE.size - 1) {
            val (hiV, hiP) = CURVE[i]
            val (loV, loP) = CURVE[i + 1]
            if (mv in loV..hiV) {
                return Math.round(loP + (hiP - loP) * (mv - loV).toDouble() / (hiV - loV)).toInt()
            }
        }
        return 0
    }
}

object Refill {
    const val CARTRIDGE_SPRAYS = 2200

    /**
     * Refill left, as a percentage. [baseline] is the counter total at the last refill
     * reset, in case the device doesn't zero its counters on reset.
     */
    fun percent(totalSprays: Long, baseline: Long = 0): Int {
        val used = (totalSprays - baseline).coerceAtLeast(0)
        val left = (CARTRIDGE_SPRAYS - used).coerceAtLeast(0)
        return Math.round(left * 100.0 / CARTRIDGE_SPRAYS).toInt()
    }
}
