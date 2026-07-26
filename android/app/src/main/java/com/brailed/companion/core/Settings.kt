package com.brailed.companion.core

import android.content.Context

/** Thin wrapper over SharedPreferences for the one thing the user configures:
 *  where the Jac agent is reachable. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("brailed", Context.MODE_PRIVATE)

    var agentBaseUrl: String
        get() = prefs.getString(KEY_AGENT_URL, DEFAULT_AGENT_URL) ?: DEFAULT_AGENT_URL
        set(value) = prefs.edit().putString(KEY_AGENT_URL, value.trimEnd('/')).apply()

    companion object {
        private const val KEY_AGENT_URL = "agent_base_url"
        // Point this at the machine running `jac start jac_backend/agent.jac`.
        // For a phone on the same Wi-Fi, use the host's LAN IP, e.g. http://192.168.1.42:8000
        const val DEFAULT_AGENT_URL = "http://192.168.1.100:8000"
    }
}
