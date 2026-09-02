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

// relay/BoundedIngestQueue.kt
package com.jeanloickdt.relay

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The difference between a buffer and a queue.
 *
 * A buffer grows until something else stops it. A queue has a ceiling and a
 * stated behaviour when it is reached. What the relay had was buffers: three
 * `ConcurrentLinkedQueue` filled per frame and drained every five seconds,
 * with nothing between the arrival rate and the JVM's memory.
 *
 * The `try/catch` around the flush protected the loop from an exception. It
 * protected nothing from arrivals being faster than departures.
 *
 * ## Why refusing, and not dropping the oldest
 *
 * Three ways to behave when full, and only one is honest.
 *
 * **Dropping the oldest falsifies history in silence.** A minute average
 * missing its first samples is still an average that looks perfectly normal.
 * That is precisely the failure mode this codebase spent a week removing
 * everywhere else — a wrong number that looks legitimate is worse than no
 * number.
 *
 * **Blocking the socket read spreads the fault to the board.** It accumulates
 * in turn, then disconnects. A server problem becomes a fleet incident.
 *
 * **Refusing is already the vocabulary here.** The type guard refuses and
 * counts; `UndeclaredSignals` refuses and counts; the frame fuse drops and
 * counts. The saturation counter belongs in the same place they do — the
 * device state the app reads.
 *
 * ## Why this ceiling
 *
 * [capacityFor] is the whole policy, and it is a pure function so a test can
 * reach it. Capacity is **the drain rate times three flush periods**: roughly
 * fifteen seconds of backlog.
 *
 * Beyond that the writer will not catch up — it is not a burst any more, it is
 * a rate the machine cannot hold. Accumulating further only postpones the same
 * decision with more memory committed.
 */
class BoundedIngestQueue<T>(
    val name: String,
    val capacity: Int
) {
    private val queue = ConcurrentLinkedQueue<T>()

    /** Tracked separately: `size` on a ConcurrentLinkedQueue walks the list. */
    private val count = AtomicInteger(0)

    private val refused = AtomicLong(0)
    private val highWaterMark = AtomicInteger(0)

    /**
     * Adds an entry, or refuses it because the queue is full.
     *
     * @return false when refused — the caller may then account for it. Never
     *         throws: the hot path must not pay for an exception, and a full
     *         queue is an expected state, not an error.
     */
    fun offer(entry: T): Boolean {
        // Read-then-increment races slightly over capacity under contention.
        // That is deliberate: a strict bound would need a lock on the hottest
        // path in the relay, to buy an exactness nobody can observe.
        if (count.get() >= capacity) {
            refused.incrementAndGet()
            return false
        }
        queue.add(entry)
        val now = count.incrementAndGet()
        highWaterMark.updateAndGet { if (now > it) now else it }
        return true
    }

    /**
     * Takes everything currently queued and empties it.
     *
     * Drains by polling rather than swapping the collection: an entry arriving
     * mid-drain stays in the queue and leaves on the next round, instead of
     * being lost with a discarded reference.
     */
    fun drain(): List<T> {
        val batch = ArrayList<T>(minOf(count.get(), capacity))
        while (true) {
            val item = queue.poll() ?: break
            batch.add(item)
        }
        count.addAndGet(-batch.size)
        return batch
    }

    val size: Int get() = count.get()

    /** How many entries were refused since boot. Zero is the normal reading. */
    val refusedCount: Long get() = refused.get()

    /** The fullest this queue has ever been — the leading indicator. */
    val peak: Int get() = highWaterMark.get()

    /** True once the queue has been more than three quarters full. */
    val everStrained: Boolean get() = highWaterMark.get() * 4 > capacity * 3

    override fun toString() = "$name=${count.get()}/$capacity"

    companion object {
        /**
         * The capacity policy, on its own so it can be tested and sabotaged.
         *
         * @param drainRatePerSecond how many entries the writer sustains
         * @param flushPeriodMs      the flush loop's period
         * @param periodsOfSlack     how many periods of backlog to tolerate
         */
        fun capacityFor(
            drainRatePerSecond: Int,
            flushPeriodMs: Long,
            periodsOfSlack: Int = 3
        ): Int {
            require(drainRatePerSecond > 0) { "drain rate must be positive" }
            require(flushPeriodMs > 0) { "flush period must be positive" }
            require(periodsOfSlack > 0) { "slack must be at least one period" }
            val perPeriod = (drainRatePerSecond * flushPeriodMs / 1000.0)
            // A floor: below a few thousand entries the ceiling would fire on
            // an ordinary burst, and a queue that refuses normal traffic is
            // worse than no queue at all.
            return maxOf(MIN_CAPACITY, (perPeriod * periodsOfSlack).toInt())
        }

        const val MIN_CAPACITY = 2_000
    }
}
