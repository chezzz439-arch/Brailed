# Brailed — Android companion app

The middle layer between the ESP32 braille device and the Jac agent. It holds the BLE connection, types decoded braille into any app, captions what's on screen back to the device, and executes natural-language commands.

Android is the only mobile platform where the full concept works: typing into other apps needs a **custom keyboard (IME)**, and reading/controlling other apps needs an **AccessibilityService** — both of which iOS does not allow.

## Build & run

There is **no committed Gradle wrapper jar**, so use Android Studio (it provisions the wrapper on first sync) or generate it once with a local Gradle:

```bash
# Option A — Android Studio (recommended): File ▸ Open ▸ select android/, let it sync.
# Option B — CLI, if you have Gradle + the Android SDK installed:
cd android
gradle wrapper --gradle-version 8.9
./gradlew installDebug          # with a device connected via adb
```

Requires the **Android SDK** (compileSdk 34) and a **real device** — BLE does not work in the emulator. Min SDK 26 (Android 8.0).

## First-run setup (in the app)

The main screen walks through three steps:

1. **Grant Bluetooth permissions**, then **Start / scan for device** (connects to `PortableBraille`).
2. **Enable the OS integrations:**
   - *Enable Brailed keyboard* → toggle it on in Settings, then *Switch to Brailed keyboard* to make it active. Text you chord on the device now types into whatever field has focus.
   - *Enable captions & control (accessibility)* → turn on the Brailed accessibility service. This powers screen captions back to the device and the `GO_HOME` / `READ_NOTIFICATIONS` commands.
3. **Set the Jac agent URL** to the machine running `jac start jac_backend/agent.jac` (e.g. `http://192.168.1.42:8000` on the same Wi-Fi), and Save.

## How the modes map to the firmware protocol

The firmware emits, over the TextInput BLE characteristic:

| Message | Text mode | Command mode |
| --- | --- | --- |
| `CHAR:<mode>:<c>` | typed into the focused field (IME) | appended to the command buffer |
| `CTRL:<mode>:BACKSPACE` | deletes one char | drops last buffered char |
| `CTRL:<mode>:SEND` | inserts newline | POSTs buffer to the agent → executes `Action` |
| `CTRL:<mode>:MODE_TOGGLE` | switch to command mode | switch back to text mode |

## Code map (`app/src/main/java/com/brailed/companion/`)

| File | Role |
| --- | --- |
| `ble/BleService.kt` | Foreground service; scans, connects, subscribes, routes messages, dispatches commands, writes captions back. |
| `ble/BrailedProtocol.kt` | Parses the `CHAR:`/`CTRL:` wire format. |
| `ime/BrailleInputMethodService.kt` | The keyboard — commits characters from the bus into the focused field. |
| `a11y/BrailedAccessibilityService.kt` | Captions on-screen text to the device; global home/back/notifications actions. |
| `agent/AgentClient.kt` + `agent/Action.kt` | POSTs to `run_command`, decodes the `Action`. |
| `command/CommandExecutor.kt` | `Action` → Intent / accessibility action, with an app allow-list. |
| `core/Bus.kt`, `core/Settings.kt` | In-process event bus and the agent-URL preference. |
| `MainActivity.kt` | Setup UI, permissions, status, activity log. |

## Not done yet / caveats

- **Not compile-tested** — written without an Android SDK available. Open in Android Studio and expect to fix minor version/import nits on first sync.
- Deprecated BLE `writeCharacteristic(value)` / `writeDescriptor(value)` calls are used for min-SDK-26 compatibility; fine, but Studio will flag them.
- The app allow-list in `CommandExecutor` only knows Instagram, Messages, Settings, Camera, Phone — matching the agent's `KNOWN_APPS`. Add packages there to support more.
- Caption throttling is naive (drops exact repeats only); a real build would debounce and filter noisier event types.
