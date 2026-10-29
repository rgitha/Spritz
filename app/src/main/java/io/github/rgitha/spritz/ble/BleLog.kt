package io.github.rgitha.spritz.ble

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One log line; [address] is the device it concerns, if any. */
data class LogLine(val time: String, val address: String?, val text: String)

/** Recent BLE events, viewable in the app to debug a device without adb. */
object BleLog {
    private const val MAX_LINES = 400
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    @Synchronized
    fun add(address: String?, message: String) {
        Log.d("AerClient", if (address == null) message else "$address $message")
        _lines.value = (_lines.value + LogLine(time.format(Date()), address, message)).takeLast(MAX_LINES)
    }

    @Synchronized
    fun clear(address: String? = null) {
        _lines.value = if (address == null) emptyList() else _lines.value.filter { it.address != address }
    }
}
