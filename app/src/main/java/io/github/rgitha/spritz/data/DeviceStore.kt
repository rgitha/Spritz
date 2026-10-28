package io.github.rgitha.spritz.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.rgitha.spritz.protocol.DeviceStatus
import io.github.rgitha.spritz.protocol.Refill
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** A device the user added, with what we last learned about it. */
data class SavedDevice(
    val address: String,
    /** The base name the user typed; see [displayName]. */
    val name: String,
    /** Number added to a name that's already taken; see [Naming]. */
    val suffix: Int? = null,
    val room: String? = null,
    val status: DeviceStatus? = null,
    /** When [status] was read, in epoch millis. */
    val statusAt: Long = 0,
    /** Counter total at the last refill reset. */
    val refillBaseline: Long = 0,
    val lowRefillNotified: Boolean = false,
    val lowBatteryNotified: Boolean = false,
) {
    val refillPercent: Int? get() = status?.let { Refill.percent(it.totalSprays, refillBaseline) }

    /** The unique name, with its suffix. */
    val displayName: String get() = Naming.display(name, suffix)

    /** The room and name, for notifications and the widget. */
    val label: String get() = room?.let { "$it · $displayName" } ?: displayName
}

data class Settings(
    /** Background status check interval in minutes; 0 turns it off. */
    val pollMinutes: Int = 60,
    val notifyLowRefill: Boolean = true,
    val notifyLowBattery: Boolean = true,
    val notifyScheduleStart: Boolean = true,
    /** The device the widget sprays; null means the first one. */
    val widgetDevice: String? = null,
    /** The device shown when the app opens. */
    val lastDevice: String? = null,
)

/** The widget's last result, shown under its button. */
data class WidgetState(val text: String = "", val at: Long = 0, val busy: Boolean = false)

private val Context.store: DataStore<Preferences> by preferencesDataStore("spritz")

class DeviceStore(context: Context) {
    private val ds = context.applicationContext.store

    val devices: Flow<List<SavedDevice>> = ds.data.map { decodeDevices(it[DEVICES]) }

    val settings: Flow<Settings> = ds.data.map {
        Settings(
            pollMinutes = it[POLL] ?: 60,
            notifyLowRefill = it[N_REFILL] ?: true,
            notifyLowBattery = it[N_BATTERY] ?: true,
            notifyScheduleStart = it[N_SCHEDULE] ?: true,
            widgetDevice = it[WIDGET_DEVICE],
            lastDevice = it[LAST_DEVICE],
        )
    }

    val widgetState: Flow<WidgetState> = ds.data.map {
        WidgetState(it[W_TEXT] ?: "", it[W_AT]?.toLongOrNull() ?: 0, it[W_BUSY] ?: false)
    }

    fun device(address: String): Flow<SavedDevice?> = devices.map { l -> l.find { it.address == address } }

    suspend fun devicesNow() = devices.first()

    suspend fun settingsNow() = settings.first()

    /** The device the widget controls. */
    suspend fun widgetDevice(): SavedDevice? {
        val list = devicesNow()
        val chosen = settingsNow().widgetDevice
        return list.find { it.address == chosen } ?: list.firstOrNull()
    }

    suspend fun add(address: String, name: String, room: String?) = updateDevices { list ->
        if (list.any { it.address == address }) list
        else list + SavedDevice(address, name, Naming.suffixFor(name, list), room)
    }

    suspend fun remove(address: String) = updateDevices { list -> list.filter { it.address != address } }

    /** Renames and moves a device; its suffix is worked out again against the others. */
    suspend fun edit(address: String, name: String, room: String?) = updateDevices { list ->
        val others = list.filter { it.address != address }
        list.map {
            if (it.address != address) it
            else it.copy(name = name, suffix = Naming.suffixFor(name, others), room = room)
        }
    }

    /** Gives suffixes to names saved before they were made unique. Safe to repeat. */
    suspend fun dedupeNames() = updateDevices { Naming.dedupe(it) }

    suspend fun update(address: String, change: (SavedDevice) -> SavedDevice) = updateDevices { list ->
        list.map { if (it.address == address) change(it) else it }
    }

    suspend fun updateSettings(change: (Settings) -> Settings) {
        ds.edit {
            val s = change(settingsFrom(it))
            it[POLL] = s.pollMinutes
            it[N_REFILL] = s.notifyLowRefill
            it[N_BATTERY] = s.notifyLowBattery
            it[N_SCHEDULE] = s.notifyScheduleStart
            if (s.widgetDevice == null) it.remove(WIDGET_DEVICE) else it[WIDGET_DEVICE] = s.widgetDevice
            if (s.lastDevice == null) it.remove(LAST_DEVICE) else it[LAST_DEVICE] = s.lastDevice
        }
    }

    suspend fun setWidgetState(state: WidgetState) {
        ds.edit {
            it[W_TEXT] = state.text
            it[W_AT] = state.at.toString()
            it[W_BUSY] = state.busy
        }
    }

    private suspend fun updateDevices(change: (List<SavedDevice>) -> List<SavedDevice>) {
        ds.edit { it[DEVICES] = encodeDevices(change(decodeDevices(it[DEVICES]))) }
    }

    private fun settingsFrom(p: Preferences) = Settings(
        p[POLL] ?: 60, p[N_REFILL] ?: true, p[N_BATTERY] ?: true, p[N_SCHEDULE] ?: true, p[WIDGET_DEVICE],
        p[LAST_DEVICE],
    )

    companion object {
        private val DEVICES = stringPreferencesKey("devices")
        private val POLL = intPreferencesKey("poll_minutes")
        private val N_REFILL = booleanPreferencesKey("notify_refill")
        private val N_BATTERY = booleanPreferencesKey("notify_battery")
        private val N_SCHEDULE = booleanPreferencesKey("notify_schedule")
        private val WIDGET_DEVICE = stringPreferencesKey("widget_device")
        private val LAST_DEVICE = stringPreferencesKey("last_device")
        private val W_TEXT = stringPreferencesKey("widget_text")
        private val W_AT = stringPreferencesKey("widget_at")
        private val W_BUSY = booleanPreferencesKey("widget_busy")

        @Volatile private var instance: DeviceStore? = null

        fun get(context: Context): DeviceStore =
            instance ?: synchronized(this) { instance ?: DeviceStore(context).also { instance = it } }

        internal fun encodeDevices(list: List<SavedDevice>): String = JSONArray().apply {
            list.forEach { d ->
                put(JSONObject().apply {
                    put("address", d.address)
                    put("name", d.name)
                    d.suffix?.let { put("suffix", it) }
                    d.room?.let { put("room", it) }
                    put("statusAt", d.statusAt)
                    put("refillBaseline", d.refillBaseline)
                    put("lowRefillNotified", d.lowRefillNotified)
                    put("lowBatteryNotified", d.lowBatteryNotified)
                    d.status?.let { put("status", encodeStatus(it)) }
                })
            }
        }.toString()

        internal fun decodeDevices(json: String?): List<SavedDevice> {
            if (json.isNullOrEmpty()) return emptyList()
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SavedDevice(
                    address = o.getString("address"),
                    name = o.getString("name"),
                    suffix = if (o.has("suffix")) o.getInt("suffix") else null,
                    room = o.optString("room").ifEmpty { null },
                    status = o.optJSONObject("status")?.let(::decodeStatus),
                    statusAt = o.optLong("statusAt"),
                    refillBaseline = o.optLong("refillBaseline"),
                    lowRefillNotified = o.optBoolean("lowRefillNotified"),
                    lowBatteryNotified = o.optBoolean("lowBatteryNotified"),
                )
            }
        }

        private fun encodeStatus(s: DeviceStatus) = JSONObject().apply {
            s.batteryMv?.let { put("bV", it) }
            s.firmware?.let { put("fV", it) }
            s.structureVersion?.let { put("sV", it) }
            put("cS", s.modeCode)
            put("sn", JSONArray(s.appSprays))
            put("sc", JSONArray(s.scheduledSprays))
            put("bn", JSONArray(s.buttonSprays))
        }

        private fun decodeStatus(o: JSONObject): DeviceStatus {
            fun longs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty()
            return DeviceStatus(
                batteryMv = if (o.has("bV")) o.getInt("bV") else null,
                firmware = o.optString("fV").ifEmpty { null },
                structureVersion = o.optString("sV").ifEmpty { null },
                modeCode = o.optInt("cS"),
                appSprays = longs("sn"),
                scheduledSprays = longs("sc"),
                buttonSprays = longs("bn"),
            )
        }
    }
}
