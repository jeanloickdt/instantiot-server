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

/**
 * The SIGNAL frame — `InstantIoT.write(I0, 23.4)` on the wire.
 *
 * ## It rides the EXISTING layout
 *
 * The 2.0 draft moved `TYPE` to the top of the frame, which would have changed
 * the gesture frames too. Gestures are untouched, so a signal is simply a new
 * **type code** in the layout that already exists — exactly how `TYPE_HEARTBEAT`
 * has always cohabited:
 *
 * ```
 * AA │ VER │ LEN │ DEV_COUNT=0 │ WID_LEN=1 │ addr │ TYPE=0x20 │ TAG │ value │ CRC
 * ```
 *
 * Two consequences worth stating:
 *
 * - **The address takes the WID slot, on one byte.** `I0..I255` fits, and the
 *   whole frame lands at ~10 bytes against 18 for `gauge1`.
 * - **The EVENT slot carries the type tag.** No extra byte: the frame already
 *   had a place for it, and `extractType`/`extractPayload` keep working
 *   unchanged on signal frames.
 *
 * The board identity is never on the wire — the connection is authenticated by
 * the device token, so the relay already knows who is speaking.
 */
object SignalFrame {

    /** Free: widget types stop at `0x11`, the heartbeat is `0xFE`. */
    const val TYPE_SIGNAL: Int = 0x20

    // The type tag, in the EVENT slot.
    const val TAG_BOOL: Int   = 0x01
    const val TAG_INT: Int    = 0x02
    const val TAG_FLOAT: Int  = 0x03
    const val TAG_STRING: Int = 0x04

    /** Offset of `DEV_COUNT`, i.e. the first body byte. */
    private const val BODY = 4

    fun isSignal(frame: ByteArray): Boolean =
        FrameParser.extractType(frame) == TYPE_SIGNAL

    /**
     * The address, read from the WID slot.
     *
     * Returns null when the slot is not exactly one byte — a signal frame whose
     * address is malformed is not a signal frame, and guessing would be worse
     * than dropping it.
     */
    fun address(frame: ByteArray): Int? = try {
        var o = BODY
        val deviceCount = frame[o++].toInt() and 0xFF
        repeat(deviceCount) {
            val len = frame[o++].toInt() and 0xFF
            o += len
        }
        val widLen = frame[o++].toInt() and 0xFF
        if (widLen != 1) null else frame[o].toInt() and 0xFF
    } catch (e: Exception) {
        null
    }

    /** The type tag, read from the EVENT slot. */
    fun tag(frame: ByteArray): Int? = try {
        var o = BODY
        val deviceCount = frame[o++].toInt() and 0xFF
        repeat(deviceCount) {
            val len = frame[o++].toInt() and 0xFF
            o += len
        }
        val widLen = frame[o++].toInt() and 0xFF
        o += widLen
        o += 1 // TYPE
        frame[o].toInt() and 0xFF
    } catch (e: Exception) {
        null
    }

    /**
     * The value as a double, when the tag carries a number.
     *
     * `null` for `TAG_STRING` — a text has no numeric sample, so it never feeds
     * the aggregation cascade. It still lands in the signal's last value.
     */
    fun numericValue(frame: ByteArray): Double? {
        val payload = FrameParser.extractPayload(frame) ?: return null
        return when (tag(frame)) {
            TAG_BOOL  -> if (payload.isEmpty()) null else if (payload[0].toInt() != 0) 1.0 else 0.0
            TAG_INT   -> readInt32(payload)?.toDouble()
            TAG_FLOAT -> readFloat(payload)?.toDouble()
            else      -> null
        }
    }

    private fun readInt32(b: ByteArray): Int? {
        if (b.size < 4) return null
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readFloat(b: ByteArray): Float? = readInt32(b)?.let { Float.fromBits(it) }

    /**
     * Builds a SIGNAL frame — used by the tests, and later by the app→board
     * setpoint path. `deviceIds` is empty: the relay knows the board.
     */
    fun build(address: Int, tag: Int, value: ByteArray): ByteArray {
        require(address in 0..255) { "address out of range: $address" }
        val body = byteArrayOf(
            0x00,                 // DEV_COUNT
            0x01,                 // WID_LEN
            address.toByte(),     // the address
            TYPE_SIGNAL.toByte(),
            tag.toByte()
        ) + value
        val crc = crc8(body)
        return byteArrayOf(
            0xAA.toByte(), 0x01,
            (body.size and 0xFF).toByte(),
            ((body.size shr 8) and 0xFF).toByte()
        ) + body + byteArrayOf(crc)
    }

    fun floatBytes(v: Float): ByteArray {
        val bits = v.toRawBits()
        return byteArrayOf(
            (bits and 0xFF).toByte(),
            ((bits shr 8) and 0xFF).toByte(),
            ((bits shr 16) and 0xFF).toByte(),
            ((bits shr 24) and 0xFF).toByte()
        )
    }

    /** Same polynomial as the lib's `Crc8` and the relay's own table: 0x07. */
    private fun crc8(bytes: ByteArray): Byte {
        var crc = 0
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF
                else (crc shl 1) and 0xFF
            }
        }
        return crc.toByte()
    }
}
