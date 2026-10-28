package io.github.rgitha.spritz.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import io.github.rgitha.spritz.data.DeviceStore
import io.github.rgitha.spritz.data.WidgetState
import io.github.rgitha.spritz.work.SprayWorker
import kotlinx.coroutines.flow.first

/** One big "Spray now" button for the widget's device. */
class SprayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = DeviceStore.get(context)
        val name = store.widgetDevice()?.label
        val state = store.widgetState.first()
        provideContent {
            GlanceTheme { Content(name, state) }
        }
    }

    @Composable
    private fun Content(name: String?, state: WidgetState) {
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
            if (state.isBusy()) {
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
            val sub = when {
                state.isBusy() -> "Connecting…"
                state.text.isNotEmpty() -> state.text
                else -> name ?: "No device yet"
            }
            Text(
                sub,
                maxLines = 2,
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }

    companion object {
        suspend fun refresh(context: Context) = SprayWidget().updateAll(context)
    }
}

/** Busy, unless the worker was lost: a spray never takes a minute. */
private fun WidgetState.isBusy() = busy && System.currentTimeMillis() - at < 60_000

class SprayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val store = DeviceStore.get(context)
        val current = store.widgetState.first()
        if (current.isBusy()) return
        store.setWidgetState(WidgetState(busy = true, at = System.currentTimeMillis()))
        SprayWidget.refresh(context)
        SprayWorker.enqueue(context)
    }
}

class SprayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SprayWidget()
}
