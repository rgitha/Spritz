package io.github.rgitha.spritz.ui

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.rgitha.spritz.BuildConfig
import io.github.rgitha.spritz.ble.BleLog
import io.github.rgitha.spritz.data.DeviceStore

/**
 * Recent Bluetooth traffic. With an [address], only that device's lines; without,
 * every line, each starting with its device's name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(address: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val all by BleLog.lines.collectAsState()
    val devices by remember { DeviceStore.get(context).devices }.collectAsState(initial = emptyList())
    val names = devices.associate { it.address to it.label }
    val lines = all
        .filter { address == null || it.address == address }
        .map { line ->
            val who = if (address == null && line.address != null) "${names[line.address] ?: line.address} · " else ""
            "${line.time} $who${line.text}"
        }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection log") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = {
                        val device = address?.let { "Device ${names[it] ?: it} ($it)\n" } ?: ""
                        val text = "Spritz ${BuildConfig.VERSION_NAME}, Android ${android.os.Build.VERSION.RELEASE}, " +
                            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" + device + lines.joinToString("\n")
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, "Share log"))
                    }) { Icon(Icons.Default.Share, contentDescription = "Share") }
                    IconButton(onClick = { BleLog.clear(address) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                },
            )
        },
    ) { padding ->
        if (lines.isEmpty()) {
            Text(
                "Nothing yet. Go back and refresh a device, then return here.",
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        } else {
            LazyColumn(state = list, contentPadding = PaddingValues(12.dp), modifier = Modifier.padding(padding)) {
                items(lines) {
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
