package io.github.rgitha.spritz.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import kotlinx.coroutines.delay
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.rgitha.spritz.ui.shortReason
import io.github.rgitha.spritz.ui.userMessage
import io.github.rgitha.spritz.R
import io.github.rgitha.spritz.data.SpritzRepository
import io.github.rgitha.spritz.notify.Notifier
import io.github.rgitha.spritz.widget.SprayWidget
import java.util.concurrent.TimeUnit

/** Reads every device's status in the background, which drives the notifications. */
class StatusPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = SpritzRepository.get(applicationContext)
        for (d in repo.store.devicesNow()) {
            runCatching { repo.refresh(d.address) }
                .onFailure { Log.i(TAG, "poll ${d.address} failed: ${it.message}") }
        }
        return Result.success()
    }

    companion object {
        /** The interval when background checks are on; the setting is only on or off. */
        const val DEFAULT_MINUTES = 60

        private const val TAG = "StatusPoll"
        private const val NAME = "status-poll"

        /** Starts, changes or stops the periodic poll. */
        fun schedule(context: Context, minutes: Int) {
            val wm = WorkManager.getInstance(context)
            if (minutes <= 0) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<StatusPollWorker>(
                minutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES,
            ).build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

/** Sprays a widget's device. Expedited so a widget tap acts at once. */
class SprayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val appWidgetId = inputData.getInt(KEY_WIDGET, -1)
        val address = inputData.getString(KEY_ADDRESS) ?: return Result.failure()
        val repo = SpritzRepository.get(ctx)
        val error = runCatching { repo.spray(address) }.exceptionOrNull()
        if (error != null) {
            // The full reason as a toast; the widget only has room for a word or two.
            val label = repo.store.devicesNow().find { it.address == address }?.label
            val text = listOfNotNull(label, error.userMessage()).joinToString(": ")
            Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
        }
        val id = runCatching { GlanceAppWidgetManager(ctx).getGlanceIdBy(appWidgetId) }.getOrNull()
            ?: return Result.success()
        updateAppWidgetState(ctx, id) {
            if (error == null) {
                it[SprayWidget.OUTCOME] = SprayWidget.OK
            } else {
                it[SprayWidget.OUTCOME] = SprayWidget.FAILED
                it[SprayWidget.ERROR] = error.shortReason()
            }
        }
        SprayWidget().update(ctx, id)
        // Then the mark goes.
        delay(SprayWidget.RESULT_MS)
        updateAppWidgetState(ctx, id) {
            it.remove(SprayWidget.OUTCOME)
            it.remove(SprayWidget.ERROR)
        }
        SprayWidget().update(ctx, id)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, Notifier.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Spraying…")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            ForegroundInfo(FG_ID, n)
        }
    }

    companion object {
        private const val FG_ID = 1001

        private const val KEY_WIDGET = "widget"
        private const val KEY_ADDRESS = "address"

        fun enqueue(context: Context, appWidgetId: Int, address: String) {
            val request = OneTimeWorkRequestBuilder<SprayWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_WIDGET to appWidgetId, KEY_ADDRESS to address))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("spray-$appWidgetId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
