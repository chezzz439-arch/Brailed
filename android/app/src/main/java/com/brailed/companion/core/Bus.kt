package com.brailed.companion.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-process event bus shared by the three components that make up the app:
 * the BLE service (produces input), the IME (consumes characters), and the
 * accessibility service (produces captions). They all live in the same process,
 * so a singleton with flows is the simplest coupling that works.
 */
object Bus {
    // Characters to type into the currently focused field (text mode only).
    private val _chars = MutableSharedFlow<Char>(extraBufferCapacity = 128)
    val chars: SharedFlow<Char> = _chars

    // Control events (currently only text-mode BACKSPACE reaches the IME).
    private val _control = MutableSharedFlow<Control>(extraBufferCapacity = 32)
    val control: SharedFlow<Control> = _control

    // Connection + mode state for the UI to observe.
    val status = MutableStateFlow(Status())

    // Rolling activity log for the UI (most recent last, capped).
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    suspend fun emitChar(c: Char) = _chars.emit(c)
    suspend fun emitControl(e: Control) = _control.emit(e)

    fun setConnected(connected: Boolean) {
        status.value = status.value.copy(connected = connected)
    }

    fun setMode(mode: Mode) {
        status.value = status.value.copy(mode = mode)
    }

    fun log(line: String) {
        _log.value = (_log.value + line).takeLast(200)
    }
}

enum class Mode { TEXT, COMMAND }

enum class Control { SEND, BACKSPACE, MODE_TOGGLE }

data class Status(
    val connected: Boolean = false,
    val mode: Mode = Mode.TEXT,
)
