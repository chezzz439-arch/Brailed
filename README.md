# Portable Braille — Agentic Phone Controller

A phone-mounted assistive device that lets blind, low-vision, deaf, and deafblind users operate a smartphone privately, without a sighted or hearing assistant — type in braille, read live captions of what the phone is saying, and control the phone with natural-language commands.

## Overview

The device attaches to the back of a smartphone via a case-mounted enclosure. It gives the user three capabilities:

- **Braille text input** — type Grade 1 braille on a 6-key chorded keypad; each chord decodes to a character and streams to the phone over Bluetooth Low Energy (BLE).
- **Live audio captioning** — whatever the phone is speaking through its own speaker (calls, media, voice replies) is transcribed to text in near real time and displayed on the device's OLED, so a deaf or hard-of-hearing user can read along.
- **Agentic phone control** — instead of a fixed set of hardcoded commands, the user types a natural-language instruction ("open messages," "reply to mom") that's interpreted by an LLM and translated into a real, validated phone action.

This is a from-scratch build inspired by Adil Jussupov's Portable Braille (TreeHacks 2025), which used a 200V refreshable braille cell and a separate camera/Jetson pipeline for facial-expression recognition. This project uses a simpler, more buildable hardware set — an OLED for visual output instead of a physical braille cell — while adding LLM-driven agentic control on top.

## Features

| Feature | How it works |
| --- | --- |
| Braille typing | 6-sensor chorded input, decoded on-device, streamed to the phone as you type |
| Dedicated Send | A 7th physical button submits sentences and confirms commands — chosen over a 7th chord for reliability on the highest-frequency non-letter action |
| Live captions | Phone-speaker audio is transcribed and pushed to the on-device OLED |
| Agentic commands | Natural-language instructions are interpreted by an LLM (via a Jac/byLLM backend) and mapped to validated phone actions |
| Private & local-first | Typing and captioning run over a direct BLE link — no cloud round-trip except for the agentic command layer |

## How it works

```
Braille device --BLE--> Phone app --HTTP--> Jac backend
(6 sensors + Send   (BLE central,        (interpret_command,
button, ESP32, OLED)  STT, actions)        phone-capability nodes)
                          |
                    (audio in, actions out)
                          |
                          v
                     OS & speaker
              (calls, media, apps, notifications)
```

- Typing flows directly from the device to the phone app over BLE — no backend involved, kept fast and deterministic.
- Captioning flows the other direction: the phone app transcribes its own speaker audio and streams caption text back to the device's OLED.
- Agentic commands are the one path that leaves the phone: a typed instruction is sent to a small Jac backend, which uses an LLM to decide on a structured action, which the phone app then validates against an allow-list before executing.

## Hardware

| Component | Role |
| --- | --- |
| ESP32 dev board | Reads sensors, runs BLE, drives the OLED |
| 6x capacitive touch sensors | Braille chord input — mirrors the 6 dots of a standard braille cell |
| 1x momentary push button | Dedicated End/Send, wired independently of the chord logic |
| OLED display (I2C, SSD1306-class) | Live captions + status |
| Phone-back mount / enclosure | Attaches the device to the phone, with cutouts for camera/speaker/mic |
| Li-Po battery + charge/BMS module | Power |

## Tech stack

- **Firmware:** C/C++ (Arduino framework) on ESP32 — chord detection, OLED rendering, BLE peripheral.
- **Communication:** Custom BLE GATT service between the device and the phone app.
- **Agentic backend:** Jac (Jaseci stack) — natural-language command interpretation via byLLM, with phone capabilities modeled as Object-Spatial nodes/walkers. Model calls route through LiteLLM, pointed at the Claude API.
- **Phone app:** Native (Swift/Kotlin) or cross-platform — handles BLE central role, on-device speech-to-text, OS-level text injection and action execution (Accessibility APIs / Shortcuts, depending on platform).

## Build instructions

Recommended build order:

1. **Firmware bring-up** — get the 6 touch sensors reliably producing a decoded braille character, and the 7th button firing an independent Send event. Verify over Serial before touching BLE or the OLED.
2. **OLED output** — get word-wrap and scrolling working with hardcoded test strings.
3. **BLE GATT service** — stand up the characteristics, verify with a generic BLE scanner app (e.g. nRF Connect) before writing the phone app.
4. **Jac backend MVP** — stand up the command-interpretation service with a couple of hardcoded phone-capability actions, test it standalone before wiring it to the phone app.
5. **Minimal companion app** — connect over BLE, display incoming text, echo typed characters into a test field.
6. **Speech-to-text pipeline** — get captured audio flowing to text and onto the OLED.
7. **Agentic command loop end-to-end** — type instruction → BLE → Jac backend → validated action → OS automation → OLED confirmation.
8. **OS-level integration & polish** — full text injection into third-party apps, expand the action set, refine the enclosure.

A more detailed technical spec — braille encoding tables, BLE characteristic definitions, Jac architecture, and a full component-by-component workflow trace — lives in `docs/master-prompt.md` (or wherever you place it in this repo).

## Status

Early-stage / hackathon build. Core loops (typing, captioning, agentic commands) are being built and tested independently before full integration — see Build instructions above for current stage.

## License

 (e.g. MIT, Apache 2.0)
