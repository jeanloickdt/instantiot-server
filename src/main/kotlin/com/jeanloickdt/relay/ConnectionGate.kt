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

// relay/ConnectionGate.kt
package com.jeanloickdt.relay

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * How many boards may be connected at once.
 *
 * The relay was built without a cap on purpose, and the reasoning was sound for
 * *threads*: suspending reads mean thousands of idle connections cost a handful
 * of threads rather than one each. That is a real quality and this class does
 * not take it away.
 *
 * It was never sound for *memory*. Each session carries a read buffer, a
 * [DeviceOutbox], and registry entries — a few tens of kilobytes that no
 * suspension gives back. Without a ceiling the failure mode at saturation is an
 * `OutOfMemoryError`: the process dies, every board drops, and nothing in the
 * logs says which one was the last straw.
 *
 * With a ceiling it is a refused connection and a counter. The board retries,
 * the others keep running, and the number that was climbing is on record. That
 * trade — one board refused instead of all of them dropped — is the whole
 * point.
 *
 * ## Choosing the number
 *
 * It is an operator setting, not a plan right: it protects the machine, so it
 * belongs to whoever runs the machine. The default is deliberately generous —
 * a cap that fires during normal use would be worse than none, because it would
 * teach everyone to raise it without looking.
 *
 * The honest caveat: **the right value is not known yet.** The campaign that
 * would establish it needs a second host, because a loopback connection and a
 * connection over a real network do not cost the same. Until then this is a
 * backstop against runaway, not a tuned limit.
 */
class ConnectionGate(val limit: Int) {

    private val current = AtomicInteger(0)
    private val refused = AtomicLong(0)
    private val peak = AtomicInteger(0)

    /**
     * Claims a slot, or refuses.
     *
     * @return false when the relay is full — the caller must close the socket
     *         immediately, without reading from it. Reading first would let a
     *         refused connection allocate the very buffers the cap exists to
     *         protect.
     */
    fun tryAcquire(): Boolean {
        val now = current.incrementAndGet()
        if (now > limit) {
            current.decrementAndGet()
            refused.incrementAndGet()
            return false
        }
        peak.updateAndGet { if (now > it) now else it }
        return true
    }

    /**
     * Returns a slot.
     *
     * Must run in a `finally`: a slot leaked on an exception path is a slot
     * lost until restart, and a cap that only ever shrinks is worse than no cap
     * — it fails closed, silently, hours later.
     */
    fun release() {
        current.decrementAndGet()
    }

    val active: Int get() = current.get()
    val refusedCount: Long get() = refused.get()
    val highWaterMark: Int get() = peak.get()

    /** True once the relay has been more than nine tenths full. */
    val nearingLimit: Boolean get() = peak.get() * 10 > limit * 9

    override fun toString() = "${current.get()}/$limit connections"

    companion object {
        /**
         * Generous by design.
         *
         * At roughly 40 kB per session this is about 400 MB of session state,
         * comfortable inside the container's memory cap. Reaching this number
         * means the bounded queues have something to report first.
         */
        const val DEFAULT_LIMIT = 10_000
    }
}
