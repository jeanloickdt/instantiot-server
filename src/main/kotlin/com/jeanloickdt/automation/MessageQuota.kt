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

// automation/MessageQuota.kt
package com.jeanloickdt.automation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The month's running total, and the refusal that was missing.
 *
 * [MessageUsageCounter] already counted correctly — it bumps RAM per frame and
 * the flush loop drains it into the ledger. What it could not do is *refuse*:
 * it resets to zero on every drain, so it never knows the month's total, and
 * the stored total lives in a table nobody may read per frame.
 *
 * This holds that total in RAM. One database read per account per boot seeds
 * it; every frame afterwards is an atomic increment and a comparison.
 *
 * ## Why refusing here and smoothing elsewhere
 *
 * The per-second smoothing and this quota answer different questions.
 * Smoothing asks *can the machine take this right now* — exceed it and you wait
 * a second. This asks *has this account used what it bought* — exceed it and
 * you have used your month. One is physics, the other is commerce, and merging
 * them would make a busy afternoon look like an expired subscription.
 */
class MessageQuota(
    private val repository: MessageUsageRepository,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Usage(val period: String, val total: AtomicLong)

    private val usage = ConcurrentHashMap<String, Usage>()
    private val refused = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Counts this frame and says whether it may proceed.
     *
     * @param limit the account's `messages.perMonth`. Zero or [UNLIMITED]
     *              means unmetered — an uncapped right, or the dry run.
     * @return true when the frame is within budget.
     */
    fun countAndAllow(ownerId: String, limit: Int): Boolean {
        if (limit <= 0) return true

        val period = MessageUsageRepository.periodOf(clock())
        var entry = usage[ownerId]

        // A new month rolls the total rather than waiting for a restart —
        // otherwise a long-lived process would carry August into September and
        // lock out an account that had done nothing wrong.
        if (entry == null || entry.period != period) {
            val seeded = Usage(period, AtomicLong(repository.usage(ownerId, period)))
            usage[ownerId] = seeded
            entry = seeded
        }

        if (entry.total.incrementAndGet() > limit) {
            refused.computeIfAbsent(ownerId) { AtomicLong(0) }.incrementAndGet()
            return false
        }
        return true
    }

    /** How many frames this account has had refused for being over budget. */
    fun refusedCount(ownerId: String): Long = refused[ownerId]?.get() ?: 0L

    /** The month's running total as this process sees it. */
    fun totalOf(ownerId: String): Long = usage[ownerId]?.total?.get() ?: 0L

    fun reset() {
        usage.clear()
        refused.clear()
    }

    companion object {
        /** What `-1` means in `plans.json`. */
        const val UNLIMITED = -1
    }
}
