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

package com.jeanloickdt.signal

import com.jeanloickdt.relay.FrameParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The SIGNAL frame rides the layout that already exists — that is the whole
 * point of the decision, so these tests guard the cohabitation more than the
 * encoding: the existing parser must keep working on signal frames, and gesture
 * frames must be untouched.
 */
class SignalFrameTest {

    private fun floatFrame(addr: Int, v: Float) =
        SignalFrame.build(addr, SignalFrame.TAG_FLOAT, SignalFrame.floatBytes(v))

    // ── La cohabitation ───────────────────────────────────────────────────

    @Test
    fun `a signal frame is valid for the EXISTING parser`() {
        val frame = floatFrame(5, 23.4f)
        assertTrue(FrameParser.isValid(frame), "same header, same CRC polynomial — nothing forked")
        assertEquals(SignalFrame.TYPE_SIGNAL, FrameParser.extractType(frame))
    }

    @Test
    fun `the type code collides with nothing`() {
        // Widget types stop at 0x11 (EmergencyButton); the heartbeat is 0xFE.
        assertTrue(SignalFrame.TYPE_SIGNAL > 0x11)
        assertTrue(SignalFrame.TYPE_SIGNAL < 0xFE)
    }

    @Test
    fun `a gesture frame is NOT read as a signal`() {
        // AA VER LEN | DEV_COUNT=0 | WID_LEN=4 "btn1" | TYPE=0x01 | EV=0x04 | 0x01 | CRC
        // The frame a SimpleButton toggle produces today — untouched by 2.0.
        val body = byteArrayOf(0x00, 0x04) + "btn1".toByteArray() + byteArrayOf(0x01, 0x04, 0x01)
        val frame = byteArrayOf(0xAA.toByte(), 0x01, body.size.toByte(), 0x00) + body +
            byteArrayOf(crc8(body))

        assertTrue(FrameParser.isValid(frame))
        assertTrue(!SignalFrame.isSignal(frame), "a gesture must never be mistaken for a value")
        assertEquals("btn1", FrameParser.extractWidgetId(frame))
    }

    // ── L'adresse ─────────────────────────────────────────────────────────

    @Test
    fun `the address rides the WID slot on one byte`() {
        assertEquals(0, SignalFrame.address(floatFrame(0, 1f)))
        assertEquals(5, SignalFrame.address(floatFrame(5, 1f)))
        assertEquals(255, SignalFrame.address(floatFrame(255, 1f)))
    }

    @Test
    fun `the whole frame is 14 bytes for a float — against 19 for gauge1`() {
        // header 4 + body 9 (DEV_COUNT, WID_LEN, addr, TYPE, TAG, 4B value) + CRC
        assertEquals(14, floatFrame(5, 23.4f).size)
        // The same measure named "gauge1" costs 19: the six characters of the
        // id become one byte. Five widgets on one sensor: 95 bytes today
        // against 14 — the real saving is the frame COUNT, not the frame size.
    }

    @Test
    fun `a multi-byte WID is not an address — the frame is refused, never guessed`() {
        val body = byteArrayOf(0x00, 0x04) + "btn1".toByteArray() +
            byteArrayOf(SignalFrame.TYPE_SIGNAL.toByte(), SignalFrame.TAG_FLOAT.toByte()) +
            SignalFrame.floatBytes(1f)
        val frame = byteArrayOf(0xAA.toByte(), 0x01, body.size.toByte(), 0x00) + body +
            byteArrayOf(crc8(body))

        assertNull(SignalFrame.address(frame), "guessing an address would corrupt someone's data")
    }

    // ── Les valeurs typées ────────────────────────────────────────────────

    @Test
    fun `float, int and bool all decode to a numeric sample`() {
        assertEquals(23.4, SignalFrame.numericValue(floatFrame(1, 23.4f))!!, 0.0001)

        val i = SignalFrame.build(2, SignalFrame.TAG_INT, byteArrayOf(42, 0, 0, 0))
        assertEquals(42.0, SignalFrame.numericValue(i)!!, 0.0)

        val on = SignalFrame.build(3, SignalFrame.TAG_BOOL, byteArrayOf(0x01))
        val off = SignalFrame.build(3, SignalFrame.TAG_BOOL, byteArrayOf(0x00))
        assertEquals(1.0, SignalFrame.numericValue(on)!!, 0.0)
        assertEquals(0.0, SignalFrame.numericValue(off)!!, 0.0)
    }

    @Test
    fun `a string has no numeric sample — it never feeds the cascade`() {
        val s = SignalFrame.build(4, SignalFrame.TAG_STRING, "OK".toByteArray())
        assertNull(SignalFrame.numericValue(s),
            "averaging a text is meaningless — a string signal keeps only its last value")
        assertEquals(4, SignalFrame.address(s), "…but it is still routable")
    }

    @Test
    fun `a truncated payload yields no value instead of garbage`() {
        val short = SignalFrame.build(1, SignalFrame.TAG_FLOAT, byteArrayOf(0x01, 0x02))
        assertNull(SignalFrame.numericValue(short))
    }

    @Test
    fun `the payload the existing extractor returns is the value itself`() {
        val frame = floatFrame(7, 1f)
        assertEquals(4, FrameParser.extractPayload(frame)!!.size,
            "TYPE and EVENT are skipped by the existing parser — the tag costs no extra byte")
    }

    // ── Le contrat avec la bibliotheque Arduino ───────────────────────────

    /**
     * The exact bytes `InstantIoT.write(I5, 23.4f)` emits from
     * `BinaryCodec::encodeSignal`.
     *
     * Golden on purpose: the board and the server are compiled from different
     * repositories by different toolchains, and nothing else would catch a
     * silent divergence — a reordered field or a flipped endianness would
     * simply produce wrong values in somebody's history.
     */
    @Test
    fun `the bytes the Arduino library emits are the bytes the server reads`() {
        val expected = byteArrayOf(
            0xAA.toByte(), 0x01,             // SYNC, VERSION
            0x09, 0x00,                      // LEN = 9, little-endian
            0x00,                            // DEV_COUNT — the relay knows the board
            0x01, 0x05,                      // WID_LEN = 1, address = I5
            0x20,                            // TYPE_SIGNAL
            0x03,                            // TAG_FLOAT
            0x33, 0x33, 0xBB.toByte(), 0x41, // 23.4f, little-endian
            0xF3.toByte()                    // CRC8, polynomial 0x07
        )

        val built = SignalFrame.build(5, SignalFrame.TAG_FLOAT, SignalFrame.floatBytes(23.4f))
        assertEquals(expected.toList(), built.toList(),
            "the server's builder and the library's encodeSignal must agree byte for byte")

        assertTrue(FrameParser.isValid(expected))
        assertEquals(5, SignalFrame.address(expected))
        assertEquals(23.4, SignalFrame.numericValue(expected)!!, 0.0001)
    }

    private fun crc8(bytes: ByteArray): Byte {
        var crc = 0
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc.toByte()
    }
}
