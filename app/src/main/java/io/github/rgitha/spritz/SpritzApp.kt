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
            StatusPollWorker.schedule(this@SpritzApp, store.settingsNow().pollMinutes)
        }
    }
}
