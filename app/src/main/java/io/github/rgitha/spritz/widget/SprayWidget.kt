package io.github.rgitha.spritz.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import io.github.rgitha.spritz.MainActivity
import io.github.rgitha.spritz.data.DeviceStore
import io.github.rgitha.spritz.data.SavedDevice
import io.github.rgitha.spritz.work.SprayWorker

/**
 * A "Spray now" button for one device. Each placed widget keeps its own device,
 * chosen when it's placed (or changed with Reconfigure), and its own last result.
 */
class SprayWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = DeviceStore.get(context)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val prefs = currentState<Preferences>()
            val devices by store.devices.collectAsState(initial = null)
            val settings by store.settings.collectAsState(initial = null)
            val list = devices ?: return@provideContent
            val chosen = prefs[ADDRESS]
            val device = if (chosen != null) list.find { it.address == chosen } else legacyDevice(list, settings?.widgetDevice)
            GlanceTheme {
                when {
                    list.isEmpty() -> Message(
                        "Add a device in Spritz",
                        actionStartActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)),
                    )
                    device == null -> Message("Device removed · Tap to choose", chooseAction(context, appWidgetId))
                    else -> Content(device, prefs)
                }
            }
        }
    }

    @Composable
    private fun Content(device: SavedDevice, prefs: Preferences) {
        val busy = prefs[BUSY] == true && System.currentTimeMillis() - (prefs[AT] ?: 0) < 60_000
        val result = prefs[TEXT]
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(24.dp)
                .background(GlanceTheme.colors.primaryContainer)
                .clickable(actionRunCallback<SprayAction>())
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = GlanceModifier.size(28.dp),
                    color = GlanceTheme.colors.onPrimaryContainer,
                )
            } else {
                Text(
                    "Spray now",
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            Spacer(GlanceModifier.height(4.dp))
            Text(
                when {
                    busy -> "Spraying…"
                    !result.isNullOrEmpty() -> result
                    else -> device.label
                },
                maxLines = 2,
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }

    @Composable
    private fun Message(text: String, action: Action) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(24.dp)
                .background(GlanceTheme.colors.surfaceVariant)
                .clickable(action)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                maxLines = 3,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center),
            )
        }
    }

    companion object {
        val ADDRESS = stringPreferencesKey("address")
        val TEXT = stringPreferencesKey("text")
        val AT = longPreferencesKey("at")
        val BUSY = booleanPreferencesKey("busy")

        /** How long a widget shows the result of a spray before its device again. */
        const val RESULT_MS = 3_000L

        suspend fun refresh(context: Context) = SprayWidget().updateAll(context)

        /**
         * Widgets placed before each had its own device: they keep spraying the device
         * the app used to give the widget, or the first one.
         */
        fun legacyDevice(list: List<SavedDevice>, widgetDevice: String?): SavedDevice? =
            list.find { it.address == widgetDevice } ?: list.firstOrNull()

        /** Ties widget [appWidgetId] to the device at [address]. */
        suspend fun assign(context: Context, appWidgetId: Int, address: String) {
            val id = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(context, id) {
                it[ADDRESS] = address
                it.remove(TEXT)
                it[BUSY] = false
            }
            SprayWidget().update(context, id)
        }

        private fun chooseAction(context: Context, appWidgetId: Int): Action = actionStartActivity(
            Intent(context, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

class SprayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val manager = GlanceAppWidgetManager(context)
        val appWidgetId = manager.getAppWidgetId(glanceId)
        val store = DeviceStore.get(context)
        var address: String? = null
        updateAppWidgetState(context, glanceId) { prefs ->
            val busy = prefs[SprayWidget.BUSY] == true &&
                System.currentTimeMillis() - (prefs[SprayWidget.AT] ?: 0) < 60_000
            if (busy) return@updateAppWidgetState
            address = prefs[SprayWidget.ADDRESS]
                ?: SprayWidget.legacyDevice(store.devicesNow(), store.settingsNow().widgetDevice)?.address
            if (address != null) {
                prefs[SprayWidget.BUSY] = true
                prefs[SprayWidget.AT] = System.currentTimeMillis()
                prefs.remove(SprayWidget.TEXT)
            }
        }
        val target = address ?: return
        SprayWidget().update(context, glanceId)
        SprayWorker.enqueue(context, appWidgetId, target)
    }
}

class SprayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SprayWidget()
}
