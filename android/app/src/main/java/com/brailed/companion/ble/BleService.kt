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
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationCompat
import com.brailed.companion.BrailedApp
import com.brailed.companion.a11y.BrailedAccessibilityService
import com.brailed.companion.agent.AgentClient
import com.brailed.companion.command.CommandExecutor
import com.brailed.companion.core.Bus
import com.brailed.companion.core.Control
import com.brailed.companion.core.Mode
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
class BleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var adapter: BluetoothAdapter
    private lateinit var settings: Settings

    private var gatt: BluetoothGatt? = null
    private var captionChar: BluetoothGattCharacteristic? = null
    private var commandResultChar: BluetoothGattCharacteristic? = null

    private var currentMode: Mode = Mode.TEXT
    private val commandBuffer = StringBuilder()

    // Serial GATT write queue (BLE allows one outstanding write at a time).
    // Each entry carries its target characteristic so captions and command
    // results can share the one queue while going to different characteristics.
    private val writeQueue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private var writing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        adapter = getSystemService(BluetoothManager::class.java).adapter
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

            val textInput = service.getCharacteristic(TEXT_INPUT_UUID) ?: return
            g.setCharacteristicNotification(textInput, true)
            @Suppress("DEPRECATION")
            textInput.getDescriptor(CCCD_UUID)?.let { cccd ->
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
            Bus.log("Subscribed to TextInput")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid != TEXT_INPUT_UUID) return
            val raw = c.value?.let { String(it) } ?: return
            handleMessage(raw)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writing = false
            drainWriteQueue()
        }
    }

    // ---- Message routing --------------------------------------------------

    private fun handleMessage(raw: String) {
        when (val msg = BrailedProtocol.parse(raw)) {
            is DeviceMessage.CharInput -> {
                if (msg.mode == Mode.COMMAND) {
                    commandBuffer.append(msg.ch)
                } else {
                    routeTextChar(msg.ch)
                }
            }

            is DeviceMessage.ControlInput -> when (msg.control) {
                Control.MODE_TOGGLE -> {
                    currentMode = if (currentMode == Mode.TEXT) Mode.COMMAND else Mode.TEXT
                    if (currentMode == Mode.TEXT) commandBuffer.setLength(0)
                    Bus.setMode(currentMode)
                    Bus.log("Mode: $currentMode")
                }

                Control.BACKSPACE -> {
                    if (currentMode == Mode.COMMAND) {
                        if (commandBuffer.isNotEmpty()) commandBuffer.setLength(commandBuffer.length - 1)
                    } else {
                        routeTextBackspace()
                    }
                }

                Control.SEND -> {
                    if (currentMode == Mode.COMMAND) {
                        dispatchCommand(commandBuffer.toString().trim())
                        commandBuffer.setLength(0)
                    } else {
                        routeTextSend()
                    }
                }
            }

            is DeviceMessage.Unknown -> Bus.log("Unparsed: ${msg.raw}")
        }
    }

    // ---- Text-mode injection strategy -------------------------------------
    // Preferred path is the Brailed keyboard (IME) when it is the active input
    // method. When it isn't, we fall back to the AccessibilityService's
    // ACTION_SET_TEXT path (the mechanism named in the master prompt §10/§11),
    // so typing still works without forcing a keyboard switch.

    private fun brailedImeIsActive(): Boolean {
        val current = AndroidSettings.Secure.getString(
            contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD
        )
        return current?.startsWith(packageName) == true
    }

    private fun routeTextChar(c: Char) {
        if (brailedImeIsActive()) {
            scope.launch { Bus.emitChar(c) }
        } else if (BrailedAccessibilityService.instance?.injectText(c.toString()) != true) {
            Bus.log("No input target — enable the Brailed keyboard or accessibility service")
        }
    }

    private fun routeTextBackspace() {
        if (brailedImeIsActive()) scope.launch { Bus.emitControl(Control.BACKSPACE) }
        else BrailedAccessibilityService.instance?.deleteLastChar()
    }

    private fun routeTextSend() {
        // IME turns this into the field's editor action (Enter/Done/Send);
        // the accessibility fallback just appends a newline.
        if (brailedImeIsActive()) scope.launch { Bus.emitControl(Control.SEND) }
        else BrailedAccessibilityService.instance?.injectText("\n")
    }

    private fun dispatchCommand(instruction: String) {
        if (instruction.isBlank()) return
        Bus.log("Command: \"$instruction\"")
        scope.launch {
            val action = runCatching { AgentClient.runCommand(settings.agentBaseUrl, instruction) }
                .getOrElse {
                    Bus.log("Agent error: ${it.message}")
                    sendCommandResult("Command failed: ${it.message}")
                    return@launch
                }
            Bus.log("Action: ${action.type} ${action.appName} ${action.target}".trim())
            val result = CommandExecutor.execute(this@BleService, action)
            sendCommandResult(result)
        }
    }

    // ---- Caption / result write-back --------------------------------------

    /** Live caption text (what the phone is saying) → CaptionOutput. */
    fun sendCaption(text: String) = enqueueWrite(captionChar, text)

    /** Short command confirmation/error → CommandResult (master prompt §8). */
    fun sendCommandResult(text: String) = enqueueWrite(commandResultChar, text)

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
        // Must match firmware/portable_braille.ino.
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-0000-1000-8000-00805f9b34fb")
        val TEXT_INPUT_UUID: UUID = UUID.fromString("6e400002-0000-1000-8000-00805f9b34fb")
        val CAPTION_OUTPUT_UUID: UUID = UUID.fromString("6e400003-0000-1000-8000-00805f9b34fb")
        val COMMAND_RESULT_UUID: UUID = UUID.fromString("6e400004-0000-1000-8000-00805f9b34fb")
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
