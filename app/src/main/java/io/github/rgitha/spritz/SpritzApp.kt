package io.github.rgitha.spritz

import android.app.Application
import io.github.rgitha.spritz.data.DeviceStore
import io.github.rgitha.spritz.notify.Notifier
import io.github.rgitha.spritz.work.StatusPollWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SpritzApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val store = DeviceStore.get(this@SpritzApp)
            store.dedupeNames()
            // Intervals other than hourly came from the old choice of interval; it's now just on or off.
            val minutes = store.settingsNow().pollMinutes
            if (minutes > 0 && minutes != StatusPollWorker.DEFAULT_MINUTES) {
                store.updateSettings { it.copy(pollMinutes = StatusPollWorker.DEFAULT_MINUTES) }
            }
            StatusPollWorker.schedule(this@SpritzApp, store.settingsNow().pollMinutes)
        }
    }
}
