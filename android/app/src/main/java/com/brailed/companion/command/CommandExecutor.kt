package com.brailed.companion.command

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import com.brailed.companion.a11y.BrailedAccessibilityService
import com.brailed.companion.agent.Action

/**
 * Turns an [Action] from the agent into an actual Android effect. App launches
 * go through Intents; navigation actions (home, notifications) go through the
 * accessibility service's global actions.
 *
 * Returns a short human-readable caption describing what happened, which the
 * caller sends back to the device so the user gets feedback.
 */
object CommandExecutor {

    // Second line of defence after the agent's server-side allow-list: the app
    // only knows how to launch these. Anything else falls through to "can't do".
    private val APP_PACKAGES = mapOf(
        "instagram" to "com.instagram.android",
        "messages" to "com.google.android.apps.messaging",
    )

    fun execute(context: Context, action: Action): String {
        return when (action.type.uppercase()) {
            "OPEN_APP" -> openApp(context, action.appName)
            "GO_HOME" -> {
                val ok = BrailedAccessibilityService.instance?.goHome() ?: false
                if (ok) "Going home" else "Enable the Brailed accessibility service to navigate"
            }
            "READ_NOTIFICATIONS" -> {
                val ok = BrailedAccessibilityService.instance?.openNotifications() ?: false
                if (ok) "Opening notifications" else "Enable the Brailed accessibility service to read notifications"
            }
            else -> action.reason.ifBlank { "Sorry, I couldn't do that" }
        }
    }

    private fun openApp(context: Context, appName: String): String {
        val name = appName.trim()
        if (name.isBlank()) return "Which app?"

        // A few apps are reachable without a known package.
        when (name.lowercase()) {
            "settings" -> return launch(context, Intent(Settings.ACTION_SETTINGS), "Settings")
            "camera" -> return launch(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Camera")
            "phone" -> return launch(context, Intent(Intent.ACTION_DIAL), "Phone")
        }

        val pkg = APP_PACKAGES[name.lowercase()] ?: return "$name isn't set up on this phone"
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "$name doesn't seem to be installed"
        return launch(context, intent, name)
    }

    private fun launch(context: Context, intent: Intent, label: String): String {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Opening $label"
        } catch (e: Exception) {
            "Couldn't open $label: ${e.message}"
        }
    }
}
