package io.github.rgitha.spritz.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Hears saved devices' advertisements while the app is in the foreground: one
 * long-running, low-power scan filtered to their IDs. Android allows only 5 scan
 * starts in 30 s, so the scan restarts only when the list changes, Bluetooth comes
 * back, or it nears the 30 minutes after which Android quietly downgrades it.
 */
object Presence {
    /** Not heard for this long while scanning: not advertising. */
    const val SILENT_MS = 195_000L

    private const val RESTART_MS = 25 * 60_000L

    private val _lastSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** When each device was last heard, or last answered: it doesn't advertise while connected. */
    val lastSeen: StateFlow<Map<String, Long>> = _lastSeen

    private val _scanSince = MutableStateFlow<Long?>(null)
    /** When the current scan started; null when not scanning. */
    val scanSince: StateFlow<Long?> = _scanSince

    private val _bluetoothOn = MutableStateFlow(true)
    /** Whether Bluetooth is on, as last seen in the foreground. */
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn

    private val _permitted = MutableStateFlow(true)
    /** Whether the app may use Bluetooth, as last checked in the foreground. */
    val permitted: StateFlow<Boolean> = _permitted

    /** Checks the permission again, as after asking for it. */
    fun recheck(context: Context) {
        _permitted.value = BlePermissions.granted(context)
    }

    private val _advertisements = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** The ID of each device heard. */
    val advertisements: SharedFlow<String> = _advertisements

    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var scanning: Set<String> = emptySet()
    private var startedAt = 0L

    fun markHeard(address: String) {
        _lastSeen.update { it + (address to System.currentTimeMillis()) }
    }

    /** Whether [address] is advertising; null when that can't be told, as when not scanning. */
    fun advertising(address: String, lastSeen: Map<String, Long>, scanSince: Long?, now: Long): Boolean? {
        if (scanSince == null) return null
        val seen = lastSeen[address]
        if (seen != null && now - seen < SILENT_MS) return true
        return if (now - scanSince >= SILENT_MS) false else null
    }

    /**
     * Scans for [devices] until cancelled, following the list and Bluetooth being
     * switched on and off. Run it while the app is in the foreground.
     */
    suspend fun run(context: Context, devices: Flow<Set<String>>) = coroutineScope {
        val adapter = BlePermissions.adapter(context)
        _bluetoothOn.value = adapter?.isEnabled == true
        // Checked on each return to the foreground, so a grant in Settings shows at once.
        recheck(context)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                _bluetoothOn.value = state == BluetoothAdapter.STATE_ON
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // A tick each minute, so a long scan gets renewed before Android downgrades it.
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(60_000)
            }
        }
        try {
            launch {
                combine(devices, _bluetoothOn, _permitted, ticks) { list, on, allowed, _ ->
                    Triple(list, on, allowed)
                }.collect { (list, on, allowed) ->
                    if (on && allowed) start(context, list) else stop()
                }
            }.join()
        } finally {
            context.unregisterReceiver(receiver)
            stop()
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun start(context: Context, addresses: Set<String>) {
        val now = System.currentTimeMillis()
        if (callback != null && addresses == scanning && now - startedAt < RESTART_MS) return
        stop()
        if (addresses.isEmpty() || !BlePermissions.granted(context)) return
        val s = BlePermissions.adapter(context)?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                markHeard(address)
                _advertisements.tryEmit(address)
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                BleLog.add(null, "✗ Couldn't listen for devices (code $errorCode)")
                synchronized(this@Presence) {
                    callback = null
                    scanning = emptySet()
                    _scanSince.value = null
                }
            }
        }
        val filters = addresses.map { ScanFilter.Builder().setDeviceAddress(it).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        runCatching { s.startScan(filters, settings, cb) }.onSuccess {
            scanner = s
            callback = cb
            scanning = addresses
            startedAt = now
            _scanSince.value = now
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun stop() {
        callback?.let { cb -> runCatching { scanner?.stopScan(cb) } }
        scanner = null
        callback = null
        scanning = emptySet()
        _scanSince.value = null
    }
}
