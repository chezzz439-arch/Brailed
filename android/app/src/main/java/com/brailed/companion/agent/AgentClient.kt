package com.brailed.companion.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the Jac agent started with `jac start jac_backend/agent.jac`.
 * POSTs the instruction to the run_command walker and decodes the reported
 * Action. Kept dependency-free (HttpURLConnection + org.json) on purpose.
 */
object AgentClient {

    suspend fun runCommand(baseUrl: String, instruction: String): Action = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl/walker/run_command")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(JSONObject().put("instruction", instruction).toString().toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("HTTP $code: ${body.take(200)}")
            parse(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * jaclang's walker responses have varied a little across versions, so we
     * look for a "reports" array either at the top level or under "data", and
     * read the first report object.
     */
    private fun parse(body: String): Action {
        val root = JSONObject(body)
        val reports = root.optJSONArray("reports")
            ?: root.optJSONObject("data")?.optJSONArray("reports")
            ?: return Action.UNKNOWN
        if (reports.length() == 0) return Action.UNKNOWN
        val r = reports.getJSONObject(0)
        return Action(
            // Normalize "ActionType.OPEN_APP" -> "OPEN_APP".
            type = r.optString("type", "UNKNOWN").substringAfterLast('.'),
            appName = r.optString("app_name", ""),
            target = r.optString("target", ""),
            reason = r.optString("reason", ""),
        )
    }
}
