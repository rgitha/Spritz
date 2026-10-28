package io.github.rgitha.spritz.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BlePermissions {
    /** Runtime permissions needed to scan for and talk to devices. */
    val required: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context, permissions: Array<String> = required) = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Only connecting is needed for background work; on Android 8–11 that has no runtime permission. */
    fun canConnect(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            granted(context, arrayOf(Manifest.permission.BLUETOOTH_CONNECT))

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
}
