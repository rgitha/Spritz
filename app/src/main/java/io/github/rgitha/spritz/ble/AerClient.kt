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
import io.github.rgitha.spritz.protocol.MsgType
import io.github.rgitha.spritz.protocol.Reply
import io.github.rgitha.spritz.protocol.Schedule
import io.github.rgitha.spritz.protocol.toHex
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

class AerException(message: String) : Exception(message)

/** The device answered with ack 100. */
class RefusedException(request: Int) : Exception("The device refused request $request")

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
    suspend fun readStatus(): DeviceStatus = run { l ->
        if (!l.timeSet) {
            l.waitSinceConnect(STATUS_DELAY_MS - 1000)
            setTime(l)
            delay(1000)
        } else {
            l.waitSinceConnect(STATUS_DELAY_MS)
        }
        status(l)
    }

    /** Sprays once ([interval] 0) or starts auto mode, then reads the status back. */
    suspend fun spray(interval: Int = 0): DeviceStatus =
        command(Messages.spray(interval), MsgType.SPRAY)

    suspend fun stopAuto(): DeviceStatus = command(Messages.stopAuto(), MsgType.STOP)

    suspend fun stopRun(run: Int): DeviceStatus = command(Messages.stopRun(run), MsgType.STOP)

    suspend fun refillReset(): DeviceStatus = command(Messages.refillReset(), MsgType.REFILL_RESET)

    suspend fun readSchedules(): List<Schedule> = run { l ->
        l.waitSinceConnect(COMMAND_DELAY_MS)
        Schedule.listFrom(l.request(Messages.readSchedules(), MsgType.READ_SCHEDULES))
    }

    /** Replaces every schedule on the device: remove all, wait, then upload the full list. */
    suspend fun writeSchedules(list: List<Schedule>) = run { l ->
        l.waitSinceConnect(COMMAND_DELAY_MS)
        l.request(Messages.removeSchedules(), MsgType.REMOVE_SCHEDULES).requireOk()
        if (list.isEmpty()) return@run
        delay(SCHEDULE_UPLOAD_GAP_MS)
        l.request(Messages.setSchedules(list), MsgType.SET_SCHEDULES, AerUuids.SCHEDULE).requireOk()
    }

    fun close() {
        scope.launch { opLock.withLock { drop() } }
    }

    private suspend fun command(frame: ByteArray, type: Int): DeviceStatus = run { l ->
        l.waitSinceConnect(COMMAND_DELAY_MS)
        l.request(frame, type).requireOk()
        if (!l.timeSet) setTime(l)
        delay(500)
        status(l)
    }

    private suspend fun setTime(l: Link) {
        // Best effort: a missing clock ack shouldn't fail the caller's operation.
        val ok = runCatching {
            l.request(Messages.setTime(System.currentTimeMillis(), TimeZone.getDefault()), MsgType.SET_TIME)
        }.isSuccess
        l.timeSet = ok
    }

    private suspend fun status(l: Link) =
        DeviceStatus.from(l.request(Messages.status(), MsgType.STATUS))

    private suspend fun <T> run(block: suspend (Link) -> T): T = opLock.withLock {
        disconnectJob?.cancel()
        try {
            if (link?.connected == false) drop() // the device went away while idle
            val l = link ?: connect().also { link = it }
            try {
                block(l)
            } catch (e: Exception) {
                if (!l.connected) drop()
                throw e
            }
        } finally {
            scheduleDisconnect()
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
                        else -> { drop(); true }
                    }
                }
                if (done) return@launch
                delay(1000)
            }
        }
    }

    private fun drop() {
        link?.close()
        link = null
    }

    private suspend fun connect(): Link {
        if (!BlePermissions.canConnect(context)) throw AerException("Bluetooth permission not granted")
        val adapter = BlePermissions.adapter(context) ?: throw AerException("No Bluetooth adapter")
        if (!adapter.isEnabled) throw AerException("Bluetooth is off")
        val l = Link(adapter.getRemoteDevice(address))
        try {
            l.open()
        } catch (e: Exception) {
            l.close()
            throw e
        }
        return l
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
            val g = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
            gatt = g ?: throw AerException("Couldn't start a connection")
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectWait.await() }
                ?: throw AerException("Device didn't respond. Is it nearby and switched on?")
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
            BleLog.add(address, "MTU: $agreed")
            if (agreed != null && agreed < MIN_MTU) throw AerException("Link too small ($agreed bytes)")
            val notify = service.getCharacteristic(AerUuids.NOTIFY)
                ?: throw AerException("Notify characteristic missing")
            // 0001 has no CCCD, so register locally only; the device notifies without one.
            val subscribed = g.setCharacteristicNotification(notify, true)
            BleLog.add(address, "setCharacteristicNotification ${notify.uuid}: $subscribed")
            if (!subscribed) throw AerException("Couldn't subscribe")
            connectedAt = SystemClock.elapsedRealtime()
            lastActivity = connectedAt
            BleLog.add(address, "connected")
        }

        suspend fun waitSinceConnect(ms: Long) {
            val left = connectedAt + ms - SystemClock.elapsedRealtime()
            if (left > 0) delay(left)
        }

        suspend fun request(frame: ByteArray, type: Int, char: UUID = AerUuids.COMMAND): Reply {
            val g = gatt ?: throw AerException("Not connected")
            val c = g.getService(AerUuids.SERVICE)?.getCharacteristic(char)
                ?: throw AerException("Characteristic $char missing")
            val expected = MsgType.replyTo(type)
            val replyWait = CompletableDeferred<Reply>()
            synchronized(this) { pending = expected to replyWait }
            try {
                val written = CompletableDeferred<Int>().also { writeWait = it }
                val rc = writeCharacteristic(g, c, frame)
                BleLog.add(address, "writeCharacteristic $char ${frame.toHex()} -> $rc")
                lastActivity = SystemClock.elapsedRealtime()
                if (rc != WRITE_STARTED) throw AerException("Write failed ($rc)")
                val status = withTimeoutOrNull(REPLY_TIMEOUT_MS) { written.await() }
                    ?: throw AerException("Write timed out").also { BleLog.add(address, "no onCharacteristicWrite within ${REPLY_TIMEOUT_MS}ms") }
                if (status != BluetoothGatt.GATT_SUCCESS) throw AerException("Write failed ($status)")
                val reply = withTimeoutOrNull(REPLY_TIMEOUT_MS) { replyWait.await() }
                    ?: throw AerException(if (connected) "No reply from the device" else "Device disconnected")
                        .also { BleLog.add(address, "no reply $expected within ${REPLY_TIMEOUT_MS}ms") }
                if (reply.isRefusalOf(expected)) throw RefusedException(type)
                return reply
            } finally {
                synchronized(this) { if (pending?.second === replyWait) pending = null }
            }
        }

        fun close() {
            connected = false
            gatt?.let {
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            gatt = null
            BleLog.add(address, "closed")
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            BleLog.add(address, "onConnectionStateChange state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                connectWait.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                val err = AerException("Device disconnected")
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
            BleLog.add(address, "onMtuChanged mtu=$mtu status=$status")
            mtuWait?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            BleLog.add(address, "onCharacteristicWrite ${c.uuid} status=$status")
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
            BleLog.add(address, "notify $uuid ${value.toHex()}")
            val now = SystemClock.elapsedRealtime()
            lastActivity = now
            val buf = buffers.getOrPut(uuid) { ByteArrayOutputStream() }
            if (now - (lastData[uuid] ?: now) > FRAGMENT_TIMEOUT_MS) buf.reset()
            lastData[uuid] = now
            buf.write(value)
            if (buf.size() > MAX_REPLY_BYTES) {
                BleLog.add(address, "dropping oversized reply")
                buf.reset()
                return
            }
            val data = buf.toByteArray()
            val item = try {
                Cbor.decode(data).first
            } catch (_: Cbor.Incomplete) {
                return
            } catch (e: Cbor.Malformed) {
                BleLog.add(address, "bad reply ${data.toHex()}: ${e.message}")
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
        const val DISCOVER_TIMEOUT_MS = 5_000L
        const val REPLY_TIMEOUT_MS = 5_000L
        const val STATUS_DELAY_MS = 2_000L
        const val COMMAND_DELAY_MS = 500L
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
