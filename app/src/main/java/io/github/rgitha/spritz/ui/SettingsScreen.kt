package io.github.rgitha.spritz.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rgitha.spritz.BuildConfig
import io.github.rgitha.spritz.R
import io.github.rgitha.spritz.data.DeviceStore
import io.github.rgitha.spritz.data.Settings
import io.github.rgitha.spritz.work.StatusPollWorker
import kotlinx.coroutines.launch

private val POLL_CHOICES = listOf(0 to "Off", 15 to "Every 15 minutes", 30 to "Every 30 minutes",
    60 to "Every hour", 180 to "Every 3 hours", 360 to "Every 6 hours")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLog: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DeviceStore.get(context) }
    val settings by store.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    fun change(f: (Settings) -> Settings) = scope.launch { store.updateSettings(f) }

    Scaffold(topBar = { TopAppBar(title = { Text("App settings") }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        val s = settings ?: return@Scaffold
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Section("Background checks")
            Text(
                "Spritz checks each device now and then for low refill, low battery and schedules starting. " +
                    "Each check briefly connects to the device, so less often saves its batteries.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            POLL_CHOICES.forEach { (minutes, label) ->
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = { RadioButton(selected = s.pollMinutes == minutes, onClick = null) },
                    modifier = Modifier.selectable(selected = s.pollMinutes == minutes) {
                        change { it.copy(pollMinutes = minutes) }
                        StatusPollWorker.schedule(context, minutes)
                    },
                )
            }
            HorizontalDivider()
            Section("Notifications")
            Toggle("Low refill", s.notifyLowRefill) { v -> change { it.copy(notifyLowRefill = v) } }
            Toggle("Low battery", s.notifyLowBattery) { v -> change { it.copy(notifyLowBattery = v) } }
            Toggle("Schedule started", s.notifyScheduleStart, "Seen at the next check, so it can be late") { v ->
                change { it.copy(notifyScheduleStart = v) }
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Connection log") },
                supportingContent = { Text("Recent Bluetooth traffic for all devices, for troubleshooting") },
                modifier = Modifier.clickable(onClick = onLog),
            )
            HorizontalDivider()
            Section("About")
            Text(
                "${stringResource(R.string.app_full_name)} ${BuildConfig.VERSION_NAME}\n" +
                    "Free software under the GNU GPL v3 or later.\n" +
                    stringResource(R.string.disclaimer),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp),
    )
}

@Composable
private fun Toggle(title: String, checked: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
