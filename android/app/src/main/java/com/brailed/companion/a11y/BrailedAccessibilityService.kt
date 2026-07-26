package com.brailed.companion.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.brailed.companion.ble.BleService
import com.brailed.companion.core.Bus

/**
 * The "captions" half. Android lets an accessibility service observe the text
 * of other apps' windows and spoken announcements — that's what we forward to
 * the device so the user can read, in braille, what the phone is doing.
 *
 * It also exposes global navigation actions (home / notifications) that command
 * mode uses. This is the piece that has no equivalent on iOS.
 */
class BrailedAccessibilityService : AccessibilityService() {

    private var lastCaption: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Bus.log("Captions service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val text = event.text.joinToString(" ").trim()
        if (text.isBlank() || text == lastCaption) return

        val source = when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "Notification"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> event.packageName?.toString() ?: "Screen"
            else -> "Screen"
        }
        lastCaption = text
        val caption = "[$source] $text"
        Bus.log(caption)
        BleService.instance?.sendCaption(caption)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ---- Text injection (used when the Brailed keyboard isn't active) ------
    // The master prompt (§10/§11) names an AccessibilityService as Android's
    // text-injection mechanism. We drive the focused editable node with
    // ACTION_SET_TEXT, replacing its content with the new full string.

    /** Append [text] to the focused editable field. Returns false if none. */
    fun injectText(text: String): Boolean = withFocusedEditable { node ->
        setNodeText(node, (node.text?.toString() ?: "") + text)
    }

    /** Delete the last character of the focused editable field. */
    fun deleteLastChar(): Boolean = withFocusedEditable { node ->
        val current = node.text?.toString() ?: ""
        if (current.isEmpty()) true else setNodeText(node, current.dropLast(1))
    }

    private inline fun withFocusedEditable(block: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return try {
            if (!node.isEditable) false else block(node)
        } finally {
            @Suppress("DEPRECATION") node.recycle()
        }
    }

    private fun setNodeText(node: AccessibilityNodeInfo, newText: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        // Keep the caret at the end so the next character appends correctly.
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return ok
    }

    companion object {
        @Volatile
        var instance: BrailedAccessibilityService? = null
            private set
    }
}
