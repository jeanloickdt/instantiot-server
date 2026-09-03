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

import com.jeanloickdt.signal.data.SignalTable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * The acceptance rule, on its own.
 *
 * Written against the pure function rather than through the relay on purpose:
 * four times during this rework a sabotage changed nothing because the rule
 * lived inside a socket loop where no test could reach it. A rank is a rank —
 * it deserves to be checkable without a frame, a board or a database.
 */
class SignalTypeCompatTest {

    // ── Widening: always lossless, therefore always accepted ──────────────

    @Test
    fun `a bool fits an int slot`() {
        assertTrue(SignalTypeCompat.accepts(SignalTable.TYPE_INT, SignalFrame.TAG_BOOL))
    }

    @Test
    fun `a bool fits a float slot`() {
        assertTrue(SignalTypeCompat.accepts(SignalTable.TYPE_FLOAT, SignalFrame.TAG_BOOL))
    }

    @Test
    fun `an int fits a float slot`() {
        // The case that made the rule: instant.write(I0, 0) picks the integer
        // overload in the library, and 0 belongs in a float slot.
        assertTrue(SignalTypeCompat.accepts(SignalTable.TYPE_FLOAT, SignalFrame.TAG_INT))
    }

    // ── Same rank: the ordinary case ──────────────────────────────────────

    @Test
    fun `each type accepts its own tag`() {
        assertTrue(SignalTypeCompat.accepts(SignalTable.TYPE_BOOL, SignalFrame.TAG_BOOL))
        assertTrue(SignalTypeCompat.accepts(SignalTable.TYPE_INT, SignalFrame.TAG_INT))
        assertTrue(SignalTypeCompat.accepts(SignalTable.TYPE_FLOAT, SignalFrame.TAG_FLOAT))
        assertTrue(SignalTypeCompat.accepts(SignalTable.TYPE_STRING, SignalFrame.TAG_STRING))
    }

    // ── Narrowing: always loses, therefore always refused ─────────────────

    @Test
    fun `a float does not fit an int slot`() {
        assertFalse(SignalTypeCompat.accepts(SignalTable.TYPE_INT, SignalFrame.TAG_FLOAT))
    }

    @Test
    fun `a float does not fit a bool slot`() {
        assertFalse(SignalTypeCompat.accepts(SignalTable.TYPE_BOOL, SignalFrame.TAG_FLOAT))
    }

    @Test
    fun `an int does not fit a bool slot`() {
        // Even 0 and 1 are refused: the rule reads the rank, not the value, so
        // that the check stays one comparison on the hottest path there is.
        assertFalse(SignalTypeCompat.accepts(SignalTable.TYPE_BOOL, SignalFrame.TAG_INT))
    }

    // ── Text: an island ───────────────────────────────────────────────────

    @Test
    fun `no number enters a text slot`() {
        assertFalse(SignalTypeCompat.accepts(SignalTable.TYPE_STRING, SignalFrame.TAG_BOOL))
        assertFalse(SignalTypeCompat.accepts(SignalTable.TYPE_STRING, SignalFrame.TAG_INT))
        assertFalse(SignalTypeCompat.accepts(SignalTable.TYPE_STRING, SignalFrame.TAG_FLOAT))
    }

    @Test
    fun `text enters no numeric slot`() {
        assertFalse(SignalTypeCompat.accepts(SignalTable.TYPE_BOOL, SignalFrame.TAG_STRING))
        assertFalse(SignalTypeCompat.accepts(SignalTable.TYPE_INT, SignalFrame.TAG_STRING))
        assertFalse(SignalTypeCompat.accepts(SignalTable.TYPE_FLOAT, SignalFrame.TAG_STRING))
    }

    // ── The unknown is refused, never guessed ─────────────────────────────

    @Test
    fun `an unknown tag is refused`() {
        assertFalse(SignalTypeCompat.accepts(SignalTable.TYPE_FLOAT, 0x7F))
    }

    @Test
    fun `an unknown declared type is refused`() {
        assertFalse(SignalTypeCompat.accepts("duration", SignalFrame.TAG_FLOAT))
    }

    // ── enum: le type a disparu, la garde le refuse ───────────────────────

    @Test
    fun `an enum slot is no longer a type this server knows`() {
        assertNull(SignalTypeCompat.rankOfType("enum"),
            "enum n'existe plus : le refuser vaut mieux que le deviner")
    }

    // ── The diagnostic the user reads ─────────────────────────────────────

    @Test
    fun `a tag names itself for the message`() {
        assertEquals(SignalTable.TYPE_FLOAT, SignalTypeCompat.nameOfTag(SignalFrame.TAG_FLOAT))
        assertEquals(SignalTable.TYPE_BOOL, SignalTypeCompat.nameOfTag(SignalFrame.TAG_BOOL))
    }

    @Test
    fun `an unknown tag still names itself readably`() {
        assertEquals("0x7F", SignalTypeCompat.nameOfTag(0x7F))
    }
}
