package com.brailed.companion.ble

import com.brailed.companion.core.Control
import com.brailed.companion.core.Mode

/**
 * Canonical Brailed wire protocol — see /PROTOCOL.md (repo root).
 *
 * The device emits a 3-byte TextInput packet [type][mode][payload] over BLE,
 * and the same packet as an ASCII "TXI <type> <mode> <payload>" line over USB
 * serial. Both are parsed here into the transport-agnostic [DeviceMessage] /
 * [DeviceStatus] types, so BleService and UsbSerialService share one parser.
 */
sealed interface DeviceMessage {
    data class CharInput(val mode: Mode, val ch: Char) : DeviceMessage
    data class ControlInput(val mode: Mode, val control: Control) : DeviceMessage
    data class Unknown(val raw: String) : DeviceMessage
}

/** Status (…0005 / "STA") — advisory device state. */
data class DeviceStatus(val connected: Boolean, val mode: Mode)

object BrailedProtocol {

    // Packet type bytes — MUST match firmware/src/main.cpp.
    const val PKT_CHAR = 0x01
    const val PKT_SEND = 0x02
    const val PKT_BACKSPACE = 0x03
    const val PKT_MODE_CHANGE = 0x04

    const val MODE_TEXT = 0x00
    const val MODE_COMMAND = 0x01

    private fun modeOf(b: Int): Mode = if (b == MODE_COMMAND) Mode.COMMAND else Mode.TEXT

    /** BLE TextInput: 3-byte [type][mode][payload]. */
    fun parse(bytes: ByteArray): DeviceMessage {
        if (bytes.size < 3) return DeviceMessage.Unknown(bytes.toHex())
        val type = bytes[0].toInt() and 0xFF
        val mode = modeOf(bytes[1].toInt() and 0xFF)
        val payload = bytes[2].toInt() and 0xFF
        return when (type) {
            PKT_CHAR -> DeviceMessage.CharInput(mode, payload.toChar())
            PKT_SEND -> DeviceMessage.ControlInput(mode, Control.SEND)
            PKT_BACKSPACE -> DeviceMessage.ControlInput(mode, Control.BACKSPACE)
            PKT_MODE_CHANGE -> DeviceMessage.ControlInput(mode, Control.MODE_TOGGLE)
            else -> DeviceMessage.Unknown(bytes.toHex())   // unknown type → ignore upstream
        }
    }

    /** BLE Status: 2-byte [state][mode]. Null if malformed. */
    fun parseStatus(bytes: ByteArray): DeviceStatus? {
        if (bytes.size < 2) return null
        return DeviceStatus(
            connected = (bytes[0].toInt() and 0xFF) != 0,
            mode = modeOf(bytes[1].toInt() and 0xFF),
        )
    }

    /**
     * USB-serial line. Returns a [DeviceMessage] for "TXI …" lines; everything
     * else (STA, "#…" logs, blanks) → [DeviceMessage.Unknown] so callers ignore
     * it. Status from serial is parsed separately via [parseStatusLine].
     */
    fun parseSerialLine(line: String): DeviceMessage {
        val p = line.trim().split(" ")
        if (p.size < 4 || p[0] != "TXI") return DeviceMessage.Unknown(line)
        return runCatching {
            parse(
                byteArrayOf(
                    p[1].toInt(16).toByte(),
                    p[2].toInt(16).toByte(),
                    p[3].toInt(16).toByte(),
                )
            )
        }.getOrElse { DeviceMessage.Unknown(line) }
    }

    /** USB-serial "STA <state> <mode>" → [DeviceStatus], or null. */
    fun parseStatusLine(line: String): DeviceStatus? {
        val p = line.trim().split(" ")
        if (p.size < 3 || p[0] != "STA") return null
        return runCatching {
            parseStatus(byteArrayOf(p[1].toInt(16).toByte(), p[2].toInt(16).toByte()))
        }.getOrNull()
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
}
