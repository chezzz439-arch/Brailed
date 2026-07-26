package com.brailed.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.hardware.usb.UsbManager
import com.brailed.companion.ble.BleService
import com.brailed.companion.capture.AudioCaptureService
import com.brailed.companion.core.Bus
import com.brailed.companion.core.Settings
import com.brailed.companion.usb.UsbSerialService
import com.hoho.android.usbserial.driver.UsbSerialProber

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BrailedTheme { HomeScreen() } }
        maybeStartUsb(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeStartUsb(intent)
    }

    override fun onResume() {
        super.onResume()
        // Catch the common case: the device is already plugged in when the app
        // is opened normally (no USB_DEVICE_ATTACHED launch intent). Without
        // this, a device that was connected before launch never enumerates.
        maybeStartUsb(null)
    }

    /**
     * Start the wired transport when a supported serial adapter is present —
     * either because plugging it in launched us (USB_DEVICE_ATTACHED), or
     * because one is already connected. The default prober's device table is
     * the source of truth (CP2102, CH340, …); if it finds a driver, connect.
     */
    private fun maybeStartUsb(intent: Intent?) {
        val attached = intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        val present = runCatching {
            UsbSerialProber.getDefaultProber()
                .findAllDrivers(getSystemService(UsbManager::class.java))
                .isNotEmpty()
        }.getOrDefault(false)
        if (attached || present) {
            ContextCompat.startForegroundService(this, Intent(this, UsbSerialService::class.java))
        }
    }
}

// ---- Theme ---------------------------------------------------------------

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E4BE0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF001259),
    surface = Color(0xFFFDFBFF),
    background = Color(0xFFF4F4FB),
    surfaceVariant = Color(0xFFE3E1EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7C3FF),
    onPrimary = Color(0xFF06218C),
    primaryContainer = Color(0xFF2439A6),
    onPrimaryContainer = Color(0xFFDDE1FF),
)

@Composable
internal fun BrailedTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

// ---- Screen --------------------------------------------------------------

private fun requiredPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    add(Manifest.permission.RECORD_AUDIO) // for playback-capture captions
}.toTypedArray()

/** The button actions, hoisted so [HomeContent] stays stateless and previewable. */
internal class HomeActions(
    val onGrantPermissions: () -> Unit = {},
    val onStartScan: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onEnableKeyboard: () -> Unit = {},
    val onSwitchKeyboard: () -> Unit = {},
    val onEnableAccessibility: () -> Unit = {},
    val onSaveAgentUrl: () -> Unit = {},
    val onStartCaptions: () -> Unit = {},
    val onStopCaptions: () -> Unit = {},
)

/** Thin wiring layer: pulls live state + platform actions and hands them to
 *  the stateless [HomeContent]. Not previewable (needs a real Activity). */
@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val status by Bus.status.collectAsState()
    val log by Bus.log.collectAsState()
    var agentUrl by remember { mutableStateOf(settings.agentBaseUrl) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* user can retry from the same button */ }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, AudioCaptureService::class.java).apply {
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    HomeContent(
        connected = status.connected,
        mode = status.mode.name,
        log = log,
        agentUrl = agentUrl,
        onAgentUrlChange = { agentUrl = it },
        actions = HomeActions(
            onGrantPermissions = { permLauncher.launch(requiredPermissions()) },
            onStartScan = {
                ContextCompat.startForegroundService(context, Intent(context, BleService::class.java))
            },
            onStop = { context.stopService(Intent(context, BleService::class.java)) },
            onEnableKeyboard = {
                context.startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
            },
            onSwitchKeyboard = {
                context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            },
            onEnableAccessibility = {
                context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onSaveAgentUrl = { settings.agentBaseUrl = agentUrl },
            onStartCaptions = {
                val mpm = context.getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            },
            onStopCaptions = {
                context.stopService(Intent(context, AudioCaptureService::class.java))
            },
        ),
    )
}

/** Stateless UI — everything it needs is passed in, so `@Preview` can render it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeContent(
    connected: Boolean,
    mode: String,
    log: List<String>,
    agentUrl: String,
    onAgentUrlChange: (String) -> Unit,
    actions: HomeActions,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = {
                Text("Brailed", fontWeight = FontWeight.SemiBold)
            })
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusHero(connected = connected, mode = mode)

            StepCard(1, "Connect to the device") {
                PrimaryButton("Grant permissions", actions.onGrantPermissions)
                PrimaryButton("Start / scan for device", actions.onStartScan)
                TonalButton("Stop", actions.onStop)
            }

            StepCard(2, "Enable typing & control") {
                TonalButton("Enable Brailed keyboard", actions.onEnableKeyboard)
                TonalButton("Switch to Brailed keyboard", actions.onSwitchKeyboard)
                TonalButton("Enable captions & control", actions.onEnableAccessibility)
            }

            StepCard(3, "Jac agent endpoint") {
                OutlinedTextField(
                    value = agentUrl,
                    onValueChange = onAgentUrlChange,
                    label = { Text("Agent base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton("Save", actions.onSaveAgentUrl)
            }

            StepCard(4, "Live captions (phone audio)") {
                PrimaryButton("Start live captions", actions.onStartCaptions)
                TonalButton("Stop captions", actions.onStopCaptions)
                Hint(
                    "Captions media/video audio (Android 10+). Call audio usually can't be " +
                        "captured, and speech-to-text is a stub — see android/README.md."
                )
            }

            LogCard(log)
        }
    }
}

// ---- Previews (Android Studio: open the split/design pane) ---------------

private val SAMPLE_LOG = listOf(
    "Scanning…",
    "Found PortableBraille, connecting…",
    "Connected; discovering services…",
    "Subscribed to TextInput",
    "Mode: COMMAND",
    "Command: \"open messages\"",
    "Action: OPEN_APP Messages ",
    "caption: ▶ audio playing — no speech-to-text engine configured",
)

@Preview(name = "Connected", showBackground = true, showSystemUi = true)
@Composable
private fun HomeContentConnectedPreview() {
    BrailedTheme {
        HomeContent(
            connected = true,
            mode = "COMMAND",
            log = SAMPLE_LOG,
            agentUrl = "http://192.168.1.42:8000",
            onAgentUrlChange = {},
            actions = HomeActions(),
        )
    }
}

@Preview(name = "Disconnected", showBackground = true, showSystemUi = true)
@Composable
private fun HomeContentDisconnectedPreview() {
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

// ---- Components ----------------------------------------------------------

@Composable
private fun StatusHero(connected: Boolean, mode: String) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) cs.primaryContainer else cs.surfaceVariant
        ),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (connected) Color(0xFF1E8E3E) else Color(0xFF9AA0A6))
            )
            Column(Modifier.weight(1f)) {
                Text(
                    if (connected) "Connected" else "Not connected",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (connected) "Braille device ready" else "Start scanning to connect your device",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (connected) Pill(mode)
        }
    }
}

@Composable
private fun Pill(text: String) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun StepCard(number: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$number",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun TonalButton(text: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LogCard(log: List<String>) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            if (log.isEmpty()) {
                Hint("Nothing yet — connect the device to see input and commands here.")
            } else {
                log.takeLast(40).reversed().forEach {
                    Text(
                        it,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
