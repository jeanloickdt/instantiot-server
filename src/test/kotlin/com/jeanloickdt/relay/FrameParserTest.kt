/*
 * InstantIoT Server — self-hosted IoT relay for makers.
 * Copyright (C) 2026 Djoufack Tsobeng Jean Loick (InstantIoT)
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

package com.jeanloickdt.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [FrameParser] — the iWidgets v1 binary frame codec.
 *
 * This is the highest-risk untested code in the server: pure byte arithmetic
 * (offsets, little-endian floats, CRC8) on every device frame. A bug here
 * silently corrupts the data of every connected device.
 *
 * Frame layout under test:
 *   AA | VER(01) | LEN(2B LE) | DEV_COUNT | [DEV_LEN|DEV_ID]xN | WID_LEN | WID | TYPE | EVENT | PAYLOAD | CRC8
 */
class FrameParserTest {

    // iWidgets v1 codes (mirror of FrameParser's private constants).
    private val TYPE_GAUGE = 0x03
    private val TYPE_JOYSTICK = 0x04
    private val TYPE_METRIC = 0x07
    private val TYPE_CHART = 0x09
    private val TYPE_HSLIDER = 0x0A
    private val TYPE_HEARTBEAT = 0xFE
    private val EV_SETVALUE = 0x01
    private val EV_ADD_POINT = 0x01
    private val EV_ADD_TIMED_POINT = 0x02
    private val CMD_VALUE_CHANGING = 0x10
    private val CMD_POSITION_CHANGED = 0x01
    private val EV_PRESS = 0x05 // arbitrary non-numeric event

    // ============================================================
    // CRC8 / isValid
    // ============================================================

    /**
     * Independent known-answer test of the server's CRC8 via the public API.
     * The CRC-8/SMBUS (poly 0x07, init 0, no reflection) check value over the
     * ASCII string "123456789" is the well-known constant 0xF4. If isValid
     * accepts this hand-built frame, the production computeCrc8 matches the
     * standard — verified WITHOUT calling the private function.
     */
    @Test
    fun `isValid accepts the CRC-8 SMBUS known-answer frame`() {
        val body = "123456789".toByteArray()           // 9 bytes
        val frame = byteArrayOf(0xAA.toByte(), 0x01, 0x09, 0x00) + body + byteArrayOf(0xF4.toByte())
        assertTrue(FrameParser.isValid(frame), "CRC-8/SMBUS check value 0xF4 must validate")
    }

    @Test
    fun `isValid rejects a wrong CRC`() {
        val body = "123456789".toByteArray()
        val frame = byteArrayOf(0xAA.toByte(), 0x01, 0x09, 0x00) + body + byteArrayOf(0xF5.toByte())
        assertFalse(FrameParser.isValid(frame))
    }

    @Test
    fun `isValid accepts a well-formed device frame`() {
        val frame = deviceFrame("W1", TYPE_GAUGE, EV_SETVALUE, floatLE(23.5f))
        assertTrue(FrameParser.isValid(frame))
    }

    @Test
    fun `isValid rejects a bad sync byte`() {
        val frame = deviceFrame("W1", TYPE_GAUGE, EV_SETVALUE, floatLE(1f))
        frame[0] = 0xBB.toByte()
        assertFalse(FrameParser.isValid(frame))
    }

    @Test
    fun `isValid rejects an unknown version`() {
        val frame = deviceFrame("W1", TYPE_GAUGE, EV_SETVALUE, floatLE(1f))
        frame[1] = 0x02
        assertFalse(FrameParser.isValid(frame))
    }

    @Test
    fun `isValid rejects a frame shorter than the minimum`() {
        assertFalse(FrameParser.isValid(byteArrayOf(0xAA.toByte(), 0x01, 0x00)))
        assertFalse(FrameParser.isValid(ByteArray(0)))
    }

    @Test
    fun `isValid rejects a truncated frame whose declared length exceeds its size`() {
        val frame = deviceFrame("W1", TYPE_GAUGE, EV_SETVALUE, floatLE(1f))
        val truncated = frame.copyOfRange(0, frame.size - 2) // drop CRC + 1 body byte
        assertFalse(FrameParser.isValid(truncated))
    }

    @Test
    fun `isValid rejects a corrupted body`() {
        val frame = deviceFrame("W1", TYPE_GAUGE, EV_SETVALUE, floatLE(23.5f))
        frame[6] = (frame[6] + 1).toByte() // flip a payload byte → CRC mismatch
        assertFalse(FrameParser.isValid(frame))
    }

    // ============================================================
    // extractWidgetId
    // ============================================================

    @Test
    fun `extractWidgetId reads the widget id of a device frame`() {
        val frame = deviceFrame("temp-sensor", TYPE_GAUGE, EV_SETVALUE, floatLE(20f))
        assertEquals("temp-sensor", FrameParser.extractWidgetId(frame))
    }

    @Test
    fun `extractWidgetId skips the device section of an app frame`() {
        val frame = appFrame(listOf("dev-uuid-1", "dev-uuid-2"), "W42", TYPE_GAUGE, EV_SETVALUE, floatLE(1f))
        assertEquals("W42", FrameParser.extractWidgetId(frame))
    }

    @Test
    fun `extractWidgetId returns null on a malformed frame`() {
        assertNull(FrameParser.extractWidgetId(byteArrayOf(0xAA.toByte(), 0x01)))
    }

    // ============================================================
    // extractType
    // ============================================================

    @Test
    fun `extractType reads the type byte`() {
        val frame = deviceFrame("W1", TYPE_METRIC, EV_SETVALUE, floatLE(1f))
        assertEquals(TYPE_METRIC, FrameParser.extractType(frame))
    }

    @Test
    fun `extractType recognizes the heartbeat type`() {
        val frame = deviceFrame("hb", TYPE_HEARTBEAT, 0x00, ByteArray(0))
        assertEquals(0xFE, FrameParser.extractType(frame))
    }

    @Test
    fun `extractType returns null on a malformed frame`() {
        assertNull(FrameParser.extractType(byteArrayOf(0xAA.toByte(), 0x01, 0x00)))
    }

    // ============================================================
    // extractPayload
    // ============================================================

    @Test
    fun `extractPayload returns the raw payload bytes`() {
        val payload = floatLE(42.0f)
        val frame = deviceFrame("W1", TYPE_GAUGE, EV_SETVALUE, payload)
        assertContentEquals(payload, FrameParser.extractPayload(frame))
    }

    @Test
    fun `extractPayload returns an empty array when there is no payload`() {
        val frame = deviceFrame("W1", TYPE_GAUGE, EV_PRESS, ByteArray(0))
        val out = FrameParser.extractPayload(frame)
        assertNotNull(out)
        assertEquals(0, out.size)
    }

    @Test
    fun `extractPayload returns null on a malformed frame`() {
        assertNull(FrameParser.extractPayload(byteArrayOf(0xAA.toByte(), 0x01)))
    }

    // ============================================================
    // extractNumericValue
    // ============================================================

    @Test
    fun `extractNumericValue decodes a gauge SETVALUE`() {
        val frame = deviceFrame("g", TYPE_GAUGE, EV_SETVALUE, floatLE(23.5f))
        val sample = FrameParser.extractNumericValue(frame)
        assertNotNull(sample)
        assertNull(sample.seriesId)
        assertEquals(23.5, sample.value)
    }

    @Test
    fun `extractNumericValue decodes a metric SETVALUE`() {
        val frame = deviceFrame("m", TYPE_METRIC, EV_SETVALUE, floatLE(-7.25f))
        val sample = FrameParser.extractNumericValue(frame)
        assertNotNull(sample)
        assertEquals(-7.25, sample.value)
    }

    @Test
    fun `extractNumericValue decodes a chart ADD_POINT with its series id`() {
        // payload = seriesId(len + bytes) + y(4B)
        val payload = byteArrayOf(2) + "L1".toByteArray() + floatLE(99.0f)
        val frame = deviceFrame("chart", TYPE_CHART, EV_ADD_POINT, payload)
        val sample = FrameParser.extractNumericValue(frame)
        assertNotNull(sample)
        assertEquals("L1", sample.seriesId)
        assertEquals(99.0, sample.value)
    }

    @Test
    fun `extractNumericValue decodes a chart TIMED_POINT keeping y and skipping x`() {
        // payload = seriesId(len + bytes) + x(4B) + y(4B) → we keep y
        val payload = byteArrayOf(2) + "L1".toByteArray() + floatLE(1000f) + floatLE(42.0f)
        val frame = deviceFrame("chart", TYPE_CHART, EV_ADD_TIMED_POINT, payload)
        val sample = FrameParser.extractNumericValue(frame)
        assertNotNull(sample)
        assertEquals("L1", sample.seriesId)
        assertEquals(42.0, sample.value)
    }

    @Test
    fun `extractNumericValue returns null for a non-numeric widget`() {
        // a button press carries no analog value
        val frame = deviceFrame("btn", 0x01 /* button */, EV_PRESS, ByteArray(0))
        assertNull(FrameParser.extractNumericValue(frame))
    }

    @Test
    fun `extractNumericValue returns null when the float is truncated`() {
        val frame = deviceFrame("g", TYPE_GAUGE, EV_SETVALUE, byteArrayOf(1, 2)) // only 2 of 4 float bytes
        assertNull(FrameParser.extractNumericValue(frame))
    }

    // ============================================================
    // extractDeviceIds
    // ============================================================

    @Test
    fun `extractDeviceIds reads the target uuids of an app frame`() {
        val frame = appFrame(listOf("uuid-a", "uuid-b"), "W1", TYPE_HSLIDER, CMD_VALUE_CHANGING, floatLE(0.5f))
        assertEquals(listOf("uuid-a", "uuid-b"), FrameParser.extractDeviceIds(frame))
    }

    @Test
    fun `extractDeviceIds returns empty for a device frame`() {
        val frame = deviceFrame("W1", TYPE_GAUGE, EV_SETVALUE, floatLE(1f))
        assertTrue(FrameParser.extractDeviceIds(frame).isEmpty())
    }

    @Test
    fun `extractDeviceIds returns empty on a malformed frame`() {
        assertTrue(FrameParser.extractDeviceIds(byteArrayOf(0xAA.toByte(), 0x01)).isEmpty())
    }

    // ============================================================
    // isStreamingCommand
    // ============================================================

    @Test
    fun `isStreamingCommand is true for a slider drag`() {
        val frame = appFrame(listOf("d"), "sld", TYPE_HSLIDER, CMD_VALUE_CHANGING, floatLE(0.5f))
        assertTrue(FrameParser.isStreamingCommand(frame))
    }

    @Test
    fun `isStreamingCommand is true for a joystick drag`() {
        val frame = appFrame(listOf("d"), "joy", TYPE_JOYSTICK, CMD_POSITION_CHANGED, floatLE(0.5f))
        assertTrue(FrameParser.isStreamingCommand(frame))
    }

    @Test
    fun `isStreamingCommand is false for a discrete command`() {
        val frame = appFrame(listOf("d"), "g", TYPE_GAUGE, EV_SETVALUE, floatLE(1f))
        assertFalse(FrameParser.isStreamingCommand(frame))
    }

    @Test
    fun `isStreamingCommand is false on a malformed frame`() {
        assertFalse(FrameParser.isStreamingCommand(byteArrayOf(0xAA.toByte(), 0x01)))
    }

    // ============================================================
    // trimDeviceHeader (app → device)
    // ============================================================

    @Test
    fun `trimDeviceHeader produces a valid DEV_COUNT zero frame preserving widget and payload`() {
        val payload = floatLE(0.75f)
        val appFrame = appFrame(listOf("uuid-a", "uuid-b"), "slider1", TYPE_HSLIDER, CMD_VALUE_CHANGING, payload)

        val trimmed = FrameParser.trimDeviceHeader(appFrame)
        assertNotNull(trimmed)

        // the trimmed frame must be self-consistent (CRC recomputed)
        assertTrue(FrameParser.isValid(trimmed), "trimmed frame must pass CRC validation")
        // DEV_COUNT byte (first body byte after the 4-byte header) must be 0
        assertEquals(0, trimmed[4].toInt())
        // no target devices remain
        assertTrue(FrameParser.extractDeviceIds(trimmed).isEmpty())
        // widget id + payload survive the trim intact
        assertEquals("slider1", FrameParser.extractWidgetId(trimmed))
        assertContentEquals(payload, FrameParser.extractPayload(trimmed))
    }

    @Test
    fun `trimDeviceHeader returns null on a malformed frame`() {
        assertNull(FrameParser.trimDeviceHeader(byteArrayOf(0xAA.toByte(), 0x01)))
    }

    // ============================================================
    // hashDeviceToken / encodePayloadToBase64
    // ============================================================

    @Test
    fun `hashDeviceToken matches the known SHA-256 of test`() {
        // SHA-256("test") — independent known answer
        assertEquals(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            FrameParser.hashDeviceToken("test")
        )
    }

    @Test
    fun `hashDeviceToken is deterministic and distinct per token`() {
        assertEquals(FrameParser.hashDeviceToken("abc"), FrameParser.hashDeviceToken("abc"))
        assertTrue(FrameParser.hashDeviceToken("abc") != FrameParser.hashDeviceToken("abd"))
        assertEquals(64, FrameParser.hashDeviceToken("abc").length) // 32 bytes hex
    }

    @Test
    fun `encodePayloadToBase64 encodes bytes`() {
        assertEquals("AAEC", FrameParser.encodePayloadToBase64(byteArrayOf(0, 1, 2)))
    }

    // ============================================================
    // Frame builders (test fixtures)
    // ============================================================

    /** CRC-8/SMBUS, poly 0x07, init 0, no reflection — mirrors FrameParser.computeCrc8. */
    private fun crc8(data: ByteArray): Byte {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return (crc and 0xFF).toByte()
    }

    /** 4-byte little-endian IEEE-754 encoding of a float. */
    private fun floatLE(f: Float): ByteArray {
        val bits = f.toRawBits()
        return byteArrayOf(
            (bits and 0xFF).toByte(),
            ((bits ushr 8) and 0xFF).toByte(),
            ((bits ushr 16) and 0xFF).toByte(),
            ((bits ushr 24) and 0xFF).toByte()
        )
    }

    /** Wraps a body into a full frame: AA | VER | LEN(LE) | body | CRC8. */
    private fun wrapFrame(body: ByteArray): ByteArray {
        val len = body.size
        return byteArrayOf(
            0xAA.toByte(), 0x01,
            (len and 0xFF).toByte(), ((len ushr 8) and 0xFF).toByte()
        ) + body + byteArrayOf(crc8(body))
    }

    /** Device→server body: DEV_COUNT(0) | WID_LEN | WID | TYPE | EVENT | PAYLOAD. */
    private fun deviceFrame(widgetId: String, type: Int, event: Int, payload: ByteArray): ByteArray {
        val wid = widgetId.toByteArray()
        val body = byteArrayOf(0x00) +
            byteArrayOf(wid.size.toByte()) + wid +
            byteArrayOf(type.toByte(), event.toByte()) +
            payload
        return wrapFrame(body)
    }

    /** App→server body: DEV_COUNT(N) | [DEV_LEN|DEV_ID]xN | WID_LEN | WID | TYPE | EVENT | PAYLOAD. */
    private fun appFrame(
        deviceIds: List<String>,
        widgetId: String,
        type: Int,
        event: Int,
        payload: ByteArray
    ): ByteArray {
        var dev = byteArrayOf(deviceIds.size.toByte())
        for (d in deviceIds) {
            val db = d.toByteArray()
            dev = dev + byteArrayOf(db.size.toByte()) + db
        }
        val wid = widgetId.toByteArray()
        val body = dev +
            byteArrayOf(wid.size.toByte()) + wid +
            byteArrayOf(type.toByte(), event.toByte()) +
            payload
        return wrapFrame(body)
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray?) {
        assertNotNull(actual)
        assertTrue(expected.contentEquals(actual), "byte arrays differ")
    }
}

/**
 * Contrôle croisé du banc de charge : ces octets sortent TELS QUELS du codec
 * Python (`banc-de-charge/protocol.py`). Si ce test tombe, le générateur émet
 * des trames que le serveur rejette — et un banc qui mesure des trames
 * rejetées mesure le vide, sans jamais le dire.
 */
class LoadHarnessFrameCompatTest {

    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `le battement du generateur Python est accepte`() {
        val frame = hex("aa 01 04 00 00 00 fe 00 c2")
        assertTrue(FrameParser.isValid(frame), "trame rejetée par isValid")
        assertEquals(0xFE, FrameParser.extractType(frame))
    }

    @Test
    fun `une mesure du generateur Python est accepte et lisible`() {
        val frame = hex("aa 01 0d 00 00 05 74 65 6d 70 31 03 01 00 00 b4 41 95")
        assertTrue(FrameParser.isValid(frame), "trame rejetée par isValid")
        // extractWidgetId ne doit PAS renvoyer null : c'est ce retour qui fait
        // sortir handleDeviceFrame avant toute écriture.
        assertEquals("temp1", FrameParser.extractWidgetId(frame))
        assertEquals(0x03, FrameParser.extractType(frame))
    }
}
