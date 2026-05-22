/*
 * InstantIoT Server — self-hosted IoT relay for makers.
 * Copyright (C) 2026 InstantIoT
 * Author: Djoufack Tsobeng Jean Loick (@jeanloick_dt)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

// relay/FrameParser.kt
package com.jeanloickdt.relay

import java.security.MessageDigest
import java.util.Base64

/**
 * Parser of iWidgets v1 binary frames
 *
 * Complete frame format :
 * AA | VER(01) | LEN(2B LE) | DEV_COUNT | [DEV_LEN|DEV_ID]×N | WID_LEN | WID | TYPE | EVENT | PAYLOAD... | CRC8
 *
 * The server is a dumb relay — it never reads TYPE, EVENT or the PAYLOAD content.
 * It extracts only what it needs to route and store.
 *
 * Two directions :
 *   App → Server → Device : frame with DEV_COUNT + device UUIDs → trim → TCP relay
 *   Device → Server → App : frame with DEV_COUNT=0 → extract widgetId + payload → WebSocket broadcast
 */
object FrameParser {

    // Minimum size of a valid frame
    // AA(1) + VER(1) + LEN(2) + body(min 1) + CRC(1) = 6
    private const val MIN_FRAME_SIZE = 6

    private const val SYNC_BYTE: Int    = 0xAA
    private const val VERSION_BYTE: Int = 0x01

    // Fixed header : AA + VER + LEN(2) = 4 bytes
    private const val FIXED_HEADER_SIZE = 4

    // ================================================================
    // VALIDATION
    // ================================================================

    /**
     * Validates the frame — checks sync, version, minimum size and CRC8.
     * The CRC adds an application-level protection layer on top of TCP.
     */
    fun isValid(frame: ByteArray): Boolean {
        if (frame.size < MIN_FRAME_SIZE) return false
        if (frame[0].toInt() and 0xFF != SYNC_BYTE) return false
        if (frame[1].toInt() and 0xFF != VERSION_BYTE) return false

        // verify the CRC8 — computed over the body (between the fixed header and the final CRC)
        val bodyLength = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        val expectedFrameSize = FIXED_HEADER_SIZE + bodyLength + 1 // +1 for CRC
        if (frame.size < expectedFrameSize) return false

        val body = frame.copyOfRange(FIXED_HEADER_SIZE, FIXED_HEADER_SIZE + bodyLength)
        val computedCrc = computeCrc8(body)
        val receivedCrc = frame[FIXED_HEADER_SIZE + bodyLength].toInt() and 0xFF

        return computedCrc.toInt() == receivedCrc
    }

    // ================================================================
    // EXTRACTION — Device → Server → App
    // The ESP sends DEV_COUNT=0 — no target device in this direction
    // ================================================================

    /**
     * Extracts the widget ID from the frame sent by the ESP.
     * Position : after AA | VER | LEN | DEV_COUNT(0) | WID_LEN | WID
     */
    fun extractWidgetId(frame: ByteArray): String? {
        return try {
            var offset = FIXED_HEADER_SIZE

            // skip DEV_COUNT + [DEV_LEN|DEV_ID]×N
            val deviceCount = frame[offset++].toInt() and 0xFF
            repeat(deviceCount) {
                val deviceIdLength = frame[offset++].toInt() and 0xFF
                offset += deviceIdLength
            }

            // WID_LEN + WID
            val widgetIdLength = frame[offset++].toInt() and 0xFF
            frame.decodeToString(offset, offset + widgetIdLength)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts the TYPE byte from a device→server frame.
     *
     * Position : after AA | VER | LEN | DEV_COUNT(0) | WID_LEN | WID
     *
     * Used to distinguish application frames (widget updates)
     * from service frames such as the heartbeat (`TYPE = 0xFE`).
     */
    fun extractType(frame: ByteArray): Int? {
        return try {
            var offset = FIXED_HEADER_SIZE
            // skip DEV_COUNT + [DEV_LEN|DEV_ID]×N
            val deviceCount = frame[offset++].toInt() and 0xFF
            repeat(deviceCount) {
                val deviceIdLength = frame[offset++].toInt() and 0xFF
                offset += deviceIdLength
            }
            // skip WID_LEN + WID
            val widgetIdLength = frame[offset++].toInt() and 0xFF
            offset += widgetIdLength
            // TYPE
            frame[offset].toInt() and 0xFF
        } catch (e: Exception) {
            null
        }
    }

    // ================================================================
    // STREAMING CLASSIFICATION — backpressure DeviceOutbox
    // ================================================================

    // iWidgets v1 codes — synchronized with the app `BinaryProtocolCodes.kt`.
    // If these values change on the app side, they must be updated here.
    private const val TYPE_GAUGE: Int    = 0x03
    private const val TYPE_JOYSTICK: Int = 0x04
    private const val TYPE_HLEVEL: Int   = 0x05
    private const val TYPE_VLEVEL: Int   = 0x06
    private const val TYPE_METRIC: Int   = 0x07
    private const val TYPE_CHART: Int    = 0x09
    private const val TYPE_HSLIDER: Int  = 0x0A
    private const val TYPE_VSLIDER: Int  = 0x0B

    private const val CMD_POSITION_CHANGED: Int = 0x01  // joystick drag
    private const val CMD_VALUE_CHANGING: Int   = 0x10  // slider drag

    // Device→App event codes (0x01..0x0E)
    private const val EV_SETVALUE: Int        = 0x01  // gauge/hlevel/vlevel/metric/slider
    private const val EV_UPDATE: Int          = 0x03  // gauge/hlevel/vlevel : value+min+max
    private const val EV_ADD_POINT: Int       = 0x01  // chart : seriesId + y  (same code as EV_SETVALUE, disambiguated by TYPE)
    private const val EV_ADD_TIMED_POINT: Int = 0x02  // chart : seriesId + x + y

    /**
     * Identifies the commands emitted by a **continuous gesture** (slider
     * drag, active joystick). These frames arrive at 20 Hz for the whole
     * duration of the gesture and saturate the TCP → ESP pipe if all of
     * them are transmitted.
     *
     * Streaming currently recognized :
     * - HSlider `CMD_VALUE_CHANGING`    (type=0x0A, event=0x10)
     * - VSlider `CMD_VALUE_CHANGING`    (type=0x0B, event=0x10)
     * - Joystick `CMD_POSITION_CHANGED` (type=0x04, event=0x01)
     *
     * Any other frame is considered **discrete** and must arrive
     * intact (Press, Release, Toggle, final ValueChanged, DragStarted,
     * DragEnded, joystick Released, SetValue switch, etc.).
     *
     * Called on the **original** frame (before trim) — the format is
     * identical on the type/event side.
     *
     * @return `true` if streaming (can be dropped under backpressure),
     *         `false` otherwise or if the frame is invalid/incomplete.
     */
    fun isStreamingCommand(frame: ByteArray): Boolean {
        return try {
            var offset = FIXED_HEADER_SIZE

            // skip DEV_COUNT + [DEV_LEN|DEV_ID]×N
            val deviceCount = frame[offset++].toInt() and 0xFF
            repeat(deviceCount) {
                val deviceIdLength = frame[offset++].toInt() and 0xFF
                offset += deviceIdLength
            }

            // skip WID_LEN + WID
            val widgetIdLength = frame[offset++].toInt() and 0xFF
            offset += widgetIdLength

            // TYPE
            val type = frame[offset++].toInt() and 0xFF
            // EVENT
            val event = frame[offset].toInt() and 0xFF

            when (type) {
                TYPE_HSLIDER, TYPE_VSLIDER -> event == CMD_VALUE_CHANGING
                TYPE_JOYSTICK              -> event == CMD_POSITION_CHANGED
                else                       -> false
            }
        } catch (e: Exception) {
            // Malformed frame → safe default : non-streaming, no drop
            false
        }
    }

    /**
     * Extracts the raw PAYLOAD from the frame sent by the ESP.
     * The server does not understand the content — opaque bytes stored as base64.
     * Position : after WID | TYPE | EVENT — everything left before CRC
     */
    fun extractPayload(frame: ByteArray): ByteArray? {
        return try {
            var offset = FIXED_HEADER_SIZE

            // LEN — body size (little-endian)
            val bodyLength = ((frame[2].toInt() and 0xFF)) or
                    ((frame[3].toInt() and 0xFF) shl 8)

            // end of body = FIXED_HEADER_SIZE + bodyLength
            val bodyEnd = FIXED_HEADER_SIZE + bodyLength

            // skip DEV_COUNT + [DEV_LEN|DEV_ID]×N
            val deviceCount = frame[offset++].toInt() and 0xFF
            repeat(deviceCount) {
                val deviceIdLength = frame[offset++].toInt() and 0xFF
                offset += deviceIdLength
            }

            // skip WID_LEN + WID
            val widgetIdLength = frame[offset++].toInt() and 0xFF
            offset += widgetIdLength

            // skip TYPE + EVENT — the server does not read them
            offset += 2

            // PAYLOAD = everything left before CRC
            val payloadLength = bodyEnd - offset
            if (payloadLength <= 0) ByteArray(0)
            else frame.copyOfRange(offset, offset + payloadLength)
        } catch (e: Exception) {
            null
        }
    }

    // ================================================================
    // NUMERIC EXTRACTION — for the history of analog widgets
    // ================================================================

    /**
     * Numeric sample decoded from a device→app frame.
     *
     * @param seriesId For a multi-series chart ("line1"). `null` for
     *                 simple widgets (gauge, metric, level, slider).
     * @param value    The main numeric value (always a double).
     */
    data class NumericSample(
        val seriesId: String?,
        val value: Double
    )

    /**
     * Decodes a device→app frame and extracts a numeric sample
     * if the event contains an analog `value` (float).
     *
     * Supported :
     * - Gauge / HLevel / VLevel : EV_SETVALUE (4B float), EV_UPDATE (value+min+max, we take value)
     * - Metric                  : EV_SETVALUE
     * - HSlider / VSlider       : EV_SETVALUE (device→app, rare but possible)
     * - Chart                   : EV_ADD_POINT (seriesId + y), EV_ADD_TIMED_POINT (seriesId + x + y, we take y)
     *
     * Anything else (buttons, switches, joystick push, segSwitch, etc.) → `null`
     * → no numeric history for this type.
     *
     * @return The decoded sample, or `null` if non-extractable / invalid frame.
     */
    fun extractNumericValue(frame: ByteArray): NumericSample? {
        return try {
            var offset = FIXED_HEADER_SIZE

            // skip DEV_COUNT + [DEV_LEN|DEV_ID]×N (device→app : devCount=0 normally)
            val deviceCount = frame[offset++].toInt() and 0xFF
            repeat(deviceCount) {
                val deviceIdLength = frame[offset++].toInt() and 0xFF
                offset += deviceIdLength
            }

            // skip WID_LEN + WID
            val widgetIdLength = frame[offset++].toInt() and 0xFF
            offset += widgetIdLength

            // TYPE + EVENT
            val type  = frame[offset++].toInt() and 0xFF
            val event = frame[offset++].toInt() and 0xFF

            // offset now points to the start of the payload.
            // All numeric payloads are self-sufficient
            // (no need to know the end of the body for these cases).
            when (type) {
                TYPE_GAUGE, TYPE_HLEVEL, TYPE_VLEVEL -> when (event) {
                    EV_SETVALUE -> readFloatAt(frame, offset)?.let { NumericSample(null, it.toDouble()) }
                    EV_UPDATE   -> readFloatAt(frame, offset)?.let { NumericSample(null, it.toDouble()) } // value, then min, max — we keep value
                    else        -> null
                }
                TYPE_METRIC -> when (event) {
                    EV_SETVALUE -> readFloatAt(frame, offset)?.let { NumericSample(null, it.toDouble()) }
                    else        -> null
                }
                TYPE_HSLIDER, TYPE_VSLIDER -> when (event) {
                    EV_SETVALUE -> readFloatAt(frame, offset)?.let { NumericSample(null, it.toDouble()) }
                    else        -> null
                }
                TYPE_CHART -> {
                    // Payload format : seriesId (1B len + bytes) + float(s)
                    val seriesIdLen = frame[offset].toInt() and 0xFF
                    val seriesIdStart = offset + 1
                    val seriesIdEnd   = seriesIdStart + seriesIdLen
                    if (seriesIdEnd + 4 > frame.size) return null
                    val seriesId = frame.decodeToString(seriesIdStart, seriesIdEnd)
                    when (event) {
                        EV_ADD_POINT -> {
                            // seriesId + y (4B)
                            readFloatAt(frame, seriesIdEnd)?.let { NumericSample(seriesId, it.toDouble()) }
                        }
                        EV_ADD_TIMED_POINT -> {
                            // seriesId + x (4B) + y (4B) — we keep y (the value), x is used for the time axis on the app side but recordedAt does the job for us
                            if (seriesIdEnd + 8 > frame.size) return null
                            readFloatAt(frame, seriesIdEnd + 4)?.let { NumericSample(seriesId, it.toDouble()) }
                        }
                        else -> null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Reads 4 little-endian bytes as an IEEE 754 Float. `null` if out of bounds. */
    private fun readFloatAt(frame: ByteArray, offset: Int): Float? {
        if (offset + 4 > frame.size) return null
        val bits = (frame[offset].toInt() and 0xFF) or
                ((frame[offset + 1].toInt() and 0xFF) shl 8) or
                ((frame[offset + 2].toInt() and 0xFF) shl 16) or
                ((frame[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    // ================================================================
    // EXTRACTION — App → Server → Device
    // The app sends DEV_COUNT + [DEV_LEN|DEVICE_UUID]×N
    // ================================================================

    /**
     * Extracts the target device UUIDs from the frame sent by the app.
     * These are the public UUIDs of the devices — not the secret tokens.
     */
    fun extractDeviceIds(frame: ByteArray): List<String> {
        return try {
            var offset = FIXED_HEADER_SIZE

            val deviceCount = frame[offset++].toInt() and 0xFF
            val deviceIds = mutableListOf<String>()

            repeat(deviceCount) {
                val deviceIdLength = frame[offset++].toInt() and 0xFF
                deviceIds.add(frame.decodeToString(offset, offset + deviceIdLength))
                offset += deviceIdLength
            }

            deviceIds
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Trims the DEV header of the App → Device frame.
     * Removes DEV_COUNT + [DEV_LEN|DEV_ID]×N and recomputes LEN + CRC.
     *
     * Original frame : AA | VER | LEN | DEV_COUNT | [DEV_UUID]×N | WID_LEN | WID | TYPE | EVENT | PAYLOAD | CRC
     * Trimmed frame  : AA | VER | LEN | 00                        | WID_LEN | WID | TYPE | EVENT | PAYLOAD | CRC
     *
     * The ESP receives a frame identical to Direct mode — DEV_COUNT = 0.
     */
    fun trimDeviceHeader(frame: ByteArray): ByteArray? {
        return try {
            var offset = FIXED_HEADER_SIZE

            // skip DEV_COUNT + [DEV_LEN|DEV_ID]×N to find the start of WID
            val deviceCount = frame[offset++].toInt() and 0xFF
            repeat(deviceCount) {
                val deviceIdLength = frame[offset++].toInt() and 0xFF
                offset += deviceIdLength
            }

            // offset now points to WID_LEN
            val widgetSectionStart = offset

            // body of the trimmed frame : DEV_COUNT=0 + WID_LEN | WID | TYPE | EVENT | PAYLOAD
            val widgetAndPayloadBytes = frame.copyOfRange(widgetSectionStart, frame.size - 1) // without CRC
            val trimmedBody = byteArrayOf(0x00) + widgetAndPayloadBytes // DEV_COUNT = 0

            // recompute LEN
            val trimmedBodyLength = trimmedBody.size
            val trimmedLenBytes = byteArrayOf(
                (trimmedBodyLength and 0xFF).toByte(),
                ((trimmedBodyLength shr 8) and 0xFF).toByte()
            )

            // recompute CRC over the new body
            val trimmedCrc = computeCrc8(trimmedBody)

            // assemble the final trimmed frame
            byteArrayOf(
                frame[0],               // AA
                frame[1],               // VER
                trimmedLenBytes[0],     // LEN low byte
                trimmedLenBytes[1],     // LEN high byte
            ) + trimmedBody + byteArrayOf(trimmedCrc.toByte())
        } catch (e: Exception) {
            null
        }
    }

    // ================================================================
    // CRC8 — recomputation for the trimmed frame
    // Polynomial 0x07 — CRC-8/SMBUS — identical to Crc8.kt on the Android app side
    // ================================================================

    private val crc8Table = ByteArray(256) { index ->
        var crc = index
        repeat(8) {
            crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x07
            else crc shl 1
        }
        (crc and 0xFF).toByte()
    }

    private fun computeCrc8(bodyBytes: ByteArray): UByte {
        var crc = 0
        for (bodyByte in bodyBytes) {
            crc = crc8Table[(crc xor (bodyByte.toInt() and 0xFF)) and 0xFF].toInt() and 0xFF
        }
        return crc.toUByte()
    }

    // ================================================================
    // UTILITIES
    // ================================================================

    /**
     * Encodes a ByteArray to Base64 for SQLite storage.
     * The PAYLOAD is stored opaque — the server never decodes it.
     */
    fun encodePayloadToBase64(payloadBytes: ByteArray): String =
        Base64.getEncoder().encodeToString(payloadBytes)

    /**
     * Computes the SHA-256 of the device token received in clear text during the TCP connection.
     * Used for the lookup in DeviceTable — the clear-text token is never stored.
     */
    fun hashDeviceToken(deviceToken: String): String {
        val hashedBytes = MessageDigest.getInstance("SHA-256").digest(deviceToken.toByteArray())
        return hashedBytes.joinToString("") { "%02x".format(it) }
    }
}