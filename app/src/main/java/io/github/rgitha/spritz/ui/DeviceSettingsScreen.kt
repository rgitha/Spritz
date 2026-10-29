package io.github.rgitha.spritz.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.rgitha.spritz.data.SavedDevice
import io.github.rgitha.spritz.data.SpritzRepository
import io.github.rgitha.spritz.widget.PinnedWidgetReceiver
import io.github.rgitha.spritz.widget.SprayWidgetReceiver
import kotlinx.coroutines.launch

/**
 * One device's name and room, widget, info, connection log and removal. Name and
 * room are saved on leaving the screen, as in Android's own settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingsScreen(
    address: String,
    onBack: () -> Unit,
    onLog: () -> Unit,
    onRemoved: (Removed) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { SpritzRepository.get(context) }
    val devices by repo.store.devices.collectAsState(initial = null)
    val list = devices ?: return
    val d = list.find { it.address == address } ?: return
    val others = list.filter { it.address != address }

    var name by remember { mutableStateOf(d.name) }
    var room by remember { mutableStateOf(d.room) }
    var removing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Save on the way out; an empty name keeps the old one.
    val latest by rememberUpdatedState(Triple(name, room, d))
    DisposableEffect(Unit) {
        onDispose {
            val (n, r, dev) = latest
            val clean = cleanName(n)
            if (!removing && clean.isNotEmpty() && (clean != dev.name || r != dev.room)) {
                repo.editDevice(address, clean, r)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Device settings") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Section("Name and room")
            NameRoomFields(name, { name = it }, room, { room = it }, others, initialRoom = d.room)
            HorizontalDivider(Modifier.padding(top = 20.dp))
            Section("Widget")
            OutlinedButton(onClick = { pinWidget(context, d) }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add Spray widget to home screen")
            }
            HorizontalDivider(Modifier.padding(top = 20.dp))
            Section("Device info")
            Info("ID", d.address)
            Info("Firmware", d.status?.firmware ?: "–")
            Info("Updated", ago(d.statusAt))
            ListItem(
                headlineContent = { Text("Connection log") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onLog),
            )
            HorizontalDivider()
            TextButton(onClick = { removing = true }, modifier = Modifier.padding(vertical = 12.dp)) {
                Text("Remove device", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${d.displayName}?") },
            text = { Text("ID ${d.address}. Schedules stay on the device and keep running. You can add it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.removeDevice(address)
                        onRemoved(Removed(d.displayName, d.address))
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
    )
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.Start) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Asks the launcher to place a Spray widget already tied to [d]. */
private fun pinWidget(context: Context, d: SavedDevice) {
    val manager = AppWidgetManager.getInstance(context)
    if (!manager.isRequestPinAppWidgetSupported) {
        Toast.makeText(
            context,
            "Your home screen can't add widgets from apps. Add Spray now from its widget list.",
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    val callback = PendingIntent.getBroadcast(
        context,
        d.address.hashCode(),
        Intent(context, PinnedWidgetReceiver::class.java).putExtra(PinnedWidgetReceiver.EXTRA_ADDRESS, d.address),
        // Mutable so the launcher can add the new widget's id.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
    manager.requestPinAppWidget(ComponentName(context, SprayWidgetReceiver::class.java), null, callback)
}
