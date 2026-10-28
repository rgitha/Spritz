package io.github.rgitha.spritz.ble

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recent BLE events, viewable in the app to debug a device without adb. */
object BleLog {
    private const val MAX_LINES = 400
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Synchronized
    fun add(message: String) {
        Log.d("AerClient", message)
        _lines.value = (_lines.value + "${time.format(Date())} $message").takeLast(MAX_LINES)
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }
}
