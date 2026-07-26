package com.brailed.companion.usb

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brailed.companion.BrailedApp
import com.brailed.companion.ble.BrailedProtocol
import com.brailed.companion.core.ActiveLink
import com.brailed.companion.core.Bus
import com.brailed.companion.core.LinkRouter
import com.brailed.companion.core.LinkSink
import com.brailed.companion.core.Settings
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Wired alternative to [com.brailed.companion.ble.BleService]: talks to the
 * ESP32 over USB serial (CP2102) instead of BLE, so the radio never powers up
 * and the device can't brown out (and the phone powers it over OTG).
 *
 * Same protocol (see /PROTOCOL.md), different pipe: it feeds decoded lines into
 * the shared [LinkRouter] and implements [LinkSink] for caption/command writes.
 * Auto-launches when the device is plugged in (see the manifest intent-filter).
 */
class UsbSerialService : Service(), LinkSink, SerialInputOutputManager.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: Settings
    private lateinit var router: LinkRouter
    private lateinit var usbManager: UsbManager

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val rxBuf = StringBuilder()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        usbManager = getSystemService(UsbManager::class.java)
        router = LinkRouter(applicationContext, settings, scope, this)
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notif("Looking for USB braille device…"))
        ActiveLink.sink = this
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        closePort()
        runCatching { unregisterReceiver(usbReceiver) }
        if (ActiveLink.sink === this) ActiveLink.sink = null
        scope.cancel()
        super.onDestroy()
    }

    // ---- Connect ----------------------------------------------------------

    private fun connect() {
        if (port != null) return // already connected; onResume/restart is idempotent
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
        if (driver == null) { Bus.log("No USB serial device found"); return }

        val device = driver.device
        if (!usbManager.hasPermission(device)) { requestPermission(device); return }

        val connection = usbManager.openDevice(device)
        if (connection == null) { Bus.log("Could not open USB device"); return }

        val p = driver.ports.firstOrNull() ?: run { Bus.log("USB driver has no ports"); return }
        val opened = runCatching {
            p.open(connection)
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Keep the ESP32 in run mode (a mis-set line state drops it into
            // reset/bootloader). Some drivers don't support these — ignore then.
            runCatching { p.setDTR(true) }
            runCatching { p.setRTS(true) }
        }.isSuccess
        if (!opened) { Bus.log("USB open failed"); runCatching { p.close() }; return }

        port = p
        ioManager = SerialInputOutputManager(p, this).also { it.start() }
        Bus.setConnected(true)
        Bus.log("USB serial connected")
        updateNotif("Connected (USB) to braille device")
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
        )
        usbManager.requestPermission(device, pi)
        Bus.log("Requesting USB permission…")
    }

    private fun closePort() {
        runCatching { ioManager?.stop() }
        runCatching { port?.close() }
        ioManager = null
        port = null
        Bus.setConnected(false)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
                    else Bus.log("USB permission denied")

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Bus.log("USB device detached")
                    closePort()
                    updateNotif("USB device detached")
                }
            }
        }
    }

    // ---- Inbound: SerialInputOutputManager.Listener -----------------------

    override fun onNewData(data: ByteArray) {
        rxBuf.append(String(data, Charsets.UTF_8))
        var nl = rxBuf.indexOf("\n")
        while (nl >= 0) {
            val line = rxBuf.substring(0, nl)
            rxBuf.delete(0, nl + 1)
            dispatchLine(line)
            nl = rxBuf.indexOf("\n")
        }
    }

    override fun onRunError(e: Exception) {
        Bus.log("USB read error: ${e.message}")
        Bus.setConnected(false)
    }

    private fun dispatchLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return   // logs
        BrailedProtocol.parseStatusLine(trimmed)?.let { router.onStatus(it); return }
        router.onDeviceMessage(BrailedProtocol.parseSerialLine(trimmed))
    }

    // ---- Outbound: LinkSink -----------------------------------------------

    override fun sendCaption(text: String) = write("CAP $text\n")
    override fun sendCommandResult(text: String) = write("CMD $text\n")

    private fun write(s: String) {
        runCatching { port?.write(s.toByteArray(Charsets.UTF_8), WRITE_TIMEOUT_MS) }
            .onFailure { Bus.log("USB write failed: ${it.message}") }
    }

    // ---- Notification -----------------------------------------------------

    private fun notif(text: String): Notification =
        NotificationCompat.Builder(this, BrailedApp.CHANNEL_ID)
            .setContentTitle("Brailed")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    private fun updateNotif(text: String) {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notif(text))
    }

    companion object {
        private const val NOTIF_ID = 3   // distinct from BleService (1) and AudioCapture (2)
        private const val WRITE_TIMEOUT_MS = 500
        private const val ACTION_USB_PERMISSION = "com.brailed.companion.USB_PERMISSION"
    }
}
