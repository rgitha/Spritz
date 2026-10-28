package io.github.rgitha.spritz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.rgitha.spritz.R
import io.github.rgitha.spritz.data.SavedDevice
import io.github.rgitha.spritz.data.SpritzRepository
import io.github.rgitha.spritz.notify.Notifier
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(onOpen: (String) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SpritzRepository.get(context) }
    val devices by repo.store.devices.collectAsState(initial = null)
    val spraying = remember { mutableStateListOf<String>() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_label)) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add device") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = devices
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(Modifier.padding(padding))
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = gap,
                modifier = Modifier.padding(padding),
            ) {
                items(list, key = { it.address }) { d ->
                    DeviceCard(
                        device = d,
                        spraying = d.address in spraying,
                        onOpen = { onOpen(d.address) },
                        onSpray = {
                            spraying += d.address
                            scope.launch {
                                runCatching { repo.spray(d.address) }
                                    .onSuccess { snackbar.showSnackbar("Sprayed ${d.name}") }
                                    .onFailure { snackbar.showSnackbar(it.userMessage()) }
                                spraying -= d.address
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: SavedDevice, spraying: Boolean, onOpen: () -> Unit, onSpray: () -> Unit) {
    val s = device.status
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                val line = if (s == null) {
                    "Tap to connect"
                } else {
                    val battery = s.batteryPercent?.let { "Battery $it%" } ?: "Battery –"
                    "$battery · Refill ${device.refillPercent}% · ${s.mode.label()}"
                }
                val warn = s != null && (
                    (device.refillPercent ?: 100) <= Notifier.LOW_REFILL_PERCENT ||
                        (s.batteryPercent ?: 100) <= Notifier.LOW_BATTERY_PERCENT
                    )
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onSpray, enabled = !spraying) {
                if (spraying) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Spray")
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.app_full_name), style = MaterialTheme.typography.titleLarge)
            Text(
                "Add your Godrej aer Smart Matic to spray, set auto mode and schedules, and get refill and battery alerts.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
