package io.github.rgitha.spritz.data

import android.content.Context
import io.github.rgitha.spritz.ble.AerClient
import io.github.rgitha.spritz.ble.AerException
import io.github.rgitha.spritz.ble.AerClients
import io.github.rgitha.spritz.ble.Presence
import io.github.rgitha.spritz.ble.RefusedException
import io.github.rgitha.spritz.notify.Notifier
import io.github.rgitha.spritz.protocol.DeviceStatus
import io.github.rgitha.spritz.protocol.ModeSource
import io.github.rgitha.spritz.protocol.Schedule
import io.github.rgitha.spritz.widget.SprayWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
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
                val status = exchange(address) { it.readStatus() }
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

    suspend fun spray(address: String, interval: Int = 0) = record(address, exchange(address) { it.spray(interval) })

    suspend fun stopAuto(address: String) = record(address, exchange(address) { it.stopAuto() })

    suspend fun stopRun(address: String, run: Int) = record(address, exchange(address) { it.stopRun(run) })

    suspend fun refillReset(address: String): DeviceStatus {
        val status = exchange(address) { it.refillReset() }
        // Whether 105 zeroes the counters isn't known yet, so remember where they stood.
        store.update(address) { it.copy(refillBaseline = status.totalSprays, lowRefillNotified = false) }
        return record(address, status)
    }

    suspend fun readSchedules(address: String): List<Schedule> =
        exchange(address) { it.readSchedules() }.also { cacheSchedules(address, it) }

    suspend fun writeSchedules(address: String, list: List<Schedule>) {
        exchange(address) { it.writeSchedules(list) }
        cacheSchedules(address, list)
    }

    private suspend fun cacheSchedules(address: String, list: List<Schedule>) =
        store.update(address) { it.copy(schedules = list, schedulesAt = System.currentTimeMillis()) }

    /** The schedule the device is running now, from the saved copy of its list. */
    fun runningSchedule(d: SavedDevice): Schedule? {
        val mode = d.status?.mode ?: return null
        if (mode.source != ModeSource.SCHEDULE) return null
        val now = LocalDateTime.now()
        return Schedule.running(d.schedules.orEmpty(), now.dayOfWeek, now.hour * 60 + now.minute, mode.interval)
    }

    /** Adds a device and opens it; widgets still tied to it from before pick it up again. */
    suspend fun addDevice(address: String, name: String, room: String?) {
        store.add(address, name, room)
        store.updateSettings { it.copy(lastDevice = address) }
        scope.launch { SprayWidget.refresh(context) }
    }

    /** Renames or moves a device in the background, so leaving a screen can't cut it short. */
    fun editDevice(address: String, name: String, room: String?) {
        scope.launch {
            store.edit(address, name, room)
            // Widgets show the room and name.
            SprayWidget.refresh(context)
        }
    }

    /**
     * Removes a device, then calls [onRemoved] on the main thread. Runs here rather than
     * in the screen's scope: the screen leaves as soon as the device does, which would
     * cancel the rest.
     */
    fun removeDevice(address: String, onRemoved: () -> Unit) {
        scope.launch {
            AerClients.remove(address)
            store.remove(address)
            Notifier.cancelAll(context, address)
            store.updateSettings {
                it.copy(
                    widgetDevice = it.widgetDevice.takeIf { a -> a != address },
                    lastDevice = it.lastDevice.takeIf { a -> a != address },
                )
            }
            withContext(Dispatchers.Main) { onRemoved() }
            // Its widgets now say it was removed.
            SprayWidget.refresh(context)
        }
    }

    /**
     * Runs [block] against the device and remembers whether it got its reply, for the
     * connection status. A refusal is still a reply. Bluetooth being off or a missing
     * permission is the phone's doing, so it leaves the record as it was.
     */
    private suspend fun <T> exchange(address: String, block: suspend (AerClient) -> T): T {
        val result = try {
            block(client(address))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RefusedException) {
            noteExchange(address, true)
            throw e
        } catch (e: Exception) {
            val phoneSide = e is SecurityException ||
                (e as? AerException)?.kind in setOf(AerException.Kind.BLUETOOTH_OFF, AerException.Kind.NO_PERMISSION)
            if (!phoneSide) noteExchange(address, false)
            throw e
        }
        noteExchange(address, true)
        return result
    }

    private suspend fun noteExchange(address: String, ok: Boolean) {
        // It doesn't advertise while connected, so an answer counts as being heard.
        if (ok) Presence.markHeard(address)
        store.update(address) { it.copy(exchangeOk = ok, exchangeAt = System.currentTimeMillis()) }
    }

    /**
     * Called for each advertisement heard: reads the status at once if the last
     * exchange failed or the status is older than the background check interval, so
     * a device that comes back shows as connected by itself. At most once a minute.
     */
    fun onHeard(address: String) {
        scope.launch {
            val d = store.devicesNow().find { it.address == address } ?: return@launch
            val now = System.currentTimeMillis()
            val pollMs = (store.settingsNow().pollMinutes.takeIf { it > 0 } ?: 60) * 60_000L
            if (d.exchangeOk != false && now - d.statusAt < pollMs) return@launch
            if (now - (recoveredAt[address] ?: 0) < RECOVER_GAP_MS) return@launch
            recoveredAt[address] = now
            runCatching { refresh(address) }
        }
    }

    private val recoveredAt = ConcurrentHashMap<String, Long>()

    private suspend fun record(address: String, status: DeviceStatus): DeviceStatus {
        val before = store.devicesNow().find { it.address == address } ?: return status
        val now = System.currentTimeMillis()
        var after = before.copy(status = status, statusAt = now)
        status.batteryMv?.let { after = after.copy(battery = BatteryLog.add(after.battery, now, it)) }
        // Counters went backwards (e.g. reset by the device): the old baseline no longer applies.
        if (status.totalSprays < after.refillBaseline) after = after.copy(refillBaseline = 0)
        // A schedule is running that the saved list doesn't explain: read the list once.
        if (status.mode.source == ModeSource.SCHEDULE && runningSchedule(after) == null &&
            now - after.schedulesAt > SCHEDULES_RECHECK_MS
        ) {
            runCatching { client(address).readSchedules() }.onSuccess {
                after = after.copy(schedules = it, schedulesAt = System.currentTimeMillis())
            }
        }
        after = Notifier.check(context, store.settingsNow(), before, after, runningSchedule(after))
        store.update(address) {
            it.copy(
                status = after.status, statusAt = after.statusAt, refillBaseline = after.refillBaseline,
                lowRefillNotified = after.lowRefillNotified, lowBatteryNotified = after.lowBatteryNotified,
                schedules = after.schedules, schedulesAt = after.schedulesAt, battery = after.battery,
            )
        }
        return status
    }

    companion object {
        /** How soon to read the schedule list again when it doesn't explain a running schedule. */
        private const val SCHEDULES_RECHECK_MS = 10 * 60_000L

        /** The least time between automatic reads for one device. */
        private const val RECOVER_GAP_MS = 60_000L

        /** A setup read older than this isn't worth keeping. */
        private const val ADOPT_MS = 60_000L

        @Volatile private var instance: SpritzRepository? = null

        fun get(context: Context): SpritzRepository = instance ?: synchronized(this) {
            instance ?: SpritzRepository(context.applicationContext).also { instance = it }
        }
    }
}
