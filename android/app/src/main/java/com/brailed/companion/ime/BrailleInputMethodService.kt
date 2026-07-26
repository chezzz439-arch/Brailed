package com.brailed.companion.ime

import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.brailed.companion.core.Bus
import com.brailed.companion.core.Control
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The "keyboard". There are no on-screen keys — input arrives from the braille
 * device over BLE and reaches us through [Bus]. When this IME is the active
 * keyboard, we commit those characters into whatever text field has focus.
 *
 * The user still has to select "Brailed Braille Keyboard" as their input method
 * (and enable it in Settings first); this service just needs to be running.
 */
class BrailleInputMethodService : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        // Type characters as they arrive in TEXT mode.
        scope.launch {
            Bus.chars.collect { c ->
                currentInputConnection?.commitText(c.toString(), 1)
            }
        }
        // Handle text-mode backspace.
        scope.launch {
            Bus.control.collect { control ->
                if (control == Control.BACKSPACE) {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        // Minimal status strip so the IME has a visible surface.
        return TextView(this).apply {
            text = "⠿ Brailed — type on your braille device"
            gravity = Gravity.CENTER
            setPadding(32, 48, 32, 48)
            textSize = 16f
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
