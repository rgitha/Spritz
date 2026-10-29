package io.github.rgitha.spritz.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.rgitha.spritz.data.DeviceStore
import io.github.rgitha.spritz.ui.theme.SpritzTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Picks the device a Spray widget controls: when the widget is placed, when it's
 * reconfigured (long-press › Reconfigure), or after its device was removed.
 */
class WidgetConfigActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        // Backing out leaves the widget unplaced.
        setResult(RESULT_CANCELED, result)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val store = DeviceStore.get(this)
        setContent {
            SpritzTheme {
                val devices by store.devices.collectAsState(initial = null)
                Scaffold(topBar = { TopAppBar(title = { Text("Spray widget: choose device") }) }) { padding ->
                    val list = devices ?: return@Scaffold
                    if (list.isEmpty()) {
                        Text("Add a device in Spritz first.", modifier = Modifier.padding(padding).padding(16.dp))
                    }
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.padding(padding)) {
                        items(list, key = { it.address }) { d ->
                            ListItem(
                                overlineContent = d.room?.let { { Text(it) } },
                                headlineContent = { Text(d.displayName) },
                                supportingContent = { Text(d.address) },
                                modifier = Modifier.clickable {
                                    lifecycleScope.launch {
                                        SprayWidget.assign(this@WidgetConfigActivity, appWidgetId, d.address)
                                        setResult(RESULT_OK, result)
                                        finish()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Told the id of a widget pinned from Device settings, and ties it to that device. */
class PinnedWidgetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || address == null) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                SprayWidget.assign(context.applicationContext, appWidgetId, address)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
    }
}
