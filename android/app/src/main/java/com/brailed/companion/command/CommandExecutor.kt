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

    // Known apps → package. Second line of defence after the agent's allow-list.
    // Matched by substring so "instagram", "insta", "open instagram" all hit.
    private val APP_PACKAGES = linkedMapOf(
        "instagram" to "com.instagram.android",
        "whatsapp" to "com.whatsapp",
        "messages" to "com.google.android.apps.messaging",
        "youtube" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome",
        "spotify" to "com.spotify.music",
        "tiktok" to "com.zhiliaoapp.musically",
        "snapchat" to "com.snapchat.android",
        "facebook" to "com.facebook.katana",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
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
        val name = appName.lowercase().trim()
        if (name.isBlank()) return "Which app?"

        // System destinations reachable without a package lookup.
        when {
            "setting" in name -> return launch(context, Intent(Settings.ACTION_SETTINGS), "Settings")
            "camera" in name -> return launch(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Camera")
            name == "phone" || "dial" in name || "call" in name ->
                return launch(context, Intent(Intent.ACTION_DIAL), "Phone")
        }

        // Fuzzy match: the agent's app_name may be "Instagram", "insta", etc.
        val match = APP_PACKAGES.entries.firstOrNull { name.contains(it.key) }
            ?: return "I don't know the app \"$appName\""

        val intent = context.packageManager.getLaunchIntentForPackage(match.value)
        // Launch the installed app directly — never bounce to the store.
        return if (intent != null) launch(context, intent, match.key)
        else "${match.key} isn't installed on this phone"
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
