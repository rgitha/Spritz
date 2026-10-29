package io.github.rgitha.spritz.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.rgitha.spritz.MainActivity
import io.github.rgitha.spritz.R
import io.github.rgitha.spritz.data.SavedDevice
import io.github.rgitha.spritz.data.Settings
import io.github.rgitha.spritz.protocol.ModeSource
import io.github.rgitha.spritz.protocol.Schedule
import io.github.rgitha.spritz.ui.clockText

object Notifier {
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_SCHEDULE = "schedule"
    const val CHANNEL_WORK = "work"

    const val LOW_REFILL_PERCENT = 10
    const val LOW_BATTERY_PERCENT = 15

    /** Re-arm a warning only once the value has clearly recovered (new cartridge or batteries). */
    private const val REARM_MARGIN = 10

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_ALERTS, "Refill and battery", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Low refill and low battery warnings" },
                NotificationChannel(CHANNEL_SCHEDULE, "Schedule started", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "A schedule started spraying" },
                NotificationChannel(CHANNEL_WORK, "Background activity", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Shown briefly while talking to a device in the background" },
            )
        )
    }

    /**
     * Posts any notifications the change from [before] to [after] calls for,
     * and returns [after] with its notified flags updated.
     */
    fun check(
        context: Context,
        settings: Settings,
        before: SavedDevice,
        after: SavedDevice,
        running: Schedule? = null,
    ): SavedDevice {
        val status = after.status ?: return after
        var result = after

        val refill = after.refillPercent
        if (refill != null) {
            if (refill <= LOW_REFILL_PERCENT && !after.lowRefillNotified) {
                if (settings.notifyLowRefill) {
                    post(context, after, KIND_REFILL, CHANNEL_ALERTS, "Refill low: ${after.label}",
                        "About $refill% left. Replace the cartridge, then tap Refill reset in Spritz.")
                }
                result = result.copy(lowRefillNotified = true)
            } else if (refill > LOW_REFILL_PERCENT + REARM_MARGIN) {
                result = result.copy(lowRefillNotified = false)
            }
        }

        val battery = status.batteryPercent
        if (battery != null) {
            if ((battery <= LOW_BATTERY_PERCENT || status.batteryCritical) && !after.lowBatteryNotified) {
                if (settings.notifyLowBattery) {
                    val text = if (status.batteryCritical) {
                        "Batteries are flat: the device can't spray. Replace both AA cells."
                    } else {
                        "About $battery%. Replace both AA cells soon."
                    }
                    post(context, after, KIND_BATTERY, CHANNEL_ALERTS, "Battery low: ${after.label}", text)
                }
                result = result.copy(lowBatteryNotified = true)
            } else if (battery > LOW_BATTERY_PERCENT + REARM_MARGIN) {
                result = result.copy(lowBatteryNotified = false)
            }
        }

        val wasScheduled = before.status?.mode?.source == ModeSource.SCHEDULE
        val mode = status.mode
        if (settings.notifyScheduleStart && mode.source == ModeSource.SCHEDULE && !wasScheduled) {
            val every = mode.interval?.let { " every $it min" } ?: ""
            val window = running?.let {
                ", ${clockText(context, it.startMinute)} to ${clockText(context, it.displayEndMinute)}"
            } ?: ""
            post(context, after, KIND_SCHEDULE, CHANNEL_SCHEDULE, "Schedule started: ${after.label}",
                "Spraying$every$window.")
        }
        return result
    }

    private const val KIND_REFILL = 1
    private const val KIND_BATTERY = 2
    private const val KIND_SCHEDULE = 3

    private fun post(context: Context, device: SavedDevice, kind: Int, channel: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DEVICE, device.address)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = device.address.hashCode() * 8 + kind
        val pi = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
