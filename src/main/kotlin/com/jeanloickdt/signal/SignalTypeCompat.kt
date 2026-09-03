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

// signal/SignalTypeCompat.kt
package com.jeanloickdt.signal

import com.jeanloickdt.signal.data.SignalTable

/**
 * Does the value in this frame fit the slot it is addressed to?
 *
 * A signal is an addressable slot on one board holding a value of a declared
 * type. The type says how to read the bytes — nothing else. So there is one
 * question to ask of an arriving frame, and only one:
 *
 *     **does its value fit the slot without loss?**
 *
 * The answer is a rank. `bool` fits in an `int`, an `int` fits in a `float`,
 * and none of the three has anything to do with text:
 *
 *     bool (1)  →  int (2)  →  float (3)        string: an island
 *
 * Widening is always lossless, so it is always accepted. Narrowing always
 * loses, so it is always refused — a float truncated to an int is a wrong
 * number that looks legitimate, which is worse than no number at all.
 *
 * ## Why this is a rank and not a table of special cases
 *
 * The first version of this rule was going to be "strict equality, except
 * int→float". That exception was not an exception: `instant.write(I0, 0)`
 * picks the integer overload in the library, and `0` fits a float slot
 * perfectly. Written as a rank, the case disappears instead of being carved
 * out — and the check stays one integer comparison, which matters because
 * this sits on the hottest path of the relay.
 *
 * ## `enum` is not a type
 *
 * The wire carries four tags. There is no `TAG_ENUM`: a board cannot send
 * "an enum", it sends an integer. `enum` is therefore an integer with labels —
 * a presentation choice, at the same level as `unit` or `decimals`. It is
 * ranked as an integer here so that any row declared before this was
 * understood keeps ingesting, but it can no longer be declared
 * (cf. `KNOWN_TYPES` in [SignalRoutes]).
 *
 * ## What this rule does NOT do
 *
 * It never looks at `minValue` / `maxValue`. Those are display hints — a gauge
 * scale — and turning them into validators would make acceptance depend on two
 * criteria of different natures, then force a decision nobody wants to make
 * (an integer 150 on a 0..100 slot: refuse, or clamp?). Representation is
 * checked here; range is the app's business.
 */
object SignalTypeCompat {

    /** Text does not convert to or from anything. Its own island, alone. */
    private const val RANK_STRING = -1

    private const val RANK_BOOL  = 1
    private const val RANK_INT   = 2
    private const val RANK_FLOAT = 3

    /** `null` for a type this server does not know — refused rather than guessed. */
    fun rankOfType(type: String): Int? = when (type) {
        SignalTable.TYPE_BOOL   -> RANK_BOOL
        SignalTable.TYPE_INT    -> RANK_INT
        SignalTable.TYPE_FLOAT  -> RANK_FLOAT
        SignalTable.TYPE_STRING -> RANK_STRING
        else -> null
    }

    /** `null` for a tag this server does not know — same reflex. */
    fun rankOfTag(tag: Int): Int? = when (tag) {
        SignalFrame.TAG_BOOL   -> RANK_BOOL
        SignalFrame.TAG_INT    -> RANK_INT
        SignalFrame.TAG_FLOAT  -> RANK_FLOAT
        SignalFrame.TAG_STRING -> RANK_STRING
        else -> null
    }

    /**
     * True when a frame carrying [frameTag] may be written into a slot
     * declared [declaredType].
     *
     * One integer comparison once both ranks are in hand.
     */
    fun accepts(declaredType: String, frameTag: Int): Boolean {
        val slot  = rankOfType(declaredType) ?: return false
        val frame = rankOfTag(frameTag) ?: return false

        // The island: text goes to text, and nothing else crosses either way.
        if (slot == RANK_STRING || frame == RANK_STRING) return slot == frame

        // Widening only.
        return frame <= slot
    }

    /** The human name of a tag, for the diagnostic the user will read. */
    fun nameOfTag(tag: Int): String = when (tag) {
        SignalFrame.TAG_BOOL   -> SignalTable.TYPE_BOOL
        SignalFrame.TAG_INT    -> SignalTable.TYPE_INT
        SignalFrame.TAG_FLOAT  -> SignalTable.TYPE_FLOAT
        SignalFrame.TAG_STRING -> SignalTable.TYPE_STRING
        else -> "0x%02X".format(tag)
    }
}
