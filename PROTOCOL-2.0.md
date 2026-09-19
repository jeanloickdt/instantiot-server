# InstantIoT wire protocol 2.0

What a device and the InstantIoT app — or the relay in between — say to each
other, byte by byte. You never need this to use the library or the app. It is
here for the day you port InstantIoT to a device the library does not support,
or write a client of your own.

The same bytes travel over every link: TCP to the Cloud or to a self-hosted
server, TCP over the device's own Wi-Fi network, Bluetooth Classic (SPP),
Bluetooth LE (Nordic UART Service), or a serial line to an HC-05 / HM-10 module.
A signal does not know how it travelled.

Reference implementations: `src/core/BinaryCodec.hpp` in the Arduino library,
`SignalFrame.kt` and `FrameParser.kt` in the server.

---

## 1. The model

A device exposes **signals**, each at an **address** `I0` … `I255`. A signal
carries one value of one type — boolean, 32-bit integer, 32-bit float, or a
short text. The device writes values to addresses; the app reads and writes
the same addresses. Which widget shows a signal, what it is called, its unit,
its range: all of that lives in the app and never crosses the wire.

There is **one kind of message**: a value written to an address. Plus one
service frame, the heartbeat, on links that lead to a server.

---

## 2. Frame layout

Every frame:

```
AA │ VER │ LEN (u16, little-endian) │ BODY (LEN bytes) │ CRC8
```

| Field | Size | Value |
|---|---|---|
| `AA` | 1 | start byte, always `0xAA` |
| `VER` | 1 | protocol version, always `0x01` |
| `LEN` | 2 | length of `BODY`, little-endian |
| `BODY` | LEN | see below |
| `CRC8` | 1 | CRC-8 of `BODY` only — polynomial `0x07`, initial value `0`, no reflection, no final XOR (CRC-8/SMBus) |

The body:

```
DEV_COUNT │ [DEV_LEN │ DEV_ID]×DEV_COUNT │ WID_LEN │ WID │ TYPE │ TAG │ PAYLOAD
```

| Field | Size | Value |
|---|---|---|
| `DEV_COUNT` | 1 | number of device identifiers that follow — `0` on anything a device sends or receives |
| `DEV_LEN`, `DEV_ID` | 1 + n | a length-prefixed device identifier (UTF-8), only on frames the relay forwards **to apps** |
| `WID_LEN` | 1 | `1` for a signal frame, `0` for a heartbeat |
| `WID` | WID_LEN | the address, one byte, `0`–`255` |
| `TYPE` | 1 | `0x20` signal · `0xFE` heartbeat |
| `TAG` | 1 | the value's type, and the restore bit — see §3 |
| `PAYLOAD` | rest | the value, encoded per `TAG` |

There is no sequence number: every supported link delivers bytes in order,
without duplication. A receiver resynchronises on the next `0xAA` after a bad
frame; a frame whose `LEN` does not fit its receive buffer is skipped one byte
at a time. The library's largest frame is 64 bytes (`INSTANT_MAX_FRAME_SIZE`).

Type codes `0x01`–`0x11` were the widget frames of the 1.x protocol. A 2.0
device neither sends nor understands them; the relay does not route them to
an address.

---

## 3. The signal frame — `TYPE = 0x20`

```
AA 01 │ LEN │ 00 │ 01 │ addr │ 20 │ TAG │ value │ CRC
```

| `TAG` | Type | `PAYLOAD` |
|---|---|---|
| `0x01` | boolean | 1 byte, `0x00` or `0x01` |
| `0x02` | integer | 4 bytes, int32, little-endian |
| `0x03` | float | 4 bytes, IEEE 754 single, little-endian |
| `0x04` | text | the UTF-8 bytes, no terminator, **48 bytes at most** |

**Bit 7 of `TAG` (`0x80`) is the restore flag.** When a device reconnects, the
server sends back the last value of every signal that asked to be restored;
the frame is identical to a live write except for this bit. The library
strips it before decoding the type and hands the value to `ISignal` blocks
only — a widget block (`ISimpleButton`, `ISwitch`…) declares a gesture, and a
gesture is not replayed. The app never sees the bit: the relay rebuilds the
frame without it.

`InstantIoT.write(I5, 23.4f)` on the wire, 14 bytes:

```
AA 01 09 00   00 01 05 20 03   33 33 BB 41   F3
│  │  │       │  │  │  │  │    └ 23.4f LE    └ CRC8 of the 9 body bytes
│  │  LEN=9   │  │  │  │  TAG float
│  VER        │  │  │  TYPE signal
start         │  │  address I5
              │  WID_LEN
              DEV_COUNT
```

More examples — every byte, CRC included:

```
write(I0, true)        AA 01 06 00  00 01 00 20 01  01           33
write(I2, -7)          AA 01 09 00  00 01 02 20 02  F9 FF FF FF  FF
write(I3, "OK")        AA 01 07 00  00 01 03 20 04  4F 4B        84
restore of I5 = 23.4f  AA 01 09 00  00 01 05 20 83  33 33 BB 41  64
```

Both directions use the same frame. The app writes a slider's value to `I4`
exactly as the device writes a temperature to `I1`; the receiver decides what
to do with it. A frame whose `DEV_COUNT` is not `0`, whose `WID_LEN` is not
`1`, or whose `TYPE` is not `0x20` is not a signal frame for a device: the
library counts it (`InstantIoT.ignoredFrames()`) and drops it.

---

## 4. The heartbeat — `TYPE = 0xFE`

```
AA 01 04 00   00 00 FE 00   C2
```

`DEV_COUNT = 0`, `WID_LEN = 0`, `TYPE = 0xFE`, `TAG = 0`, no payload. The
device sends it every *heartbeat interval* on a link that leads to a server
(Cloud, self-hosted). The server never forwards it; receiving it resets the
socket's read timeout. It has no meaning in direct mode — over the device's own
Wi-Fi, Bluetooth or serial there is no server to reassure — and the library
does not send it there.

---

## 5. Links that lead to a server

### Transport

Plain TCP or TLS. Defaults of the library: `instantiot.cloud:9443` (TLS) or
`:9001` (plaintext) for the Cloud; a self-hosted server listens on its
`tcp.port` (9001 by default), plaintext, or its TLS port with
`MyServer(...).secure()`.

### Handshake

The first bytes on a fresh connection, before any frame:

```
PAYLOAD_LEN (1 byte) │ PAYLOAD (PAYLOAD_LEN bytes, ASCII)
```

`PAYLOAD` is `"<token>"` — or `"<token>:<heartbeatMs>"` to announce the
heartbeat interval. The token is the device's identity, given once by the app
when the device is registered. Without the announcement the server keeps a
90-second silence timeout; with it, the timeout is `heartbeatMs × 2.5`, held
between 2 s and 120 s. The library announces 5000 ms by default and bounds
what a sketch asks for to 1 s … 48 s.

A connection the server accepts and drops within seconds is a refused token.

### After the handshake

The device sends signal frames and heartbeats; the server sends signal frames
(live writes from apps, and restores with the `0x80` bit on reconnect). The
device never puts its identifier in a frame — the connection already proved
it. Toward the apps, the relay stamps the identifier in:

```
AA 01 0C 00   01 02 74 74   01 05 20 03  33 33 BB 41   B1      ← I5 = 23.4f from device "tt"
              └ DEV_COUNT=1, "tt"
```

An app targeting one device sends the same shape; the relay strips the device
list and recomputes the CRC before forwarding.

### Rate

The Cloud and the server fuse a device at **50 frames per second**, with a
burst of ten seconds' worth; above that the connection is cut. The library
applies its own ceiling before the wire (`INSTANTIOT_DEFAULT_SIGNAL_RATE`,
10 frames/s, burst 2×) and silently refuses the calls that exceed it —
`write()` returns `false`. Frames from an app toward a device
are fused at 30 per second.

An address the app has not declared is dropped by the server without a word.

---

## 6. Direct links — the app is at the other end

No handshake, no token, no heartbeat, no restore: the frames of §3, and nothing
else. The first frame can go as soon as the link is up.

| Link | How the app finds the device | Bytes |
|---|---|---|
| **Wi-Fi access point** — `AccessPoint(name, password)` | the phone joins the device's network; the device listens on TCP port **8080** (`INSTANT_AP_PORT`), address `192.168.4.1` on the ESP families | raw frames on the socket |
| **Bluetooth LE** — `BLELink(name)` | the device advertises the **Nordic UART Service** `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`; the app writes to `6E400002-…` (app → device) and subscribes to notifications on `6E400003-…` (device → app) | raw frames, split across characteristic writes and notifications as the MTU requires; the receiver reassembles on `0xAA` and `LEN` |
| **Bluetooth Classic** — `BluetoothLink(name)` | Serial Port Profile, after pairing in the phone's settings | raw frames on the RFCOMM stream |
| **Serial module** — `SerialLink(...)` | the phone pairs with, or scans for, the HC-05 / HC-06 / HM-10; the device only sees a UART at **9600 baud** by default | raw frames on the serial line |

On a serial module, the device has no "connected" signal: the library treats
the first received byte as a connection and five seconds of silence
(`INSTANT_SERIAL_TIMEOUT_MS`) as a disconnection.

---

## 7. Writing a client or a port

1. Build frames with the layout of §2 and the CRC of the table there; check
   your CRC against the examples of §3 before anything else.
2. Send `DEV_COUNT = 0`, `WID_LEN = 1`, `TYPE = 0x20`, one of the four tags,
   and a payload of exactly the size the tag says. Text: 48 bytes, no
   terminator.
3. Receive by scanning for `0xAA`, reading `VER`, `LEN`, then `LEN + 1` more
   bytes; verify the CRC; ignore what you do not understand and count it.
4. Toward a server: send the handshake first, then heartbeats at the interval
   you announced. Mask bit 7 of `TAG` before reading the type, and do not
   treat a restore as a fresh gesture.
5. Little-endian everywhere.
