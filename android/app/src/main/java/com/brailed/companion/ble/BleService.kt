package com.brailed.companion.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import com.brailed.companion.BrailedApp
import com.brailed.companion.core.ActiveLink
import com.brailed.companion.core.Bus
import com.brailed.companion.core.LinkRouter
import com.brailed.companion.core.LinkSink
import com.brailed.companion.core.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID

/**
 * Foreground service that owns the BLE link to the PortableBraille device.
 *
 * It scans for the device, subscribes to TextInput notifications, and routes
 * what arrives: in TEXT mode characters go to the IME (via [Bus]); in COMMAND
 * mode they accumulate in a buffer that is POSTed to the Jac agent on SEND.
 * It also exposes [sendCaption] so the accessibility service can push what's
 * on screen back to the device's CaptionOutput characteristic.
 */
class BleService : Service(), LinkSink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var adapter: BluetoothAdapter
    private lateinit var settings: Settings
    private lateinit var router: LinkRouter

    private var gatt: BluetoothGatt? = null
    private var captionChar: BluetoothGattCharacteristic? = null
    private var commandResultChar: BluetoothGattCharacteristic? = null

    // Serial GATT write queue (BLE allows one outstanding write at a time).
    // Each entry carries its target characteristic so captions and command
    // results can share the one queue while going to different characteristics.
    private val writeQueue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private var writing = false

    // NOTIFY characteristics are subscribed one at a time (BLE allows one
    // descriptor write outstanding); onDescriptorWrite advances the queue.
    private val subscribeQueue = ArrayDeque<BluetoothGattCharacteristic>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        adapter = getSystemService(BluetoothManager::class.java).adapter
        router = LinkRouter(applicationContext, settings, scope, this)
        ActiveLink.sink = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Scanning for braille device…"))
        startScan()
        return START_STICKY
    }

    override fun onDestroy() {
        stopScanSafe()
        closeGatt()
        scope.cancel()
        if (ActiveLink.sink === this) ActiveLink.sink = null
        instance = null
        super.onDestroy()
    }

    // ---- Scanning ---------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = adapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            Bus.log("Scanning…")
        } catch (e: SecurityException) {
            Bus.log("Missing BLUETOOTH_SCAN permission")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanSafe() {
        try {
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name == DEVICE_NAME || result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true) {
                stopScanSafe()
                Bus.log("Found ${device.name ?: device.address}, connecting…")
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Bus.log("Scan failed: $errorCode")
        }
    }

    // ---- Connection -------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
        }
        gatt = null
    }

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Bus.setConnected(true)
                Bus.log("Connected; discovering services…")
                g.discoverServices()
                updateNotification("Connected to braille device")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Bus.setConnected(false)
                Bus.log("Disconnected; rescanning…")
                updateNotification("Reconnecting…")
                closeGatt()
                startScan()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID) ?: run {
                Bus.log("Service not found on device")
                return
            }
            captionChar = service.getCharacteristic(CAPTION_OUTPUT_UUID)
            commandResultChar = service.getCharacteristic(COMMAND_RESULT_UUID)

            // Subscribe to both NOTIFY characteristics, one CCCD write at a time.
            subscribeQueue.clear()
            service.getCharacteristic(TEXT_INPUT_UUID)?.let { subscribeQueue.add(it) }
            service.getCharacteristic(STATUS_UUID)?.let { subscribeQueue.add(it) }
            if (subscribeQueue.isEmpty()) { Bus.log("TextInput characteristic missing"); return }
            subscribeNext(g)
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val bytes = c.value ?: return
            when (c.uuid) {
                TEXT_INPUT_UUID -> router.onDeviceMessage(BrailedProtocol.parse(bytes))
                STATUS_UUID -> BrailedProtocol.parseStatus(bytes)?.let { router.onStatus(it) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            subscribeNext(g)  // advance to the next NOTIFY characteristic
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writing = false
            drainWriteQueue()
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeNext(g: BluetoothGatt) {
        val ch = subscribeQueue.poll() ?: return
        g.setCharacteristicNotification(ch, true)
        @Suppress("DEPRECATION")
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd == null) { subscribeNext(g); return }  // no CCCD → skip
        @Suppress("DEPRECATION")
        run {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }
        Bus.log("Subscribed to ${ch.uuid}")
    }

    // ---- Caption / result write-back (LinkSink) ---------------------------

    /** Live caption text (what the phone is saying) → CaptionOutput. */
    override fun sendCaption(text: String) = enqueueWrite(captionChar, text)

    /** Short command confirmation/error → CommandResult (master prompt §8). */
    override fun sendCommandResult(text: String) = enqueueWrite(commandResultChar, text)

    private fun enqueueWrite(target: BluetoothGattCharacteristic?, text: String) {
        val char = target ?: return
        // Chunk into <=20 byte payloads (default ATT MTU) and queue them.
        text.toByteArray().toList().chunked(20).forEach { chunk ->
            writeQueue.add(char to chunk.toByteArray())
        }
        drainWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writing) return
        val (char, bytes) = writeQueue.poll() ?: return
        writing = true
        @Suppress("DEPRECATION")
        run {
            char.value = bytes
            gatt?.writeCharacteristic(char)
        }
    }

    // ---- Notification -----------------------------------------------------

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, BrailedApp.CHANNEL_ID)
            .setContentTitle("Brailed")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        // Canonical Nordic base UUID — must match firmware/src/main.cpp and /PROTOCOL.md.
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val TEXT_INPUT_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val CAPTION_OUTPUT_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val COMMAND_RESULT_UUID: UUID = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e")
        val STATUS_UUID: UUID = UUID.fromString("6e400005-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val DEVICE_NAME = "PortableBraille"

        private const val NOTIF_ID = 1

        /** Set while the service is running so the accessibility service can
         *  push captions without binding. */
        @Volatile
        var instance: BleService? = null
            private set
    }
}
