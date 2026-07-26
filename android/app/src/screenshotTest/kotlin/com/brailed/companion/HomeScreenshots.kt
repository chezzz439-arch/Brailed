package com.brailed.companion

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/**
 * Preview screenshot tests. `./gradlew updateDebugScreenshotTest` renders these
 * to reference PNGs under src/debug/screenshotTest/reference/ — a device-free
 * way to see the UI.
 */

private val sampleLog = listOf(
    "Scanning…",
    "Found PortableBraille, connecting…",
    "Connected; discovering services…",
    "Subscribed to TextInput",
    "Mode: COMMAND",
    "Command: \"open messages\"",
    "Action: OPEN_APP Messages",
    "caption: ▶ audio playing",
)

@Preview(showBackground = true, widthDp = 411, heightDp = 940)
@Composable
fun ConnectedScreenshot() {
    BrailedTheme {
        HomeContent(
            connected = true,
            mode = "COMMAND",
            log = sampleLog,
            agentUrl = "http://192.168.1.42:8000",
            onAgentUrlChange = {},
            actions = HomeActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 940)
@Composable
fun DisconnectedScreenshot() {
    BrailedTheme {
        HomeContent(
            connected = false,
            mode = "TEXT",
            log = emptyList(),
            agentUrl = "http://192.168.1.100:8000",
            onAgentUrlChange = {},
            actions = HomeActions(),
        )
    }
}
