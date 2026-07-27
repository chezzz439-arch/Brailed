/*
 * Portable Braille + Agentic Phone Controller — ESP32 firmware
 * =============================================================
 * Authored fresh from the authoritative project spec. Single codebase, two
 * PlatformIO build environments toggled by ENABLE_BLE (see platformio.ini).
 *
 * Scope of this file:
 *   - 6-key chorded Grade-1 braille input, decode-on-release of the MAXIMUM
 *     mask seen during the hold (tolerant of imperfect simultaneity).
 *   - Dedicated Send button, OUTSIDE the chord state machine, own debounce.
 *   - SSD1306 OLED: status bar + word-wrapped scrolling caption buffer +
 *     transient status messages.
 *   - BLE GATT over NimBLE (migrated from Bluedroid) with the Nordic base UUID
 *     and the 3-byte TextInput packet [type][mode][payload].
 *   - Serial-emulation path so the whole protocol works with the radio OFF.
 *
 * Power posture (brownout mitigation — root cause is transient inrush at radio
 * init, hardware fix in transit): 80 MHz CPU, NimBLE (lower peak current than
 * Bluedroid), TX power -12 dBm, 2 s settle before radio, slow advertising,
 * dimmed OLED, 100 kHz I2C, staged GPIO -> OLED -> settle -> radio bring-up,
 * non-fatal OLED init.
 *
 * NOTE: not compiled against a real ESP32 toolchain in this environment (no pio
 * installed here). Written against NimBLE-Arduino 1.4.x + Adafruit SSD1306
 * stable APIs. Run `pio run` and fix any toolchain nits before relying on it.
 */

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <vector>

#ifndef ENABLE_BLE
#define ENABLE_BLE 1  // default on; platformio.ini overrides per-env
#endif

#if ENABLE_BLE
#include <NimBLEDevice.h>
#endif

// ---------------------------------------------------------------------------
// Hardware pinout — AUTHORITATIVE (sensors are soldered in place).
//   dot1..dot6 -> GPIO 4,16,17,19,18,23   Send -> GPIO 25 (INPUT_PULLUP, to GND)
//   (dot4 and dot5 swapped vs original wiring: dot4=GPIO19, dot5=GPIO18)
//   OLED SSD1306 128x64 -> SDA 21, SCL 22, addr 0x3C
// ---------------------------------------------------------------------------
static const uint8_t PIN_DOT[6] = {4, 16, 17, 19, 18, 23};  // bit0=dot1 .. bit5=dot6
static const uint8_t PIN_SEND   = 25;

static const uint8_t PIN_SDA = 21;
static const uint8_t PIN_SCL = 22;
static const uint8_t OLED_ADDR = 0x3C;
static const int16_t OLED_W = 128;
static const int16_t OLED_H = 64;

// ---------------------------------------------------------------------------
// Timing
// ---------------------------------------------------------------------------
static const unsigned long CHORD_DEBOUNCE_MS = 20;   // settle window before lock-in
static const unsigned long BUTTON_DEBOUNCE_MS = 30;
static const unsigned long RADIO_SETTLE_MS = 2000;   // let supply stabilize pre-radio
static const unsigned long POLL_MS = 5;
static const unsigned long TRANSIENT_MS = 1500;      // on-screen status message life

// ---------------------------------------------------------------------------
// Reserved chord masks (bit0=dot1 ... bit5=dot6).
//
// SAFETY RULE (decode-on-release): a reserved mask must NOT be reachable by
// dropping exactly one dot from any valid symbol — otherwise a single missed
// touch silently fires it. Single-dot masks are especially unsafe. All masks
// below are multi-dot and verified: no letter, number sign, or capital sign is
// exactly one dot above them, so a dropped dot degrades to "Unknown chord"
// (recoverable) rather than a destructive action.
//   Space  1-2-4-6 : letters one dot above (47, 59) don't exist. (Replaces the
//                    original dots-3-6, which was reachable from 'u' 1-3-6.)
//   Bksp   1-2-5-6 : one dot above (55, 59) don't exist.
//   Mode   2-3-5-6 : one dot above (55, 62) don't exist.
// Inherent-braille caveat: number sign (3-4-5-6) IS one dot below 'y'
// (1-3-4-5-6); a missed dot-1 on 'y' silently arms number mode. Standard braille,
// not fixable here. Capital (dot 6) is safe: no 2-dot letter contains dot 6.
// ---------------------------------------------------------------------------
static const uint8_t MASK_NUMBER_SIGN = 0b111100;  // dots 3-4-5-6  (spec)
static const uint8_t MASK_CAPITAL     = 0b100000;  // dot 6         (spec)
static const uint8_t MASK_SPACE       = 0b101011;  // dots 1-2-4-6  (verified safe)
static const uint8_t MASK_BACKSPACE   = 0b110011;  // dots 1-2-5-6  (verified safe)
static const uint8_t MASK_MODE_TOGGLE = 0b110110;  // dots 2-3-5-6  (verified safe)

// ---------------------------------------------------------------------------
// BLE identifiers — Nordic base UUID 6e400001-b5a3-f393-e0a9-e50e24dcca9e
// ---------------------------------------------------------------------------
#define SERVICE_UUID        "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define TEXT_INPUT_UUID     "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  // NOTIFY device->phone
#define CAPTION_OUTPUT_UUID "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  // WRITE  phone->device
#define COMMAND_RESULT_UUID "6e400004-b5a3-f393-e0a9-e50e24dcca9e"  // WRITE  phone->device
#define STATUS_UUID         "6e400005-b5a3-f393-e0a9-e50e24dcca9e"  // NOTIFY device->phone

// TextInput 3-byte packet: [type][mode][payload]
static const uint8_t PKT_CHAR        = 0x01;  // payload = ASCII char
static const uint8_t PKT_SEND        = 0x02;  // payload = 0x00
static const uint8_t PKT_BACKSPACE   = 0x03;  // payload = 0x00
static const uint8_t PKT_MODE_CHANGE = 0x04;  // payload = 0x00, mode = new mode
static const uint8_t MODE_TEXT_B    = 0x00;
static const uint8_t MODE_COMMAND_B = 0x01;

// ---------------------------------------------------------------------------
// Forward declarations (.cpp needs these before first use)
// ---------------------------------------------------------------------------
static char     decodeLetter(uint8_t mask);
static char     digitForLetter(char letter);
static uint8_t  readDotMask();
static uint8_t  modeByte();
static const char* modeStr();
static void     emitPacket(uint8_t type, uint8_t mode, uint8_t payload);
static void     emitChar(char c);
static void     emitCtrl(uint8_t type);
static void     emitStatus();
static void     printBootReference();
static void     handleCompletedChord(uint8_t mask);
static void     updateChordStateMachine();
static void     updateSendButton();
static void     handleSerialInput();
static void     initOLED();
static void     renderOLED();
static void     renderIdle(uint32_t frame);
static void     pushCaption(const String& chunk);
static void     showTransient(const String& msg);
#if ENABLE_BLE
static void     setupBLE();
#endif

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
enum Mode { MODE_TEXT, MODE_COMMAND };
static Mode currentMode = MODE_TEXT;
static bool capitalNext = false;
static bool numberNext  = false;

enum ChordState { CHORD_IDLE, CHORD_ACCUMULATING, CHORD_HELD };
static ChordState chordState = CHORD_IDLE;
static uint8_t maxMaskSeen = 0;
static unsigned long chordStartTime = 0;

static bool buttonWasPressed = false;
static unsigned long buttonPressStart = 0;

static bool bleConnected = false;
static bool oledOk = false;

static Adafruit_SSD1306 display(OLED_W, OLED_H, &Wire, -1);

static String captionBuf;                 // rolling caption text (bounded)
static String transientMsg;
static unsigned long transientUntil = 0;
static volatile bool oledDirty = false;   // BLE task defers rendering to loop()

// Idle splash: after IDLE_TIMEOUT_MS with no OLED activity, show the animated
// "BRAILED" logo screen, advancing one frame every IDLE_FRAME_MS.
static const unsigned long IDLE_TIMEOUT_MS = 5000;
static const unsigned long IDLE_FRAME_MS   = 100;
static unsigned long lastActivityMs = 0;   // set whenever renderOLED() runs
static unsigned long lastIdleDraw   = 0;
static uint32_t      idleFrame      = 0;

// ---------------------------------------------------------------------------
// Braille lookup (Grade 1). Index = bitmask, value = lowercase letter, 0 = none.
// ---------------------------------------------------------------------------
static char decodeLetter(uint8_t mask) {
  switch (mask) {
    case 0b000001: return 'a';
    case 0b000011: return 'b';
    case 0b001001: return 'c';
    case 0b011001: return 'd';
    case 0b010001: return 'e';
    case 0b001011: return 'f';
    case 0b011011: return 'g';
    case 0b010011: return 'h';
    case 0b001010: return 'i';
    case 0b011010: return 'j';
    case 0b000101: return 'k';
    case 0b000111: return 'l';
    case 0b001101: return 'm';
    case 0b011101: return 'n';
    case 0b010101: return 'o';
    case 0b001111: return 'p';
    case 0b011111: return 'q';
    case 0b010111: return 'r';
    case 0b001110: return 's';
    case 0b011110: return 't';
    case 0b100101: return 'u';
    case 0b100111: return 'v';
    case 0b111010: return 'w';
    case 0b101101: return 'x';
    case 0b111101: return 'y';
    case 0b110101: return 'z';
    default: return 0;
  }
}

static char digitForLetter(char letter) {
  const char* letters = "abcdefghij";
  const char* digits  = "1234567890";
  for (int i = 0; i < 10; i++) {
    if (letters[i] == letter) return digits[i];
  }
  return 0;
}

// ---------------------------------------------------------------------------
// Sensor read. TTP223B breakouts output digital HIGH when touched.
// ---------------------------------------------------------------------------
static uint8_t readDotMask() {
  uint8_t mask = 0;
  for (int i = 0; i < 6; i++) {
    if (digitalRead(PIN_DOT[i]) == HIGH) mask |= (1 << i);
  }
  return mask;
}

static uint8_t modeByte() { return currentMode == MODE_TEXT ? MODE_TEXT_B : MODE_COMMAND_B; }
static const char* modeStr() { return currentMode == MODE_TEXT ? "TEXT" : "CMD"; }

// ---------------------------------------------------------------------------
// Output path — one function feeds both the BLE NOTIFY and the Serial emulation.
// Serial line format (parseable): "TXI <type> <mode> <payload>" in hex.
//   e.g.  TXI 01 00 61   -> char 'a' in text mode
//         TXI 02 01 00   -> SEND in command mode
// Human-readable notes are printed on separate lines prefixed with '#'.
// ---------------------------------------------------------------------------
static void emitPacket(uint8_t type, uint8_t mode, uint8_t payload) {
  uint8_t buf[3] = {type, mode, payload};

#if ENABLE_BLE
  extern void bleNotifyTextInput(const uint8_t*, size_t);  // defined in BLE block
  if (bleConnected) bleNotifyTextInput(buf, sizeof(buf));
#endif

  // Always mirror to Serial: the emulation transport when radio is off, and a
  // useful debug trace when it's on.
  char line[32];
  snprintf(line, sizeof(line), "TXI %02X %02X %02X", type, mode, payload);
  Serial.println(line);
}

static void emitChar(char c) {
  emitPacket(PKT_CHAR, modeByte(), (uint8_t)c);
  Serial.printf("# char '%c' (%s)\n", (c >= 32 && c < 127) ? c : '?', modeStr());
}

static void emitCtrl(uint8_t type) {
  emitPacket(type, modeByte(), 0x00);
  const char* name = type == PKT_SEND ? "SEND"
                   : type == PKT_BACKSPACE ? "BACKSPACE"
                   : type == PKT_MODE_CHANGE ? "MODE_CHANGE" : "CTRL";
  Serial.printf("# ctrl %s (%s)\n", name, modeStr());
}

// ---------------------------------------------------------------------------
// Chord decode (on release, using the max mask seen during the hold)
// ---------------------------------------------------------------------------
static void handleCompletedChord(uint8_t mask) {
  if (mask == MASK_CAPITAL)     { capitalNext = true; showTransient("^cap"); renderOLED(); return; }
  if (mask == MASK_NUMBER_SIGN) { numberNext  = true; showTransient("#num"); renderOLED(); return; }

  if (mask == MASK_MODE_TOGGLE) {
    currentMode = (currentMode == MODE_TEXT) ? MODE_COMMAND : MODE_TEXT;
    emitCtrl(PKT_MODE_CHANGE);
    emitStatus();  // notify phone of the new mode
    showTransient(currentMode == MODE_TEXT ? "mode: TEXT" : "mode: CMD");
    renderOLED();
    return;
  }

  if (mask == MASK_SPACE) {
    emitChar(' ');
    capitalNext = numberNext = false;
    renderOLED();
    return;
  }
  if (mask == MASK_BACKSPACE) {
    emitCtrl(PKT_BACKSPACE);
    capitalNext = numberNext = false;
    renderOLED();
    return;
  }

  char letter = decodeLetter(mask);
  if (letter == 0) { Serial.println("# unrecognized chord, ignored"); return; }

  if (numberNext) {
    char digit = digitForLetter(letter);
    if (digit != 0) emitChar(digit);
    numberNext = false;
    capitalNext = false;  // number sign takes precedence; clear stray capital
    renderOLED();
    return;
  }

  if (capitalNext) { letter = toupper(letter); capitalNext = false; }
  emitChar(letter);
  renderOLED();
}

static void updateChordStateMachine() {
  uint8_t raw = readDotMask();

  switch (chordState) {
    case CHORD_IDLE:
      if (raw != 0) {
        chordState = CHORD_ACCUMULATING;
        chordStartTime = millis();
        maxMaskSeen = raw;
      }
      break;

    case CHORD_ACCUMULATING:
      maxMaskSeen |= raw;
      if (millis() - chordStartTime >= CHORD_DEBOUNCE_MS) chordState = CHORD_HELD;
      break;

    case CHORD_HELD:
      maxMaskSeen |= raw;
      if (raw == 0) {  // all keys released -> chord complete
        handleCompletedChord(maxMaskSeen);
        maxMaskSeen = 0;
        chordState = CHORD_IDLE;
      }
      break;
  }
}

// ---------------------------------------------------------------------------
// Send button — separate GPIO, own debounce, fires SEND directly.
// (Single-purpose per spec: "fires a tagged control event directly." Mode
// toggle lives on its own chord mask, not on a long-press here.)
// ---------------------------------------------------------------------------
static void updateSendButton() {
  bool pressed = (digitalRead(PIN_SEND) == LOW);  // INPUT_PULLUP, other leg to GND

  if (pressed && !buttonWasPressed) {
    buttonPressStart = millis();
    buttonWasPressed = true;
  } else if (!pressed && buttonWasPressed) {
    unsigned long heldFor = millis() - buttonPressStart;
    buttonWasPressed = false;
    if (heldFor < BUTTON_DEBOUNCE_MS) return;  // noise
    emitCtrl(PKT_SEND);
  }
}

// ---------------------------------------------------------------------------
// Serial-emulation inbound path (phone -> device), radio-off development.
//   "CAP <text>"  -> caption chunk for the OLED (emulates CaptionOutput)
//   "CMD <text>"  -> command result string    (emulates CommandResult)
// ---------------------------------------------------------------------------
static void handleSerialInput() {
  if (!Serial.available()) return;
  String line = Serial.readStringUntil('\n');
  line.trim();
  if (line.length() == 0) return;

  if (line.startsWith("CAP ")) {
    pushCaption(line.substring(4));
    renderOLED();
  } else if (line.startsWith("CMD ")) {
    showTransient(line.substring(4));
    renderOLED();
  } else {
    Serial.printf("# unknown serial cmd: %s\n", line.c_str());
  }
}

// ---------------------------------------------------------------------------
// OLED
// ---------------------------------------------------------------------------
static void initOLED() {
  unsigned long t0 = millis();
  Wire.begin(PIN_SDA, PIN_SCL);
  Wire.setClock(100000);  // 100 kHz — gentler on the supply
  unsigned long t1 = millis();
  oledOk = display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR);
  unsigned long t2 = millis();
  Serial.printf("# [timing] Wire.begin=%lums display.begin=%lums ok=%d\n",
                t1 - t0, t2 - t1, oledOk ? 1 : 0);
  if (!oledOk) {
    Serial.println("# OLED init failed (non-fatal), continuing headless");
    return;
  }
  display.dim(true);
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.display();
  Serial.printf("# [timing] initOLED total=%lums\n", millis() - t0);
}

static void pushCaption(const String& chunk) {
  if (captionBuf.length()) captionBuf += ' ';
  captionBuf += chunk;
  // Bound memory: keep only the tail.
  const int MAX = 240;
  if ((int)captionBuf.length() > MAX) captionBuf = captionBuf.substring(captionBuf.length() - MAX);
}

static void showTransient(const String& msg) {
  transientMsg = msg;
  transientUntil = millis() + TRANSIENT_MS;
}

static void wrapInto(const String& text, int maxChars, std::vector<String>& out) {
  String cur;
  int start = 0;
  while (start < (int)text.length()) {
    int sp = text.indexOf(' ', start);
    String word = (sp < 0) ? text.substring(start) : text.substring(start, sp);
    if (word.length() > (unsigned)maxChars) {           // hard-split overlong word
      if (cur.length()) { out.push_back(cur); cur = ""; }
      while ((int)word.length() > maxChars) { out.push_back(word.substring(0, maxChars)); word = word.substring(maxChars); }
    }
    if (cur.length() == 0) cur = word;
    else if ((int)(cur.length() + 1 + word.length()) <= maxChars) cur += " " + word;
    else { out.push_back(cur); cur = word; }
    if (sp < 0) break;
    start = sp + 1;
  }
  if (cur.length()) out.push_back(cur);
}

static void renderOLED() {
  if (!oledOk) return;
  lastActivityMs = millis();   // real content on screen -> not idle
  display.clearDisplay();
  display.setTextSize(1);

  // Status bar (row 0): mode | BLE state | pending capital/number flags
  display.setCursor(0, 0);
  String bar = String(modeStr());
#if ENABLE_BLE
  bar += bleConnected ? " BLE:on" : " BLE:adv";
#else
  bar += " BLE:off";
#endif
  if (capitalNext) bar += " ^";
  if (numberNext)  bar += " #";
  display.println(bar);
  display.drawFastHLine(0, 9, OLED_W, SSD1306_WHITE);

  // Transient status message takes the next row when active.
  int y = 12;
  bool showingTransient = transientMsg.length() && (long)(transientUntil - millis()) > 0;
  if (showingTransient) {
    display.setCursor(0, y);
    display.println(transientMsg);
    y += 10;
  }

  // Caption area: word-wrapped, showing the tail that fits.
  const int CHARS_PER_LINE = OLED_W / 6;         // ~21 at text size 1
  int linesAvail = (OLED_H - y) / 8;
  if (linesAvail > 0) {
    std::vector<String> lines;
    wrapInto(captionBuf, CHARS_PER_LINE, lines);
    int first = (int)lines.size() > linesAvail ? (int)lines.size() - linesAvail : 0;
    for (int i = first; i < (int)lines.size(); i++) {
      display.setCursor(0, y);
      display.println(lines[i]);
      y += 8;
    }
  }
  display.display();
}

// ---------------------------------------------------------------------------
// Idle splash — "BRAILED" name + a big braille cell that types the word on a
// loop, plus a blinking caret. The animation IS braille: the 2x3 cell morphs
// through b-r-a-i-l-e-d so the emblem doubles as a live demo of the alphabet.
// ---------------------------------------------------------------------------
static uint8_t idleMaskFor(char c) {
  // 6-bit dot mask, bit0=dot1 .. bit5=dot6 (matches decodeLetter's encoding).
  switch (c) {
    case 'b': return 0b000011;  // 1-2
    case 'r': return 0b010111;  // 1-2-3-5
    case 'a': return 0b000001;  // 1
    case 'i': return 0b001010;  // 2-4
    case 'l': return 0b000111;  // 1-2-3
    case 'e': return 0b010001;  // 1-5
    case 'd': return 0b011001;  // 1-4-5
    default:  return 0;
  }
}

static void renderIdle(uint32_t frame) {
  if (!oledOk) return;
  display.clearDisplay();

  // Title: "BRAILED" centered near the top (size 2 ~ 12px advance/char).
  const char* title = "BRAILED";
  int titleW = (int)strlen(title) * 12;
  display.setTextSize(2);
  display.setCursor((OLED_W - titleW) / 2, 2);
  display.print(title);

  // Which letter is being "typed": ~600ms each (+ one blank), looping.
  const char* word = "brailed";
  int n = (int)strlen(word);
  int step = (int)((frame / 6) % (uint32_t)(n + 1));
  uint8_t mask = (step < n) ? idleMaskFor(word[step]) : 0;

  // Braille cell (2 cols x 3 rows) + the current uppercase letter, as a group.
  const int r = 4, gx = 14, gy = 12;
  int cellX = 40, cellY = 28;                       // top-left dot centre
  int xs[6] = {cellX, cellX, cellX, cellX + gx, cellX + gx, cellX + gx};
  int ys[6] = {cellY, cellY + gy, cellY + 2 * gy, cellY, cellY + gy, cellY + 2 * gy};
  for (int i = 0; i < 6; i++) {
    if (mask & (1 << i)) display.fillCircle(xs[i], ys[i], r, SSD1306_WHITE);
    else                 display.drawCircle(xs[i], ys[i], r, SSD1306_WHITE);
  }
  if (step < n) {
    display.setTextSize(2);
    display.setCursor(cellX + gx + 20, cellY + gy - 6);
    display.write((char)(word[step] - 'a' + 'A'));
  }

  // Blinking caret at the bottom centre — the "idle" heartbeat.
  if ((frame / 3) % 2 == 0) display.fillRect(OLED_W / 2 - 1, OLED_H - 4, 3, 3, SSD1306_WHITE);

  display.setTextSize(1);   // leave the display in the state renderOLED expects
  display.display();
}

// ---------------------------------------------------------------------------
// BLE (NimBLE) — compiled only when ENABLE_BLE=1
// ---------------------------------------------------------------------------
#if ENABLE_BLE
static NimBLECharacteristic* textInputChar = nullptr;
static NimBLECharacteristic* statusChar    = nullptr;

void bleNotifyTextInput(const uint8_t* data, size_t len) {
  if (!textInputChar) return;
  textInputChar->setValue(data, len);
  textInputChar->notify();
}

// NOTE: these callbacks run in the NimBLE host task, NOT loop(). They must not
// touch I2C/the OLED directly (concurrent transactions can wedge the bus); they
// only mutate state and set oledDirty, and loop() does the actual render.
class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* /*s*/) override {
    bleConnected = true;
    emitStatus();  // announce connected + current mode
    oledDirty = true;
  }
  void onDisconnect(NimBLEServer* /*s*/) override {
    bleConnected = false;
    NimBLEDevice::startAdvertising();  // resume advertising after a drop
    oledDirty = true;
  }
};

class CaptionWriteCallback : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c) override {
    pushCaption(String(c->getValue().c_str()));
    oledDirty = true;
  }
};

class CommandResultWriteCallback : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c) override {
    showTransient(String(c->getValue().c_str()));
    oledDirty = true;
  }
};

static void setupBLE() {
  NimBLEDevice::init("PortableBraille");

  // TX power down for brownout headroom. Under NimBLE this is the correct
  // equivalent of the Bluedroid esp_ble_tx_power_set() calls, applied after
  // init (the controller must be up). Set default + advertising paths low.
  NimBLEDevice::setPower(ESP_PWR_LVL_N12, ESP_BLE_PWR_TYPE_DEFAULT);
  NimBLEDevice::setPower(ESP_PWR_LVL_N12, ESP_BLE_PWR_TYPE_ADV);

  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  NimBLEService* service = server->createService(SERVICE_UUID);

  // NimBLE auto-creates the CCCD (0x2902) for NOTIFY characteristics.
  textInputChar = service->createCharacteristic(TEXT_INPUT_UUID, NIMBLE_PROPERTY::NOTIFY);

  NimBLECharacteristic* captionChar =
      service->createCharacteristic(CAPTION_OUTPUT_UUID, NIMBLE_PROPERTY::WRITE);
  captionChar->setCallbacks(new CaptionWriteCallback());

  NimBLECharacteristic* cmdResultChar =
      service->createCharacteristic(COMMAND_RESULT_UUID, NIMBLE_PROPERTY::WRITE);
  cmdResultChar->setCallbacks(new CommandResultWriteCallback());

  statusChar = service->createCharacteristic(STATUS_UUID, NIMBLE_PROPERTY::NOTIFY);

  service->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(false);              // scan response off — less radio activity
  adv->setMinInterval(800);                 // units of 0.625 ms -> 500 ms
  adv->setMaxInterval(1600);                // -> 1000 ms
  NimBLEDevice::startAdvertising();
}
#endif  // ENABLE_BLE

// ---------------------------------------------------------------------------
// Status characteristic (...0005) NOTIFY — device -> phone.
// 2-byte payload: [ble_state][mode]. ble_state: 0x01 connected, 0x00 not.
// Emitted on connect and on mode change. Safe to call in either build; a no-op
// on the radio (still mirrored to Serial) when BLE is off.
// ---------------------------------------------------------------------------
static void emitStatus() {
#if ENABLE_BLE
  uint8_t state = bleConnected ? 0x01 : 0x00;
  if (statusChar) {
    uint8_t buf[2] = {state, modeByte()};
    statusChar->setValue(buf, sizeof(buf));
    statusChar->notify();
  }
#else
  uint8_t state = 0x00;
#endif
  Serial.printf("STA %02X %02X\n", state, modeByte());
}

// ---------------------------------------------------------------------------
// On-device reference, printed at boot for testing without the phone app.
// ---------------------------------------------------------------------------
static void printBootReference() {
  Serial.println("--- reserved chords (braille dots) ---");
  Serial.println("  Space        1-2-4-6");
  Serial.println("  Backspace    1-2-5-6");
  Serial.println("  Mode toggle  2-3-5-6");
  Serial.println("  Number sign  3-4-5-6");
  Serial.println("  Capital      6");
  Serial.println("--- serial emulation ---");
  Serial.println("  OUT  TXI <type> <mode> <payload>   (hex)");
  Serial.println("         type 01=char 02=send 03=bksp 04=mode");
  Serial.println("         mode 00=text 01=command");
  Serial.println("  OUT  STA <ble_state> <mode>        (hex)");
  Serial.println("  IN   CAP <text>   -> caption to OLED");
  Serial.println("  IN   CMD <text>   -> command result to OLED");
  Serial.println("--------------------------------------");
}

// ---------------------------------------------------------------------------
// Arduino entry points — staged bring-up: GPIO -> OLED -> settle -> radio
// ---------------------------------------------------------------------------
void setup() {
  setCpuFrequencyMhz(80);   // first thing: halve the clock, cut current draw
  Serial.begin(115200);
  delay(50);
  Serial.println("=== Portable Braille firmware ===");
#if ENABLE_BLE
  Serial.println("# build: BLE ON (radio)");
#else
  Serial.println("# build: BLE OFF (serial emulation)");
#endif
  printBootReference();

  // 1) GPIO
  unsigned long tg = millis();
  for (int i = 0; i < 6; i++) pinMode(PIN_DOT[i], INPUT);  // TTP223B drives the line
  pinMode(PIN_SEND, INPUT_PULLUP);
  Serial.printf("# [timing] GPIO=%lums\n", millis() - tg);

  // 2) OLED
  initOLED();
  showTransient("booting...");
  unsigned long tr = millis();
  renderOLED();
  Serial.printf("# [timing] renderOLED#1=%lums\n", millis() - tr);

#if ENABLE_BLE
  // 3) settle, then radio — give the supply time before the inrush
  Serial.println("# settling before radio...");
  delay(RADIO_SETTLE_MS);
  setupBLE();
  Serial.println("# BLE up, advertising");
#endif

  showTransient("ready");
  tr = millis();
  renderOLED();
  Serial.printf("# [timing] renderOLED#2=%lums\n", millis() - tr);
  Serial.println("# ready");
}

void loop() {
  updateChordStateMachine();
  updateSendButton();
  handleSerialInput();

  // Render if a BLE-task callback flagged the display dirty.
  if (oledDirty) { oledDirty = false; renderOLED(); }

  // Expire transient status messages (redraw once when one lapses).
  static bool wasShowing = false;
  bool showing = transientMsg.length() && (long)(transientUntil - millis()) > 0;
  if (wasShowing && !showing) renderOLED();
  wasShowing = showing;

  // Idle splash: once nothing has happened for a while, play the animated
  // BRAILED logo. Any real activity calls renderOLED(), which resets the timer.
  bool idle = (lastActivityMs == 0) || (millis() - lastActivityMs > IDLE_TIMEOUT_MS);
  if (idle && oledOk && millis() - lastIdleDraw >= IDLE_FRAME_MS) {
    renderIdle(idleFrame++);
    lastIdleDraw = millis();
  }

  delay(POLL_MS);
}
