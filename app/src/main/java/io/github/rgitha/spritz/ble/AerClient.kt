package io.github.rgitha.spritz.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.SystemClock
import io.github.rgitha.spritz.protocol.Cbor
import io.github.rgitha.spritz.protocol.DeviceStatus
import io.github.rgitha.spritz.protocol.Messages
import io.github.rgitha.spritz.protocol.ModeSource
import io.github.rgitha.spritz.protocol.MsgType
import io.github.rgitha.spritz.protocol.Reply
import io.github.rgitha.spritz.protocol.Schedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.UUID

class AerException(message: String, val kind: Kind = Kind.OTHER) : Exception(message) {
    /** Broad causes, for places too small for the full message (the widget). */
    enum class Kind { NOT_IN_RANGE, BLUETOOTH_OFF, NO_PERMISSION, NO_REPLY, OTHER }
}

/** The device answered with ack 100. */
class RefusedException(request: Int) : Exception("The device refused request $request")

/** The device answered, but is busy with something that rules the request out. */
class BusyException(message: String) : Exception(message)

/**
 * Talks to one aer device. Operations are serialised; the link is opened on demand
 * and closed [IDLE_DISCONNECT_MS] after the last exchange to save the device's battery.
 */
@SuppressLint("MissingPermission")
class AerClient(context: Context, val address: String) {

    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opLock = Mutex()

    @Volatile private var link: Link? = null
    private var disconnectJob: Job? = null

    /** Reads the status, setting the device's clock first on a fresh link. */
    suspend fun readStatus(): DeviceStatus = run { l -> freshStatus(l) }

    private suspend fun freshStatus(l: Link): DeviceStatus {
        if (!l.timeSet) {
            l.waitSinceConnect(STATUS_DELAY_MS - 1000)
            setTime(l)
            delay(1000)
        } else {
            l.waitSinceConnect(STATUS_DELAY_MS)
        }
        return status(l)
    }

    /**
     * Lights the three interval lights so the device can be picked out; returns once
     * they're on. Only while idle: clearing them takes "auto off", which would stop
     * auto mode or pause a schedule run. They're cleared [IDENTIFY_MS] later.
     */
    suspend fun identify() {
        run { l ->
            val mode = freshStatus(l).mode
            when {
                mode.source == ModeSource.SCHEDULE ->
                    throw BusyException("A schedule is running. Skip it or wait for it to end to use Identify.")
                !mode.isIdle -> throw BusyException("Auto mode is running. Stop it to use Identify.")
            }
            l.request(Messages.ledTest(), MsgType.LED_TEST, "Light up").requireOk()
            litAt = SystemClock.elapsedRealtime()
        }
        scope.launch {
            delay(IDENTIFY_MS)
            runCatching { run { l -> clearLights(l) } }
        }
    }

    /**
     * When the lights were lit and not yet cleared (a failed attempt is retried on the
     * next connection): sends auto off if the device is still idle. Any other mode
     * command already turned them off.
     */
    private suspend fun clearLights(l: Link) {
        if (litAt == 0L || SystemClock.elapsedRealtime() - litAt < IDENTIFY_MS) return
        if (freshStatus(l).mode.isIdle) {
            l.request(Messages.stopAuto(), MsgType.STOP, "Lights off").requireOk()
        }
        litAt = 0L
    }

    /** When Identify lit the lights, while they're still to be cleared; 0 otherwise. */
    @Volatile private var litAt = 0L

    /** Sprays once ([interval] 0) or starts auto mode, then reads the status back. */
    suspend fun spray(interval: Int = 0): DeviceStatus = command(
        Messages.spray(interval), MsgType.SPRAY,
        if (interval == 0) "Spray" else "Start auto, every $interval min",
    )

    suspend fun stopAuto(): DeviceStatus = command(Messages.stopAuto(), MsgType.STOP, "Stop auto")

    suspend fun stopRun(run: Int): DeviceStatus = command(Messages.stopRun(run), MsgType.STOP, "Skip scheduled run")

    suspend fun refillReset(): DeviceStatus = command(Messages.refillReset(), MsgType.REFILL_RESET, "Reset spray count")

    suspend fun readSchedules(): List<Schedule> = run { l ->
        l.waitSinceConnect(COMMAND_DELAY_MS)
        Schedule.listFrom(l.request(Messages.readSchedules(), MsgType.READ_SCHEDULES, "Read schedules"))
    }

    /** Replaces every schedule on the device: remove all, wait, then upload the full list. */
    suspend fun writeSchedules(list: List<Schedule>) = run { l ->
        l.waitSinceConnect(COMMAND_DELAY_MS)
        l.request(Messages.removeSchedules(), MsgType.REMOVE_SCHEDULES, "Clear schedules").requireOk()
        if (list.isEmpty()) return@run
        delay(SCHEDULE_UPLOAD_GAP_MS)
        l.request(Messages.setSchedules(list), MsgType.SET_SCHEDULES, "Upload ${list.size} schedules", AerUuids.SCHEDULE)
            .requireOk()
    }

    fun close() {
        scope.launch { opLock.withLock { drop() } }
    }

    private suspend fun command(frame: ByteArray, type: Int, what: String): DeviceStatus = run { l ->
        l.waitSinceConnect(COMMAND_DELAY_MS)
        l.request(frame, type, what).requireOk()
        if (!l.timeSet) setTime(l)
        delay(500)
        status(l)
    }

    private suspend fun setTime(l: Link) {
        // Best effort: a missing clock ack shouldn't fail the caller's operation.
        val ok = runCatching {
            l.request(Messages.setTime(System.currentTimeMillis(), TimeZone.getDefault()), MsgType.SET_TIME, "Set clock")
        }.isSuccess
        l.timeSet = ok
    }

    private suspend fun status(l: Link): DeviceStatus {
        val s = DeviceStatus.from(l.request(Messages.status(), MsgType.STATUS, "Read status", logDone = false))
        val battery = s.batteryPercent?.let { "battery $it%" } ?: "battery unknown"
        BleLog.add(address, "✓ Status: ${s.mode.shortLabel}, $battery, ${s.totalSprays} sprays counted")
        return s
    }

    private suspend fun <T> run(block: suspend (Link) -> T): T = opLock.withLock {
        disconnectJob?.cancel()
        try {
            if (link?.connected == false) drop() // the device went away while idle
            val l = link ?: connect().also { link = it }
            // Lights left on by a failed clear; best effort, before the caller's work.
            if (litAt != 0L) runCatching { clearLights(l) }
            try {
                block(l)
            } catch (e: Exception) {
                if (!l.connected) drop()
                throw e
            }
        } catch (e: Exception) {
            throw translate(e)
        } finally {
            scheduleDisconnect()
        }
    }

    /**
     * Turns a system error from a Bluetooth call (such as DeadObjectException while
     * Bluetooth is being switched off) into one with a message for people.
     */
    private fun translate(e: Exception): Exception = when (e) {
        is CancellationException, is AerException, is RefusedException, is BusyException, is SecurityException -> e
        else -> {
            drop() // the link can't be trusted after this
            if (BlePermissions.adapter(context)?.isEnabled != true) {
                AerException("Bluetooth is off", AerException.Kind.BLUETOOTH_OFF)
            } else {
                AerException("Bluetooth stopped responding. Try again.")
            }
        }
    }

    /** Closes the link once no reply is pending and it has been idle for [IDLE_DISCONNECT_MS]. */
    private fun scheduleDisconnect() {
        disconnectJob = scope.launch {
            while (true) {
                val l = link ?: return@launch
                val left = l.idleRemaining()
                if (left > 0) {
                    delay(left)
                    continue
                }
                val done = opLock.withLock {
                    when {
                        link !== l -> true
                        l.hasPending() || l.idleRemaining() > 0 -> false
                        else -> { drop("Link closed (idle)"); true }
                    }
                }
                if (done) return@launch
                delay(1000)
            }
        }
    }

    /** Closes the link; [log] is what the connection log says, if it was still up. */
    private fun drop(log: String = "Link closed") {
        link?.close(log)
        link = null
    }

    private suspend fun connect(): Link {
        if (!BlePermissions.canConnect(context)) {
            BleLog.add(address, "✗ Can't connect: Bluetooth permission not granted")
            throw AerException("Bluetooth permission not granted", AerException.Kind.NO_PERMISSION)
        }
        val adapter = BlePermissions.adapter(context)
        if (adapter == null || !adapter.isEnabled) {
            BleLog.add(address, "✗ Can't connect: Bluetooth is off")
            throw AerException("Bluetooth is off", AerException.Kind.BLUETOOTH_OFF)
        }
        var retried = false
        while (true) {
            val l = Link(adapter.getRemoteDevice(address))
            try {
                l.open()
                return l
            } catch (e: Exception) {
                // Read before closing: closing can report a drop of its own.
                val dropped = l.dropStatus
                l.close(log = null)
                // Android often drops a new link at once (status 133 or 62), mostly soon after
                // an earlier one closed; a second try usually works.
                if (dropped != null && !retried && e !is CancellationException) {
                    BleLog.add(address, "Connection dropped (status $dropped), retrying")
                    retried = true
                    delay(RETRY_DELAY_MS)
                    continue
                }
                val reason = (e as? AerException)?.message ?: "Bluetooth error"
                BleLog.add(address, "✗ Couldn't connect: $reason" + (dropped?.let { " (status $it)" } ?: ""))
                throw e
            }
        }
    }

    private fun Reply.requireOk(): Reply {
        if (ack != null && ack != MsgType.ACK_OK) throw AerException("Device replied with ack $ack")
        return this
    }

    /** One GATT connection. */
    private inner class Link(private val device: BluetoothDevice) : BluetoothGattCallback() {
        private var gatt: BluetoothGatt? = null
        @Volatile var connected = false
            private set
        var timeSet = false
        /** The status Android gave when the link dropped before it was ready; null if it didn't. */
        @Volatile var dropStatus: Int? = null
        private var ready = false
        private var connectedAt = 0L
        /** Last write or notification, for the idle disconnect. */
        @Volatile var lastActivity = 0L

        fun hasPending(): Boolean = synchronized(this) { pending != null }

        fun idleRemaining(): Long = lastActivity + IDLE_DISCONNECT_MS - SystemClock.elapsedRealtime()

        private var connectWait = CompletableDeferred<Unit>()
        private var discoverWait = CompletableDeferred<Unit>()
        private var writeWait: CompletableDeferred<Int>? = null
        private var mtuWait: CompletableDeferred<Int?>? = null

        /** The single reply we're waiting for, keyed by the expected reply type. */
        private var pending: Pair<Int, CompletableDeferred<Reply>>? = null
        private val buffers = HashMap<UUID, ByteArrayOutputStream>()
        private val lastData = HashMap<UUID, Long>()

        suspend fun open() {
            BleLog.add(address, "Connecting")
            val g = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
            gatt = g ?: throw AerException("Couldn't start a connection")
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectWait.await() }
                ?: throw AerException("Device didn't respond. Is it nearby and switched on?", AerException.Kind.NOT_IN_RANGE)
            withTimeoutOrNull(DISCOVER_TIMEOUT_MS) { g.discoverServices(); discoverWait.await() }
                ?: throw AerException("Service discovery timed out")
            val service = g.getService(AerUuids.SERVICE) ?: throw AerException("Not an aer device")
            // Request the MTU explicitly: on Android 16 no replies arrived without this,
            // although Android 14+ requests 517 by itself. (The Home Assistant integration
            // went through an ESPHome Bluetooth proxy, which negotiates a larger MTU itself.)
            // The status reply is about 99 bytes and the set-time write 35, so refuse a
            // link that can't carry them.
            val mtu = CompletableDeferred<Int?>().also { mtuWait = it }
            val agreed = if (g.requestMtu(REQUESTED_MTU)) withTimeoutOrNull(1000) { mtu.await() } else null
            if (agreed != null && agreed < MIN_MTU) throw AerException("Link too small ($agreed bytes)")
            val notify = service.getCharacteristic(AerUuids.NOTIFY)
                ?: throw AerException("Notify characteristic missing")
            // 0001 has no CCCD, so register locally only; the device notifies without one.
            if (!g.setCharacteristicNotification(notify, true)) throw AerException("Couldn't subscribe")
            connectedAt = SystemClock.elapsedRealtime()
            lastActivity = connectedAt
            ready = true
            BleLog.add(address, "Connected")
        }

        suspend fun waitSinceConnect(ms: Long) {
            val left = connectedAt + ms - SystemClock.elapsedRealtime()
            if (left > 0) delay(left)
        }

        /**
         * Sends [frame] and waits for its reply. [what] says what it is in the log; with
         * [logDone] false the caller logs the outcome itself.
         */
        suspend fun request(
            frame: ByteArray,
            type: Int,
            what: String,
            char: UUID = AerUuids.COMMAND,
            logDone: Boolean = true,
        ): Reply {
            val g = gatt ?: throw AerException("Not connected")
            val c = g.getService(AerUuids.SERVICE)?.getCharacteristic(char)
                ?: throw AerException("Characteristic $char missing")
            val expected = MsgType.replyTo(type)
            val replyWait = CompletableDeferred<Reply>()
            synchronized(this) { pending = expected to replyWait }
            try {
                val written = CompletableDeferred<Int>().also { writeWait = it }
                BleLog.add(address, "→ $what")
                val rc = writeCharacteristic(g, c, frame)
                lastActivity = SystemClock.elapsedRealtime()
                if (rc != WRITE_STARTED) {
                    BleLog.add(address, "✗ $what: couldn't send")
                    throw AerException("Write failed ($rc)")
                }
                val status = withTimeoutOrNull(REPLY_TIMEOUT_MS) { written.await() }
                if (status == null) {
                    BleLog.add(address, "✗ $what: not delivered")
                    throw AerException("Write timed out", AerException.Kind.NO_REPLY)
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    BleLog.add(address, "✗ $what: couldn't send")
                    throw AerException("Write failed ($status)")
                }
                val reply = withTimeoutOrNull(REPLY_TIMEOUT_MS) { replyWait.await() }
                if (reply == null) {
                    BleLog.add(address, "✗ $what: no reply in ${REPLY_TIMEOUT_MS / 1000} s")
                    throw AerException(
                        if (connected) "No reply from the device" else "Device disconnected",
                        AerException.Kind.NO_REPLY,
                    )
                }
                if (reply.isRefusalOf(expected)) {
                    BleLog.add(address, "✗ $what: refused by the device")
                    throw RefusedException(type)
                }
                if (logDone) {
                    BleLog.add(address, if (reply.ack == null || reply.ack == MsgType.ACK_OK) "✓ $what" else "✗ $what: ack ${reply.ack}")
                }
                return reply
            } finally {
                synchronized(this) { if (pending?.second === replyWait) pending = null }
            }
        }

        fun close(log: String? = "Link closed") {
            val wasUp = connected
            connected = false
            gatt?.let {
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            gatt = null
            // A link that already dropped was logged as lost.
            if (log != null && wasUp) BleLog.add(address, log)
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED && connected) BleLog.add(address, "Link lost")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                connectWait.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                if (!ready) dropStatus = status
                val err = AerException("Device disconnected", AerException.Kind.NO_REPLY)
                connectWait.completeExceptionally(err)
                discoverWait.completeExceptionally(err)
                synchronized(this) { pending?.second?.completeExceptionally(err) }
                writeWait?.completeExceptionally(err)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) discoverWait.complete(Unit)
            else discoverWait.completeExceptionally(AerException("Service discovery failed ($status)"))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuWait?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeWait?.complete(status)
        }

        /** API 33+: the value comes as a parameter, so a later notification can't overwrite it. */
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onData(c.uuid, value)
        }

        /**
         * API 26–32 only call this version. On 33+ it's ignored, so a reply is never
         * handled twice.
         */
        @Deprecated("Used before Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                onData(c.uuid, c.value?.copyOf() ?: return)
            }
        }

        /** Reassembles fragmented replies until a whole CBOR item decodes. */
        private fun onData(uuid: UUID, value: ByteArray) {
            val now = SystemClock.elapsedRealtime()
            lastActivity = now
            val buf = buffers.getOrPut(uuid) { ByteArrayOutputStream() }
            if (now - (lastData[uuid] ?: now) > FRAGMENT_TIMEOUT_MS) buf.reset()
            lastData[uuid] = now
            buf.write(value)
            if (buf.size() > MAX_REPLY_BYTES) {
                BleLog.add(address, "Unreadable reply, ignored")
                buf.reset()
                return
            }
            val data = buf.toByteArray()
            val item = try {
                Cbor.decode(data).first
            } catch (_: Cbor.Incomplete) {
                return
            } catch (e: Cbor.Malformed) {
                BleLog.add(address, "Unreadable reply, ignored")
                buf.reset()
                return
            }
            buf.reset()
            val reply = Reply.parse(item) ?: return
            synchronized(this) {
                val (expected, wait) = pending ?: return
                if (reply.type == expected || reply.isRefusalOf(expected)) wait.complete(reply)
            }
        }

        /**
         * Starts a write with response. Returns the API 33+ status code, or on older
         * versions [WRITE_STARTED] / -1 for the boolean result.
         */
        private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray): Int {
            val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, v, type)
            } else {
                @Suppress("DEPRECATION")
                val started = run { c.writeType = type; c.value = v; g.writeCharacteristic(c) }
                if (started) WRITE_STARTED else -1
            }
        }
    }

    companion object {
        /** BluetoothStatusCodes.SUCCESS; also used for a started write before API 33. */
        private const val WRITE_STARTED = 0
        const val REQUESTED_MTU = 185
        /** The ~99-byte status reply plus 3 bytes of ATT overhead, with a little room. */
        const val MIN_MTU = 104
        const val CONNECT_TIMEOUT_MS = 5_000L
        /** The pause before the one retry of a connection Android dropped. */
        const val RETRY_DELAY_MS = 500L
        const val DISCOVER_TIMEOUT_MS = 5_000L
        const val REPLY_TIMEOUT_MS = 5_000L
        const val STATUS_DELAY_MS = 2_000L
        const val COMMAND_DELAY_MS = 500L
        /** How long Identify keeps the lights on. */
        const val IDENTIFY_MS = 3_000L
        const val SCHEDULE_UPLOAD_GAP_MS = 3_000L
        const val IDLE_DISCONNECT_MS = 15_000L
        const val FRAGMENT_TIMEOUT_MS = 2_000L
        const val MAX_REPLY_BYTES = 512
    }
}

/** One client per device, shared by the UI, the widget and the workers. */
object AerClients {
    private val clients = HashMap<String, AerClient>()

    @Synchronized
    fun get(context: Context, address: String): AerClient =
        clients.getOrPut(address) { AerClient(context, address) }

    @Synchronized
    fun remove(address: String) {
        clients.remove(address)?.close()
    }
}
