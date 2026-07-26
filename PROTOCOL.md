# Brailed wire protocol (canonical)

The single source of truth for how the ESP32 device and the phone app talk.
Both transports (BLE and USB-serial) carry the **same logical messages**; only
the framing differs. Firmware: `firmware/src/main.cpp`. App: `android/.../ble`.

Byte values below are authoritative — do not change one side without the other.

## GATT (BLE) — base UUID `6e400001-b5a3-f393-e0a9-e50e24dcca9e` (Nordic)

| Char | Suffix | Dir | Prop | Payload |
|------|--------|-----|------|---------|
| Service       | `…0001` | —              | —      | — |
| TextInput     | `…0002` | device → phone | NOTIFY | 3-byte packet (below) |
| CaptionOutput | `…0003` | phone → device | WRITE  | UTF-8 caption text, raw (chunked ≤20 B) |
| CommandResult | `…0004` | phone → device | WRITE  | UTF-8 result text, raw |
| Status        | `…0005` | device → phone | NOTIFY | 2-byte `[state][mode]` |

Device advertises as name `PortableBraille` and by Service UUID.

## TextInput 3-byte packet `[type][mode][payload]`

| Field | Values |
|-------|--------|
| `type` | `0x01` CHAR · `0x02` SEND · `0x03` BACKSPACE · `0x04` MODE_CHANGE |
| `mode` | `0x00` TEXT · `0x01` COMMAND (mode active when the event fired; for MODE_CHANGE this is the **new** mode) |
| `payload` | CHAR → ASCII byte; all control types → `0x00` |

## Status 2-byte packet `[state][mode]`

`state`: `0x01` connected · `0x00` not. `mode`: as above. Emitted on connect
and on every mode change. Advisory — the app also tracks mode from MODE_CHANGE.

## USB-serial framing (115200 8N1) — same messages, line-delimited ASCII

Device → phone, one message per `\n` line:
```
TXI <type> <mode> <payload>     # hex, e.g. "TXI 01 00 61" = char 'a', text mode
STA <state> <mode>              # hex, e.g. "STA 01 00"
#  …                            # human-readable log; IGNORE lines starting with '#'
```
Phone → device, one per `\n` line:
```
CAP <text>                      # caption  -> equivalent of CaptionOutput WRITE
CMD <text>                      # result   -> equivalent of CommandResult WRITE
```

Note the asymmetry, by design: BLE routes caption vs. result by *which
characteristic* is written, so no prefix is needed; serial is one stream, so it
needs the `CAP `/`CMD ` prefix. Both map to the same device behavior.

## Parsing rules
- TextInput/`TXI` `type` values not in the table → ignore (forward-compat).
- Serial: ignore any line not starting with `TXI `, `STA `.
- The CHAR payload may be a literal space (`0x20`) — never trim it away.
