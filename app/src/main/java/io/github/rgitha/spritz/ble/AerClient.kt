package io.github.rgitha.spritz.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
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

    /** Replaces every schedule on the device, as the official app does. */
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
            val l = link?.takeIf { it.connected } ?: connect().also { link = it }
            try {
                block(l)
            } catch (e: Exception) {
                if (!l.connected) drop()
                throw e
            }
        } finally {
            disconnectJob = scope.launch {
                delay(IDLE_DISCONNECT_MS)
                opLock.withLock { drop() }
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

        private var connectWait = CompletableDeferred<Unit>()
        private var discoverWait = CompletableDeferred<Unit>()
        private var descriptorWait: CompletableDeferred<Unit>? = null
        private var writeWait: CompletableDeferred<Int>? = null

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
            val notify = service.getCharacteristic(AerUuids.NOTIFY)
                ?: throw AerException("Notify characteristic missing")
            if (!g.setCharacteristicNotification(notify, true)) throw AerException("Couldn't subscribe")
            // The aer has no CCCD here; enable through the descriptor only if one exists.
            notify.getDescriptor(AerUuids.CCCD)?.let { d ->
                val wait = CompletableDeferred<Unit>().also { descriptorWait = it }
                writeDescriptor(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                withTimeoutOrNull(2000) { wait.await() }
            }
            connectedAt = SystemClock.elapsedRealtime()
            Log.d(TAG, "$address connected")
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
                Log.d(TAG, "→ ${frame.toHex()}")
                if (!writeCharacteristic(g, c, frame)) throw AerException("Write failed")
                val status = withTimeoutOrNull(REPLY_TIMEOUT_MS) { written.await() }
                    ?: throw AerException("Write timed out")
                if (status != BluetoothGatt.GATT_SUCCESS) throw AerException("Write failed ($status)")
                val reply = withTimeoutOrNull(REPLY_TIMEOUT_MS) { replyWait.await() }
                    ?: throw AerException(if (connected) "No reply from the device" else "Device disconnected")
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
            Log.d(TAG, "$address closed")
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
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

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorWait?.complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeWait?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onData(c.uuid, value)
        }

        @Deprecated("Used before Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                onData(c.uuid, c.value ?: return)
            }
        }

        /** Reassembles fragmented replies until a whole CBOR item decodes. */
        private fun onData(uuid: UUID, value: ByteArray) {
            val now = SystemClock.elapsedRealtime()
            val buf = buffers.getOrPut(uuid) { ByteArrayOutputStream() }
            if (now - (lastData[uuid] ?: now) > FRAGMENT_TIMEOUT_MS) buf.reset()
            lastData[uuid] = now
            buf.write(value)
            if (buf.size() > MAX_REPLY_BYTES) {
                Log.w(TAG, "dropping oversized reply")
                buf.reset()
                return
            }
            val data = buf.toByteArray()
            val item = try {
                Cbor.decode(data).first
            } catch (_: Cbor.Incomplete) {
                return
            } catch (e: Cbor.Malformed) {
                Log.w(TAG, "bad reply ${data.toHex()}: ${e.message}")
                buf.reset()
                return
            }
            buf.reset()
            Log.d(TAG, "← ${data.toHex()}")
            val reply = Reply.parse(item) ?: return
            synchronized(this) {
                val (expected, wait) = pending ?: return
                if (reply.type == expected || reply.isRefusalOf(expected)) wait.complete(reply)
            }
        }

        private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray): Boolean {
            val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, v, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = type; c.value = v; g.writeCharacteristic(c) }
            }
        }

        private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, v)
            } else {
                @Suppress("DEPRECATION")
                run { d.value = v; g.writeDescriptor(d) }
            }
        }
    }

    companion object {
        private const val TAG = "AerClient"
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
