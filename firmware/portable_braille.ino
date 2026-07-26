/*
 * Portable Braille — ESP32 firmware
 * Chord detection (6 braille-dot sensors) + dedicated Send/Command button,
 * broadcasting decoded text and control events over BLE.
 *
 * NOTE ON VERIFICATION: unlike the Jac backend (agent.jac), this firmware
 * has NOT been compile-tested against a real ESP32 toolchain in the
 * environment this was written in -- no Arduino/ESP32 build tools were
 * available. It's written against stable, well-established ESP32 Arduino
 * BLE APIs, but flash it and check for compile errors before relying on it.
 * Pin numbers below are examples -- change PIN_DOT_1..6 and PIN_SEND to
 * match your actual wiring.
 *
 * Covers master-prompt Section 6 (chord detection), the reserved masks
 * defined in Section 5, and the dedicated 7th Send button from Section 3.
 * BLE service setup here covers TextInput fully; CaptionOutput/CommandResult/
 * Status (Section 8) are declared but left as stubs -- OLED rendering
 * (Section 7) isn't implemented here, this file is scoped to the input path.
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ---------------------------------------------------------------------------
// Pin assignments -- CHANGE THESE to match your wiring.
// Assumes capacitive touch sensor breakout boards (e.g. TTP223-style) with
// digital HIGH/LOW output. If you're using ESP32's native touchRead() pins
// against bare pads instead, see the commented alternative in readDotMask().
// ---------------------------------------------------------------------------
const uint8_t PIN_DOT[6] = {32, 33, 25, 26, 27, 14};  // dot1..dot6
const uint8_t PIN_SEND = 13;                          // dedicated Send/Command button

// ---------------------------------------------------------------------------
// Timing
// ---------------------------------------------------------------------------
const unsigned long CHORD_DEBOUNCE_MS = 20;   // settle window before a chord "locks in"
const unsigned long BUTTON_DEBOUNCE_MS = 30;
const unsigned long LONG_PRESS_MS = 600;      // hold Send this long = toggle Command Mode

// ---------------------------------------------------------------------------
// Reserved chord masks (bit0=dot1 ... bit5=dot6), derived in master-prompt
// Section 5/6. Letters a-z + number sign + capital sign are already spoken
// for; SPACE and BACKSPACE below use two of the many remaining unused masks.
// ---------------------------------------------------------------------------
const uint8_t MASK_SPACE = 0b000010;      // dot2 alone
const uint8_t MASK_BACKSPACE = 0b000100;  // dot3 alone
const uint8_t MASK_NUMBER_SIGN = 0b111100; // dots 3-4-5-6
const uint8_t MASK_CAPITAL = 0b100000;     // dot6 alone

// ---------------------------------------------------------------------------
// Braille lookup table (Grade 1, from master-prompt Section 5)
// Index = bitmask (1-63), value = decoded lowercase letter, 0 = unmapped.
// ---------------------------------------------------------------------------
char decodeLetter(uint8_t mask) {
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
    default: return 0;  // unmapped -- includes SPACE/BACKSPACE/NUMBER/CAPITAL,
                         // handled separately before this is called
  }
}

char digitForLetter(char letter) {
  // Number sign convention: a-j read as 1-0
  const char* letters = "abcdefghij";
  const char* digits  = "1234567890";
  for (int i = 0; i < 10; i++) {
    if (letters[i] == letter) return digits[i];
  }
  return 0;
}

// ---------------------------------------------------------------------------
// BLE setup
// ---------------------------------------------------------------------------
#define SERVICE_UUID        "6e400001-0000-1000-8000-00805f9b34fb"
#define TEXT_INPUT_UUID      "6e400002-0000-1000-8000-00805f9b34fb"  // notify, device -> phone
#define CAPTION_OUTPUT_UUID  "6e400003-0000-1000-8000-00805f9b34fb"  // write, phone -> device (stub)
#define COMMAND_RESULT_UUID  "6e400004-0000-1000-8000-00805f9b34fb"  // write, phone -> device (stub)
#define STATUS_UUID          "6e400005-0000-1000-8000-00805f9b34fb"  // notify (stub)

BLEServer* bleServer = nullptr;
BLECharacteristic* textInputChar = nullptr;
BLECharacteristic* captionOutputChar = nullptr;
BLECharacteristic* commandResultChar = nullptr;
BLECharacteristic* statusChar = nullptr;
bool bleConnected = false;

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override { bleConnected = true; }
  void onDisconnect(BLEServer* server) override {
    bleConnected = false;
    server->getAdvertising()->start();  // resume advertising after a drop
  }
};

class CaptionWriteCallback : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    std::string value = c->getValue();
    // TODO (Section 7): render `value` on the OLED, word-wrapped/scrolled.
    Serial.print("Caption chunk received: ");
    Serial.println(value.c_str());
  }
};

class CommandResultWriteCallback : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    std::string value = c->getValue();
    // TODO (Section 7): show confirmation/error on the OLED.
    Serial.print("Command result received: ");
    Serial.println(value.c_str());
  }
};

void setupBLE() {
  BLEDevice::init("PortableBraille");
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  BLEService* service = bleServer->createService(SERVICE_UUID);

  textInputChar = service->createCharacteristic(
      TEXT_INPUT_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  textInputChar->addDescriptor(new BLE2902());

  captionOutputChar = service->createCharacteristic(
      CAPTION_OUTPUT_UUID, BLECharacteristic::PROPERTY_WRITE);
  captionOutputChar->setCallbacks(new CaptionWriteCallback());

  commandResultChar = service->createCharacteristic(
      COMMAND_RESULT_UUID, BLECharacteristic::PROPERTY_WRITE);
  commandResultChar->setCallbacks(new CommandResultWriteCallback());

  statusChar = service->createCharacteristic(
      STATUS_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  statusChar->addDescriptor(new BLE2902());

  service->start();
  bleServer->getAdvertising()->start();
}

// Wire protocol (device -> phone, on TextInput):
//   "CHAR:<mode>:<char>"   -- one decoded character, mode is TEXT or COMMAND
//   "CTRL:<mode>:<event>"  -- event is SEND, BACKSPACE, or MODE_TOGGLE
// See master-prompt Section 8 for the fuller characteristic design this
// simplifies into a single-string payload for firmware simplicity.
void sendBLE(const String& msg) {
  if (!bleConnected) return;
  textInputChar->setValue(msg.c_str());
  textInputChar->notify();
}

// ---------------------------------------------------------------------------
// Mode state
// ---------------------------------------------------------------------------
enum Mode { MODE_TEXT, MODE_COMMAND };
Mode currentMode = MODE_TEXT;

const char* modeStr() {
  return currentMode == MODE_TEXT ? "TEXT" : "COMMAND";
}

// ---------------------------------------------------------------------------
// Chord state machine (master-prompt Section 6)
// ---------------------------------------------------------------------------
enum ChordState { CHORD_IDLE, CHORD_ACCUMULATING, CHORD_HELD };
ChordState chordState = CHORD_IDLE;
uint8_t maxMaskSeen = 0;
unsigned long chordStartTime = 0;

bool capitalNext = false;
bool numberNext = false;

uint8_t readDotMask() {
  uint8_t mask = 0;
  for (int i = 0; i < 6; i++) {
    if (digitalRead(PIN_DOT[i]) == HIGH) {
      mask |= (1 << i);
    }
  }
  return mask;

  // --- Alternative for ESP32 native touch pins instead of TTP223-style ICs ---
  // uint8_t mask = 0;
  // const int TOUCH_THRESHOLD = 40; // calibrate at startup, see Section 6
  // for (int i = 0; i < 6; i++) {
  //   if (touchRead(PIN_DOT[i]) < TOUCH_THRESHOLD) mask |= (1 << i);
  // }
  // return mask;
}

void emitChar(char c) {
  String msg = "CHAR:";
  msg += modeStr();
  msg += ":";
  msg += c;
  sendBLE(msg);
  Serial.println(msg);
}

void emitCtrl(const char* event) {
  String msg = "CTRL:";
  msg += modeStr();
  msg += ":";
  msg += event;
  sendBLE(msg);
  Serial.println(msg);
}

void handleCompletedChord(uint8_t mask) {
  if (mask == MASK_CAPITAL) {
    capitalNext = true;
    return;  // modifier only, nothing to emit yet
  }
  if (mask == MASK_NUMBER_SIGN) {
    numberNext = true;
    return;
  }
  if (mask == MASK_SPACE) {
    emitChar(' ');
    capitalNext = false;
    numberNext = false;
    return;
  }
  if (mask == MASK_BACKSPACE) {
    emitCtrl("BACKSPACE");
    capitalNext = false;
    numberNext = false;
    return;
  }

  char letter = decodeLetter(mask);
  if (letter == 0) {
    Serial.println("Unrecognized chord, ignoring");
    return;
  }

  if (numberNext) {
    char digit = digitForLetter(letter);
    if (digit != 0) emitChar(digit);
    numberNext = false;
    capitalNext = false;  // number sign takes precedence; clear stray capital flag
    return;
  }

  if (capitalNext) {
    letter = toupper(letter);
    capitalNext = false;
  }

  emitChar(letter);
}

void updateChordStateMachine() {
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
      if (millis() - chordStartTime >= CHORD_DEBOUNCE_MS) {
        chordState = CHORD_HELD;
      }
      break;

    case CHORD_HELD:
      maxMaskSeen |= raw;
      if (raw == 0) {
        // all keys released -- chord complete
        handleCompletedChord(maxMaskSeen);
        maxMaskSeen = 0;
        chordState = CHORD_IDLE;
      }
      break;
  }
}

// ---------------------------------------------------------------------------
// Send / Command-Mode-toggle button (master-prompt Section 3/6)
// Short press -> Send. Long press (>= LONG_PRESS_MS) -> toggle Command Mode.
// ---------------------------------------------------------------------------
bool buttonWasPressed = false;
unsigned long buttonPressStart = 0;

void updateSendButton() {
  bool pressed = (digitalRead(PIN_SEND) == LOW);  // wired with INPUT_PULLUP

  if (pressed && !buttonWasPressed) {
    buttonPressStart = millis();
    buttonWasPressed = true;
  } else if (!pressed && buttonWasPressed) {
    unsigned long heldFor = millis() - buttonPressStart;
    buttonWasPressed = false;

    if (heldFor < BUTTON_DEBOUNCE_MS) {
      return;  // too short, likely noise
    }

    if (heldFor >= LONG_PRESS_MS) {
      currentMode = (currentMode == MODE_TEXT) ? MODE_COMMAND : MODE_TEXT;
      emitCtrl("MODE_TOGGLE");
      Serial.print("Mode is now: ");
      Serial.println(modeStr());
    } else {
      emitCtrl("SEND");
    }
  }
}

// ---------------------------------------------------------------------------
// Arduino entry points
// ---------------------------------------------------------------------------
void setup() {
  Serial.begin(115200);

  for (int i = 0; i < 6; i++) {
    pinMode(PIN_DOT[i], INPUT);
  }
  pinMode(PIN_SEND, INPUT_PULLUP);

  setupBLE();
  Serial.println("Portable Braille firmware ready.");
}

void loop() {
  updateChordStateMachine();
  updateSendButton();
  delay(5);  // simple polling interval; chord-level debounce absorbs sensor noise
}
