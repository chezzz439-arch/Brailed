package com.brailed.companion.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
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

    companion object {
        @Volatile
        var instance: BrailedAccessibilityService? = null
            private set
    }
}
