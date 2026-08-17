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

/**
 * Per-connection token bucket — the fuse against the most common Arduino
 * mistake in existence:
 *
 * ```cpp
 * void loop() {
 *   instantiot.virtualWrite(V1, temp);   // no delay()
 * }
 * ```
 *
 * That sketch emits thousands of frames per second. Each one costs the relay a
 * RAM put, two buffer adds and three aggregations today — and a rules
 * evaluation tomorrow. The fuse bounds what one board can cost, full stop.
 *
 * ## Per DEVICE, not per account
 *
 * The fuse protects the machine from *one* faulty board, so it targets the
 * board. A per-account bucket would let a buggy test sketch on the bench take
 * down the greenhouse next to it — same owner, innocent board.
 *
 * (Billing per account is a different mechanism entirely: the
 * `messages.perMonth` entitlement, a cumulative counter, not this.)
 *
 * ## The ceiling lives in code
 *
 * Not in `caps`: with no plan file — self-hosted, or cloud before the file is
 * deployed — `caps` is empty, and the node would be unprotected precisely when
 * nobody is watching. The file may *tighten* the fuse, never create it.
 *
 * ## Never a delay
 *
 * A frame over budget is dropped with a counter bump — `tryAcquire` returns
 * false and the read loop moves on. Suspending here would hand the flooding
 * board a way to hold its coroutine, which is the exact disease the outboxes
 * were built to cure.
 *
 * Not thread-safe, deliberately: one instance per connection, owned by the
 * single coroutine that reads that socket. No shared state, freed with the
 * connection.
 */
class FrameRateLimiter(
    ratePerSecond: Int = DEFAULT_RATE_PER_SECOND,
    /** Refusals must last this long, uninterrupted, before we give up on the board. */
    private val disconnectAfterMs: Long = SUSTAINED_ABUSE_MS
) {
    private val rate = ratePerSecond.coerceAtLeast(1)

    /**
     * Burst = 2× the rate: a healthy board that wakes up and flushes a backlog
     * (reconnection, sensor burst) must never be mistaken for a flooding one.
     * The fuse is for *sustained* abuse; one second of slack is not abuse.
     */
    private val burst = rate * 2

    private var tokens: Double = burst.toDouble()
    private var lastRefillMs: Long = Long.MIN_VALUE

    /**
     * Start of the current abuse streak; null when healthy.
     *
     * The streak is measured by the continuous PRESENCE OF DROPS, not by the
     * absence of accepted frames — the distinction matters: a board flooding at
     * 300/s still gets 50/s accepted by the refill, so "an accepted frame ends
     * the streak" would mean the disconnect never fires against the exact
     * board it exists for. Instead the streak ends only after a full clean
     * second without a single drop: the board genuinely slowed down.
     */
    private var throttledSinceMs: Long? = null
    private var lastDropMs: Long = Long.MIN_VALUE

    /** Drops inside the current streak — the severity measure. */
    private var streakDrops: Long = 0

    var dropped: Long = 0
        private set

    /** True → process the frame. False → drop it and read the next one. */
    fun tryAcquire(nowMs: Long): Boolean {
        if (lastRefillMs == Long.MIN_VALUE) lastRefillMs = nowMs
        val elapsed = (nowMs - lastRefillMs).coerceAtLeast(0)
        if (elapsed > 0) {
            tokens = (tokens + elapsed * rate / 1000.0).coerceAtMost(burst.toDouble())
            lastRefillMs = nowMs
        }

        if (tokens >= 1.0) {
            tokens -= 1.0
            if (throttledSinceMs != null && nowMs - lastDropMs >= CLEAN_WINDOW_MS) {
                throttledSinceMs = null
                streakDrops = 0
            }
            return true
        }

        dropped++
        streakDrops++
        lastDropMs = nowMs
        if (throttledSinceMs == null) throttledSinceMs = nowMs
        return false
    }

    /**
     * True once the board has flooded continuously for [disconnectAfterMs]
     * **at severe volume** — an average of at least [SEVERITY_FACTOR]× the
     * ceiling. Two conditions, because disconnection has a price:
     *
     * The fuse already bounds what frames cost. What a disconnect saves on top
     * is the read-and-parse of the socket — real, but small. What it spends is
     * a full TLS handshake on return, and the ESP library reconnects on its
     * own: evicting a mildly noisy board would turn near-free dropped frames
     * into a handshake generator every 30 seconds — the most expensive CPU
     * item the server has.
     *
     * So a board at 60/s against a 50/s ceiling is simply limited, forever,
     * and never disconnected. A board at 3 000/s is evicted: at that volume
     * even reading the socket is money, and the sketch is unambiguously
     * broken. The alternative — remembering offenders across reconnections —
     * needs global state keyed by device; this stays local to the connection.
     *
     * The caller closes with an explicit reason and a ControlEvent, so the
     * owner sees "your board floods" instead of a silent ceiling: a silent cap
     * creates a support ticket, an explained one fixes the sketch.
     */
    fun shouldDisconnect(nowMs: Long): Boolean {
        val since = throttledSinceMs ?: return false
        val streakMs = nowMs - since
        if (streakMs < disconnectAfterMs) return false
        // Average incoming ≈ accepted (the rate) + dropped. Severe means the
        // drops alone average (SEVERITY_FACTOR - 1)× the ceiling.
        val severeDrops = (SEVERITY_FACTOR - 1).toLong() * rate * streakMs / 1000L
        return streakDrops >= severeDrops
    }

    companion object {
        /**
         * 50 frames/s per board — 4 widgets at 10 Hz fits, `loop()` without
         * `delay()` does not. Blynk shipped 100 req/s *per user* from day one;
         * per board, 50 is the same order of generosity.
         */
        const val DEFAULT_RATE_PER_SECOND = 50

        const val SUSTAINED_ABUSE_MS = 30_000L

        /** One full second without a drop = the board genuinely slowed down. */
        const val CLEAN_WINDOW_MS = 1_000L

        /** Disconnection requires flooding at ≥ this multiple of the ceiling. */
        const val SEVERITY_FACTOR = 5

        /**
         * The heartbeat fuse. A legitimate heartbeat is one every few seconds;
         * ten per second is two orders of magnitude of slack — no healthy board
         * can trip it, so a limitation can never masquerade as a network
         * timeout. It exists because the heartbeat exemption would otherwise be
         * a hole: a board sending 10 000 heartbeats/s would be bounded by
         * nothing at all.
         */
        const val HEARTBEAT_RATE_PER_SECOND = 10
    }
}
