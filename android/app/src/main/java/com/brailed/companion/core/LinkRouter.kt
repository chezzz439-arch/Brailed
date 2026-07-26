package com.brailed.companion.core

import android.content.Context
import android.provider.Settings as AndroidSettings
import com.brailed.companion.a11y.BrailedAccessibilityService
import com.brailed.companion.agent.AgentClient
import com.brailed.companion.ble.DeviceMessage
import com.brailed.companion.ble.DeviceStatus
import com.brailed.companion.command.CommandExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How the router writes back to the device (captions / command results). */
interface LinkSink {
    fun sendCaption(text: String)
    fun sendCommandResult(text: String)
}

/**
 * Points at whichever transport is currently connected, so producers that don't
 * own the link (the accessibility service, the audio-caption service) can push
 * text to the device without knowing whether it's BLE or USB.
 */
object ActiveLink {
    @Volatile
    var sink: LinkSink? = null

    fun sendCaption(text: String) = sink?.sendCaption(text)
    fun sendCommandResult(text: String) = sink?.sendCommandResult(text)
}

/**
 * Transport-agnostic routing for device input. Lifted out of BleService so the
 * BLE and USB-serial services share one implementation: they parse bytes into
 * [DeviceMessage] / [DeviceStatus] (via BrailedProtocol) and hand them here.
 *
 * In TEXT mode characters go to the IME (or the accessibility fallback); in
 * COMMAND mode they accumulate and are POSTed to the Jac agent on SEND.
 */
class LinkRouter(
    private val appContext: Context,
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val sink: LinkSink,
) {
    private var currentMode: Mode = Mode.TEXT
    private val commandBuffer = StringBuilder()

    fun onDeviceMessage(msg: DeviceMessage) {
        when (msg) {
            is DeviceMessage.CharInput -> {
                if (msg.mode == Mode.COMMAND) commandBuffer.append(msg.ch) else routeTextChar(msg.ch)
            }

            is DeviceMessage.ControlInput -> when (msg.control) {
                Control.MODE_TOGGLE -> {
                    // MODE_CHANGE carries the new mode absolutely (msg.mode), so
                    // set rather than flip — stays consistent with Status
                    // regardless of arrival order.
                    if (msg.mode != currentMode) setMode(msg.mode)
                }

                Control.BACKSPACE -> {
                    if (currentMode == Mode.COMMAND) {
                        if (commandBuffer.isNotEmpty()) commandBuffer.setLength(commandBuffer.length - 1)
                    } else routeTextBackspace()
                }

                Control.SEND -> {
                    if (currentMode == Mode.COMMAND) {
                        dispatchCommand(commandBuffer.toString().trim())
                        commandBuffer.setLength(0)
                    } else routeTextSend()
                }
            }

            is DeviceMessage.Unknown -> Bus.log("Unparsed: ${msg.raw}")
        }
    }

    /** Sync mode from an authoritative Status notification (…0005). */
    fun onStatus(s: DeviceStatus) {
        if (s.mode != currentMode) {
            setMode(s.mode)
            Bus.log("Mode (from status): ${s.mode}")
        }
    }

    private fun setMode(mode: Mode) {
        currentMode = mode
        if (currentMode == Mode.TEXT) commandBuffer.setLength(0)
        Bus.setMode(currentMode)
        Bus.log("Mode: $currentMode")
    }

    // ---- Text-mode injection ---------------------------------------------
    // Preferred path is the Brailed keyboard (IME) when it is the active input
    // method; otherwise fall back to the AccessibilityService's set-text path.

    private fun brailedImeIsActive(): Boolean {
        val current = AndroidSettings.Secure.getString(
            appContext.contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD
        )
        return current?.startsWith(appContext.packageName) == true
    }

    private fun routeTextChar(c: Char) {
        if (brailedImeIsActive()) scope.launch { Bus.emitChar(c) }
        else if (BrailedAccessibilityService.instance?.injectText(c.toString()) != true) {
            Bus.log("No input target — enable the Brailed keyboard or accessibility service")
        }
    }

    private fun routeTextBackspace() {
        if (brailedImeIsActive()) scope.launch { Bus.emitControl(Control.BACKSPACE) }
        else BrailedAccessibilityService.instance?.deleteLastChar()
    }

    private fun routeTextSend() {
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
                    sink.sendCommandResult("Command failed: ${it.message}")
                    return@launch
                }
            Bus.log("Action: ${action.type} ${action.appName} ${action.target}".trim())
            val result = CommandExecutor.execute(appContext, action)
            sink.sendCommandResult(result)
        }
    }
}
