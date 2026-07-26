# Brailed

Braille keyboard that mounts on your phone — type in braille, get live captions of what your phone is saying, and control it with natural-language commands interpreted by an LLM (Jac + byLLM).

## Architecture

```
 ┌─────────────────────┐   BLE    ┌──────────────┐   HTTP    ┌──────────────────┐
 │  Portable Braille    │ ───────▶ │  Android app │ ────────▶ │  Jac agent        │
 │  device (ESP32)      │ CHAR/... │ (companion)  │  command  │  (byLLM + Claude) │
 │                      │ ◀─────── │              │ ◀──────── │                   │
 │  6 dot sensors +     │ captions │  IME + a11y  │  Action   │  interpret_command│
 │  Send/Command button │          │  + executes  │   JSON    │  + allow-list     │
 └─────────────────────┘          └──────────────┘           └──────────────────┘
```

Two modes on the device, toggled by long-pressing the Send button:

- **Text mode** — chorded braille cells decode to characters on-device and stream to the phone.
- **Command mode** — the user types a natural-language instruction ("go to instagram at the home page"); on Send the phone POSTs it to the Jac agent, which returns a structured, validated `Action` for the app to execute.

## Components

| Path | What it is | Status |
| --- | --- | --- |
| [`firmware/portable_braille.ino`](firmware/portable_braille.ino) | ESP32 firmware — 6-dot chord detection, Send/Command button, BLE text/caption/command characteristics. | Written against stable ESP32 Arduino BLE APIs; **not** compile-tested (no toolchain available). Flash and check before relying on it. |
| [`android/`](android/) | Companion app (Kotlin/Compose) — BLE client, braille keyboard (IME), screen captions + control (AccessibilityService), agent client. | Scaffold; **not** compile-tested (no Android SDK available). Open in Android Studio — see [android/README.md](android/README.md). |
| [`jac_backend/`](jac_backend/) | Jac + byLLM agent that turns an instruction into a typed `Action`, with a server-side app allow-list. Exposed as a REST walker. | **Verified** on jaclang 0.16.7 / byllm 0.6.19 — see [jac_backend/README.md](jac_backend/README.md). Tests pass under MockLLM; the live Claude call just needs an API key. |

## Quick start (backend)

```bash
python3.12 -m venv .venv && source .venv/bin/activate   # jaclang needs Python 3.11–3.13
pip install -r requirements.txt
cp .env.example .env        # add your ANTHROPIC_API_KEY

# test without an API key (MockLLM):
jac test jac_backend/agent.test.jac

# run for real:
export ANTHROPIC_API_KEY=sk-ant-...
jac start jac_backend/agent.jac --no_client
curl -X POST http://localhost:8000/walker/run_command \
  -H "Content-Type: application/json" \
  -d '{"instruction": "go to instagram at the home page"}'
```

See [jac_backend/README.md](jac_backend/README.md) for the full backend docs — response shape, extending the action set, and how it wires to the phone app.

## Companion app (Android)

Open `android/` in Android Studio, then follow the in-app three-step setup (Bluetooth permissions → enable keyboard + accessibility → set agent URL). See [android/README.md](android/README.md). A real device is required (BLE doesn't run in the emulator).

**Why Android, not iOS:** typing into other apps needs a custom keyboard, and reading/controlling other apps needs an accessibility service — Android allows both. iOS permits the braille keyboard but not reading or driving other apps, so the captions-and-control half of the product isn't possible there.

## Firmware

Open `firmware/portable_braille.ino` in the Arduino IDE (ESP32 board support required) and set `PIN_DOT[6]` / `PIN_SEND` to match your wiring before flashing. The wire protocol it emits over BLE is documented inline at the top of the file.

## Status

The agent backend is verified end-to-end except for the live model call (needs a key). The firmware covers the input path (chord detection + BLE); OLED caption rendering is stubbed. The Android app is a complete scaffold wired to the real protocol but has not been compiled (no Android SDK in the build environment) — expect minor fix-ups on first Android Studio sync.
