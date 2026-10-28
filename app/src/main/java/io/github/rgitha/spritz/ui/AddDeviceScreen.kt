package io.github.rgitha.spritz.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.rgitha.spritz.ble.AerScanner
import io.github.rgitha.spritz.ble.BlePermissions
import io.github.rgitha.spritz.ble.FoundDevice
import io.github.rgitha.spritz.data.DeviceStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(onBack: () -> Unit, onAdded: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { DeviceStore.get(context) }
    val scope = rememberCoroutineScope()

    // Re-checked on resume, so returning from system settings updates the screen.
    var recheck by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) recheck++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val hasPermission = remember(recheck) { BlePermissions.granted(context) }
    val btOn = remember(recheck) { BlePermissions.adapter(context)?.isEnabled == true }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recheck++
    }
    val askEnable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        recheck++
    }

    var found by remember { mutableStateOf<List<FoundDevice>>(emptyList()) }
    var scanError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hasPermission, btOn) {
        if (!hasPermission || !btOn) return@LaunchedEffect
        scanError = null
        AerScanner.scan(context)
            .catch { scanError = it.message ?: "Scan failed" }
            .collect { found = it }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Add device") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = gap) {
            when {
                !hasPermission -> Prompt(
                    "Spritz needs Bluetooth permission to find your aer device." +
                        if (android.os.Build.VERSION.SDK_INT < 31) " Android asks for location for this; Spritz doesn't use it." else "",
                    "Allow",
                ) { askPermission.launch(BlePermissions.required) }

                !btOn -> Prompt("Bluetooth is off.", "Turn on") {
                    askEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }

                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (scanError == null) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            scanError ?: "Looking for devices… Keep the aer close to your phone, with its batteries in.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(found, key = { it.address }) { d ->
                            Card(
                                onClick = {
                                    scope.launch {
                                        store.add(d.address, d.name)
                                        onAdded(d.address)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                ListItem(
                                    headlineContent = { Text(d.name) },
                                    supportingContent = { Text("${d.address} · signal ${signal(d.rssi)}") },
                                    trailingContent = { Text("Add", color = MaterialTheme.colorScheme.primary) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun signal(rssi: Int) = when {
    rssi >= -60 -> "strong"
    rssi >= -80 -> "good"
    else -> "weak"
}

@Composable
private fun Prompt(text: String, action: String, onClick: () -> Unit) {
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text)
        Button(onClick = onClick) { Text(action) }
        Spacer(Modifier.height(8.dp))
    }
}
