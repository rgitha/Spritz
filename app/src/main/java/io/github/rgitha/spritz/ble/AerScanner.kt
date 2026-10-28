package io.github.rgitha.spritz.ble

import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundDevice(val address: String, val name: String, val rssi: Int)

class ScanFailed(val code: Int) : Exception("Bluetooth scan failed (code $code)")

object AerScanner {
    /**
     * Scans until the flow is cancelled, emitting the aer devices seen so far.
     * The name can't be filtered by substring in a ScanFilter, so results are filtered here.
     */
    @SuppressLint("MissingPermission")
    fun scan(context: Context): Flow<List<FoundDevice>> = callbackFlow {
        val scanner = BlePermissions.adapter(context)?.bluetoothLeScanner
            ?: throw IllegalStateException("Bluetooth is off")
        val found = LinkedHashMap<String, FoundDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (!AerUuids.isAerName(name)) return
                found[result.device.address] = FoundDevice(result.device.address, name!!, result.rssi)
                trySend(found.values.toList())
            }

            override fun onScanFailed(errorCode: Int) {
                close(ScanFailed(errorCode))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
