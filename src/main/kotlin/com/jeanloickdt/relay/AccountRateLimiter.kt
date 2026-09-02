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

// relay/AccountRateLimiter.kt
package com.jeanloickdt.relay

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The ceiling one ACCOUNT may put on the relay, whatever its boards do.
 *
 * The per-board fuse bounds a runaway sketch. It does nothing about arithmetic:
 * a Maker Pro is entitled to a hundred boards, and a hundred boards at the
 * legitimate ten frames per second is **a thousand frames per second from a
 * single account** — above everything the bench has ever measured on a node.
 *
 * Neither board is misbehaving. Each is within its right. The sum is the
 * problem, and only a per-account ceiling can see the sum.
 *
 * ## Smoothing, not cutting
 *
 * Excess frames are refused and counted; the connection lives, every board
 * stays online, and the account keeps working at its ceiling. Blynk's
 * equivalent trips a "Flood Error" that drops the board — we do not, because
 * disconnecting turns a rate problem into an availability problem, and the user
 * who is briefly over their smoothing is usually the one who just deployed
 * something that works.
 *
 * ## Why a sliding second and not a token bucket
 *
 * A bucket would let an account bank an hour of silence and spend it in one
 * burst — which is precisely the spike a single-node relay cannot absorb. The
 * point here is not fairness over time, it is *never exceeding what the machine
 * holds right now*. A one-second window says exactly that.
 */
class AccountRateLimiter(
    private val limitFor: (ownerId: String) -> Int,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Window(@Volatile var startedAtMs: Long, val count: AtomicLong)

    private val windows = ConcurrentHashMap<String, Window>()
    private val refused = ConcurrentHashMap<String, AtomicLong>()

    /**
     * @return true when the frame may proceed.
     *
     * A limit of zero or less means "unmetered" — a right sold without a
     * ceiling, or a dry run where nothing is enforced yet.
     */
    fun tryAcquire(ownerId: String): Boolean {
        val limit = limitFor(ownerId)
        if (limit <= 0) return true

        val now = clock()
        val window = windows.computeIfAbsent(ownerId) { Window(now, AtomicLong(0)) }

        // Roll the window. Racing threads may roll it twice within the same
        // millisecond; the cost is a fractionally more generous second, which
        // is the right way to be wrong on the hot path.
        if (now - window.startedAtMs >= WINDOW_MS) {
            window.startedAtMs = now
            window.count.set(0)
        }

        if (window.count.incrementAndGet() > limit) {
            refused.computeIfAbsent(ownerId) { AtomicLong(0) }.incrementAndGet()
            return false
        }
        return true
    }

    /** How many frames this account has had refused since boot. */
    fun refusedCount(ownerId: String): Long = refused[ownerId]?.get() ?: 0L

    /** Every account currently over its ceiling — what the admin panel lists. */
    fun snapshot(): Map<String, Long> = refused.mapValues { it.value.get() }

    /**
     * Forgets an account.
     *
     * Called when the last board of an account disconnects: without it, the map
     * would grow with every account ever seen and become the very unbounded
     * structure the bounded queues exist to avoid.
     */
    fun forget(ownerId: String) {
        windows.remove(ownerId)
        // The refusal count deliberately survives: it is a diagnostic the user
        // may still be reading in the app after their board went quiet.
    }

    fun reset() {
        windows.clear()
        refused.clear()
    }

    companion object {
        const val WINDOW_MS = 1_000L
    }
}
