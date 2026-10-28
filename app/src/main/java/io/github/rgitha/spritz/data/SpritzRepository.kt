package io.github.rgitha.spritz.data

import android.content.Context
import io.github.rgitha.spritz.ble.AerClient
import io.github.rgitha.spritz.ble.AerClients
import io.github.rgitha.spritz.notify.Notifier
import io.github.rgitha.spritz.protocol.DeviceStatus
import io.github.rgitha.spritz.protocol.Schedule

/**
 * The one place that talks to devices: every status read goes through here, so the
 * cache, the notifications and the widget stay in step with what the device said.
 */
class SpritzRepository private constructor(private val context: Context) {
    val store = DeviceStore.get(context)

    private fun client(address: String): AerClient = AerClients.get(context, address)

    suspend fun refresh(address: String): DeviceStatus = record(address, client(address).readStatus())

    suspend fun spray(address: String, interval: Int = 0) = record(address, client(address).spray(interval))

    suspend fun stopAuto(address: String) = record(address, client(address).stopAuto())

    suspend fun stopRun(address: String, run: Int) = record(address, client(address).stopRun(run))

    suspend fun refillReset(address: String): DeviceStatus {
        val status = client(address).refillReset()
        // Whether 105 zeroes the counters isn't known yet, so remember where they stood.
        store.update(address) { it.copy(refillBaseline = status.totalSprays, lowRefillNotified = false) }
        return record(address, status)
    }

    suspend fun readSchedules(address: String): List<Schedule> = client(address).readSchedules()

    suspend fun writeSchedules(address: String, list: List<Schedule>) = client(address).writeSchedules(list)

    suspend fun removeDevice(address: String) {
        AerClients.remove(address)
        store.remove(address)
        store.updateSettings { if (it.widgetDevice == address) it.copy(widgetDevice = null) else it }
    }

    private suspend fun record(address: String, status: DeviceStatus): DeviceStatus {
        val before = store.devicesNow().find { it.address == address } ?: return status
        var after = before.copy(status = status, statusAt = System.currentTimeMillis())
        // Counters went backwards (e.g. reset by the device): the old baseline no longer applies.
        if (status.totalSprays < after.refillBaseline) after = after.copy(refillBaseline = 0)
        after = Notifier.check(context, store.settingsNow(), before, after)
        store.update(address) { after }
        return status
    }

    companion object {
        @Volatile private var instance: SpritzRepository? = null

        fun get(context: Context): SpritzRepository = instance ?: synchronized(this) {
            instance ?: SpritzRepository(context.applicationContext).also { instance = it }
        }
    }
}
