package com.brailed.companion

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.brailed.companion.ble.BleService
import com.brailed.companion.core.Bus
import com.brailed.companion.core.Settings

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { HomeScreen() }
            }
        }
    }
}

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
}.toTypedArray()

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val status by Bus.status.collectAsState()
    val log by Bus.log.collectAsState()
    var agentUrl by remember { mutableStateOf(settings.agentBaseUrl) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled implicitly; user can retry */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Brailed", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (status.connected) "● Connected — mode: ${status.mode}" else "○ Not connected",
            style = MaterialTheme.typography.titleMedium
        )

        // 1. Permissions + connection
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Connect to the device", style = MaterialTheme.typography.titleSmall)
                Button(onClick = { permLauncher.launch(requiredPermissions()) }, Modifier.fillMaxWidth()) {
                    Text("Grant Bluetooth permissions")
                }
                Button(
                    onClick = {
                        val intent = Intent(context, BleService::class.java)
                        ContextCompat.startForegroundService(context, intent)
                    },
                    Modifier.fillMaxWidth()
                ) { Text("Start / scan for device") }
                OutlinedButton(
                    onClick = { context.stopService(Intent(context, BleService::class.java)) },
                    Modifier.fillMaxWidth()
                ) { Text("Stop") }
            }
        }

        // 2. Enable the keyboard + captions
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. Enable the OS integrations", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(
                    onClick = { context.startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS)) },
                    Modifier.fillMaxWidth()
                ) { Text("Enable Brailed keyboard") }
                OutlinedButton(
                    onClick = {
                        context.getSystemService(InputMethodManager::class.java)
                            .showInputMethodPicker()
                    },
                    Modifier.fillMaxWidth()
                ) { Text("Switch to Brailed keyboard") }
                OutlinedButton(
                    onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    Modifier.fillMaxWidth()
                ) { Text("Enable captions & control (accessibility)") }
            }
        }

        // 3. Agent endpoint
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3. Jac agent endpoint", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = agentUrl,
                    onValueChange = { agentUrl = it },
                    label = { Text("Agent base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { settings.agentBaseUrl = agentUrl }, Modifier.fillMaxWidth()) {
                    Text("Save")
                }
            }
        }

        // Activity log
        Text("Activity", style = MaterialTheme.typography.titleSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                if (log.isEmpty()) {
                    Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    log.takeLast(40).reversed().forEach {
                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
