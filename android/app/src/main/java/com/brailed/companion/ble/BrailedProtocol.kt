package com.brailed.companion.ble

import com.brailed.companion.core.Control
import com.brailed.companion.core.Mode

/**
 * Parses the wire protocol the ESP32 firmware emits over the TextInput
 * characteristic (see firmware/portable_braille.ino):
 *
 *   "CHAR:<mode>:<char>"   one decoded character  (mode = TEXT | COMMAND)
 *   "CTRL:<mode>:<event>"  event = SEND | BACKSPACE | MODE_TOGGLE
 *
 * The character payload can be a literal space, so we split with a limit of 3
 * and never trim it.
 */
sealed interface DeviceMessage {
    data class CharInput(val mode: Mode, val ch: Char) : DeviceMessage
    data class ControlInput(val mode: Mode, val control: Control) : DeviceMessage
    data class Unknown(val raw: String) : DeviceMessage
}

object BrailedProtocol {

    fun parse(raw: String): DeviceMessage {
        val parts = raw.split(":", limit = 3)
        if (parts.size < 3) return DeviceMessage.Unknown(raw)

        val mode = if (parts[1] == "COMMAND") Mode.COMMAND else Mode.TEXT

        return when (parts[0]) {
            "CHAR" -> DeviceMessage.CharInput(mode, parts[2].firstOrNull() ?: ' ')
            "CTRL" -> {
                val control = when (parts[2]) {
                    "SEND" -> Control.SEND
                    "BACKSPACE" -> Control.BACKSPACE
                    "MODE_TOGGLE" -> Control.MODE_TOGGLE
                    else -> return DeviceMessage.Unknown(raw)
                }
                DeviceMessage.ControlInput(mode, control)
            }
            else -> DeviceMessage.Unknown(raw)
        }
    }
}
