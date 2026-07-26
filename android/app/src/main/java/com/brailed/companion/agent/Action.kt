package com.brailed.companion.agent

/**
 * The structured action returned by the Jac agent's `run_command` walker.
 * Mirrors the `Action` object in jac_backend/agent.jac.
 */
data class Action(
    val type: String,      // OPEN_APP | GO_HOME | READ_NOTIFICATIONS | UNKNOWN
    val appName: String = "",
    val target: String = "",
    val reason: String = "",
) {
    companion object {
        val UNKNOWN = Action(type = "UNKNOWN")
    }
}
