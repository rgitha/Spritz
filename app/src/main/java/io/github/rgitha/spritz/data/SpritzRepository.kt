package io.github.rgitha.spritz.data

import android.content.Context
import io.github.rgitha.spritz.ble.AerClient
import io.github.rgitha.spritz.ble.AerClients
import io.github.rgitha.spritz.notify.Notifier
import io.github.rgitha.spritz.protocol.DeviceStatus
import io.github.rgitha.spritz.protocol.Schedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place that talks to devices: every status read goes through here, so the
 * cache, the notifications and the widget stay in step with what the device said.
 */
class SpritzRepository private constructor(private val context: Context) {
    val store = DeviceStore.get(context)

    private fun client(address: String): AerClient = AerClients.get(context, address)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Status reads in progress, so callers asking for the same device share one. */
    private val reads = HashMap<String, Deferred<DeviceStatus>>()

    /** Reads taken before the device was saved (while it was being set up), with when. */
    private val unsaved = ConcurrentHashMap<String, Pair<DeviceStatus, Long>>()

    private fun read(address: String): Deferred<DeviceStatus> = synchronized(reads) {
        reads[address]?.takeIf { it.isActive } ?: scope.async {
            try {
                val status = client(address).readStatus()
                if (store.devicesNow().none { it.address == address }) {
                    unsaved[address] = status to System.currentTimeMillis()
                    status
                } else {
                    record(address, status)
                }
            } finally {
                synchronized(reads) { reads.remove(address) }
            }
        }.also { reads[address] = it }
    }

    /** Reads the status, joining a read of the same device that's already running. */
    suspend fun refresh(address: String): DeviceStatus =
        // Recording again is harmless, and covers a read that began before the device was saved.
        record(address, read(address).await())

    /** Starts reading a device that's being set up; [adopt] keeps the result once it's saved. */
    fun prefetch(address: String) {
        unsaved.remove(address)
        read(address)
    }

    /** Saves a status read during setup, if it finished before the device was added. */
    suspend fun adopt(address: String) {
        val (status, at) = unsaved.remove(address) ?: return
        if (System.currentTimeMillis() - at > ADOPT_MS) return
        record(address, status)
        store.update(address) { it.copy(statusAt = at) }
    }

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
        store.updateSettings {
            it.copy(
                widgetDevice = it.widgetDevice.takeIf { a -> a != address },
                lastDevice = it.lastDevice.takeIf { a -> a != address },
            )
        }
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
        /** A setup read older than this isn't worth keeping. */
        private const val ADOPT_MS = 60_000L

        @Volatile private var instance: SpritzRepository? = null

        fun get(context: Context): SpritzRepository = instance ?: synchronized(this) {
            instance ?: SpritzRepository(context.applicationContext).also { instance = it }
        }
    }
}
