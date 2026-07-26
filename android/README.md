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

The main screen walks through four steps:

1. **Grant permissions (Bluetooth + mic)**, then **Start / scan for device** (connects to `PortableBraille`).
2. **Enable the OS integrations:**
   - *Enable Brailed keyboard* → toggle it on in Settings, then *Switch to Brailed keyboard* to make it active. Text you chord on the device now types into whatever field has focus. **This is the preferred text path;** if the Brailed keyboard isn't the active one, the accessibility service injects text instead (see below).
   - *Enable captions & control (accessibility)* → turn on the Brailed accessibility service. This powers the `GO_HOME` / `READ_NOTIFICATIONS` commands and the text-injection fallback.
3. **Set the Jac agent URL** to the machine running `jac start jac_backend/agent.jac` (e.g. `http://192.168.1.42:8000` on the same Wi-Fi), and Save.
4. **Start live captions** → grants a screen/audio-capture consent and begins captioning phone audio (see limits below).

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
| `ble/BleService.kt` | Foreground service; scans, connects, subscribes, routes text vs command, dispatches commands, writes captions → `CaptionOutput` and command results → `CommandResult`. Chooses IME vs accessibility for text injection. |
| `ble/BrailedProtocol.kt` | Parses the `CHAR:`/`CTRL:` wire format. |
| `ime/BrailleInputMethodService.kt` | The keyboard — commits characters; Send triggers the field's editor action (`performEditorAction`, Enter fallback). |
| `a11y/BrailedAccessibilityService.kt` | Screen-text captions + global home/back/notifications, and the `ACTION_SET_TEXT` injection fallback. |
| `capture/AudioCaptureService.kt` | MediaProjection playback capture → PCM → `Transcriber` → `CaptionOutput`. |
| `capture/VoskTranscriber.kt` | Offline speech-to-text (Vosk); emits final utterances. |
| `capture/VoskModelProvider.kt` | Downloads + unpacks the Vosk model on first use (~40 MB). |
| `capture/Transcriber.kt` | STT seam + `StubTranscriber` (voice-activity fallback until the model loads). |
| `agent/AgentClient.kt` + `agent/Action.kt` | POSTs to `run_command`, decodes the `Action`. |
| `command/CommandExecutor.kt` | `Action` → Intent / accessibility action, with an app allow-list. |
| `core/Bus.kt`, `core/Settings.kt` | In-process event bus and the agent-URL preference. |
| `MainActivity.kt` | Setup UI, permissions, status, activity log. |

## Captions: two sources, and the hard limit

The device gets caption text back over `CaptionOutput` from two places:

1. **Phone audio** (`AudioCaptureService`) — the master-prompt capability #2. Uses `MediaProjection` + `AudioPlaybackCapture` (Android 10+) to grab the phone's *playback* audio and run it through a `Transcriber`.
   - **Speech-to-text is real, via Vosk** (offline, Apache-2.0). The small English model (~40 MB) is **downloaded on first use** to app storage — not bundled in the APK — so the first caption session shows "Downloading speech model…" and the voice-activity stub runs until it's ready. Swap the model in `VoskModelProvider` for a larger or different-language one.
   - **Call audio generally can't be captured.** Apps set their audio to disallow capture, and telephony is exempt, so captioning an actual phone call this way usually yields silence. This is an OS limitation, not a bug — the master prompt (§10) flags it as the design's riskiest assumption.
2. **On-screen text** (`BrailedAccessibilityService`) — announcements and window text, as a lighter always-on complement.

## Not done yet / caveats

- Speech-to-text quality is the small Vosk model's — fine for short commands/media, not dictation-grade. Upgrade the model in `VoskModelProvider` if needed.
- Deprecated BLE `writeCharacteristic(value)` / `writeDescriptor(value)` calls are used for min-SDK-26 compatibility; fine, but Studio will flag them.
- The app allow-list in `CommandExecutor` only knows Instagram, Messages, Settings, Camera, Phone — matching the agent's `KNOWN_APPS`. Add packages there to support more.
- Caption throttling is naive (screen-text drops exact repeats only); a real build would debounce and filter noisier event types.
