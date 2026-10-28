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
import io.github.rgitha.spritz.R
import io.github.rgitha.spritz.data.SpritzRepository
import io.github.rgitha.spritz.data.WidgetState
import io.github.rgitha.spritz.notify.Notifier
import io.github.rgitha.spritz.widget.SprayWidget
import java.text.DateFormat
import java.util.Date
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

/** Sprays the widget's device once. Expedited so a widget tap acts at once. */
class SprayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val repo = SpritzRepository.get(ctx)
        val device = repo.store.widgetDevice()
        val text = if (device == null) {
            "Add a device in Spritz first"
        } else {
            runCatching { repo.spray(device.address) }.fold(
                onSuccess = { "Sprayed at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}" },
                onFailure = { it.message ?: "Spray failed" },
            )
        }
        repo.store.setWidgetState(WidgetState(text, System.currentTimeMillis(), busy = false))
        SprayWidget.refresh(ctx)
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

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SprayWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("spray", ExistingWorkPolicy.KEEP, request)
        }
    }
}
