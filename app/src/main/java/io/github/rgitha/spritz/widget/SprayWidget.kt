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
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ColorFilter
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import io.github.rgitha.spritz.MainActivity
import io.github.rgitha.spritz.R
import io.github.rgitha.spritz.data.DeviceStore
import io.github.rgitha.spritz.data.SavedDevice
import io.github.rgitha.spritz.work.SprayWorker

/**
 * A "Spray" button for one device. Each placed widget keeps its own device,
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
        val outcome = outcomeOf(prefs)
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
            // "Spray" and the device stay put; a small mark beside "Spray" shows
            // progress, then a tick or cross, as in the app.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Spray",
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    ),
                )
                when (outcome) {
                    RUNNING -> {
                        Spacer(GlanceModifier.width(4.dp))
                        CircularProgressIndicator(
                            modifier = GlanceModifier.size(12.dp),
                            color = GlanceTheme.colors.onPrimaryContainer,
                        )
                    }
                    OK -> {
                        Spacer(GlanceModifier.width(4.dp))
                        Image(
                            ImageProvider(R.drawable.ic_widget_check),
                            contentDescription = "Sprayed",
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                            modifier = GlanceModifier.size(12.dp),
                        )
                    }
                    FAILED -> {
                        Spacer(GlanceModifier.width(4.dp))
                        Image(
                            ImageProvider(R.drawable.ic_widget_close),
                            contentDescription = "Spray failed",
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.error),
                            modifier = GlanceModifier.size(12.dp),
                        )
                    }
                }
            }
            Spacer(GlanceModifier.height(4.dp))
            // A failure's short reason stands in for the device while the cross shows.
            val error = prefs[ERROR].takeIf { outcome == FAILED }
            Text(
                error ?: device.label,
                maxLines = 2,
                style = TextStyle(
                    color = if (error != null) GlanceTheme.colors.error else GlanceTheme.colors.onPrimaryContainer,
                    fontSize = 10.sp,
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
        /** [RUNNING], [OK] or [FAILED] for the last spray; absent when there's nothing to show. */
        val OUTCOME = stringPreferencesKey("outcome")
        val AT = longPreferencesKey("at")
        /** The short reason for a failed spray, shown with the cross. */
        val ERROR = stringPreferencesKey("error")

        const val RUNNING = "running"
        const val OK = "ok"
        const val FAILED = "failed"

        /** How long the tick or cross stays. */
        const val RESULT_MS = 3_000L

        /** A spray never takes a minute, so an older "running" means the worker was lost. */
        fun outcomeOf(prefs: Preferences): String? {
            val o = prefs[OUTCOME]
            return if (o == RUNNING && System.currentTimeMillis() - (prefs[AT] ?: 0) > 60_000) null else o
        }

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
                it.remove(OUTCOME)
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
            if (SprayWidget.outcomeOf(prefs) == SprayWidget.RUNNING) return@updateAppWidgetState
            address = prefs[SprayWidget.ADDRESS]
                ?: SprayWidget.legacyDevice(store.devicesNow(), store.settingsNow().widgetDevice)?.address
            if (address != null) {
                prefs[SprayWidget.OUTCOME] = SprayWidget.RUNNING
                prefs[SprayWidget.AT] = System.currentTimeMillis()
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
