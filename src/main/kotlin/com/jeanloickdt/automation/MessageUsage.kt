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

package com.jeanloickdt.automation

import com.jeanloickdt.automation.data.MessageUsageTable
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * The `messages.perMonth` flow counter — étape 0b.
 *
 * A stock (`devices.max`) is checked with a `COUNT(*)` over what exists. A
 * flow needs its own ledger: how much was consumed since the period began.
 * This is that ledger, split in two so a frame never costs a DB write:
 *
 *  - **hot path**: [MessageUsageCounter.increment] — one `LongAdder`-style
 *    RAM bump, same discipline as the history buffers
 *  - **flush**: [drain] hands the deltas to [SqliteMessageUsageRepository],
 *    which adds them to one row per (owner, month)
 *
 * The period key is `"2026-08"` in **UTC**: the monthly reset is a NEW key,
 * not an UPDATE racing a sweep — nothing to reset, old periods just stop
 * growing (and double as a billing history).
 *
 * ## What this deliberately does not do
 *
 * Enforce. Refusing frames when the month's budget is gone is a product
 * decision — silently dropping a paying customer's telemetry mid-month is the
 * notification-quota debate all over again — and it is not taken here. The
 * counter makes the decision POSSIBLE and the usage visible; the gate, when
 * decided, will be one read away.
 */
class MessageUsageCounter {

    private val counts = ConcurrentHashMap<String, AtomicLong>()

    /** Hot path — one map lookup and one atomic add. */
    fun increment(ownerId: String) {
        counts.computeIfAbsent(ownerId) { AtomicLong() }.incrementAndGet()
    }

    /**
     * Snapshot-and-reset, for the flush job. An increment landing between the
     * read and the reset survives: [AtomicLong.getAndSet] hands us exactly
     * what we drained and keeps the rest for the next cycle.
     */
    fun drain(): Map<String, Long> {
        if (counts.isEmpty()) return emptyMap()
        val out = HashMap<String, Long>()
        counts.forEach { (owner, counter) ->
            val delta = counter.getAndSet(0)
            if (delta > 0) out[owner] = delta
        }
        return out
    }

    /** RAM not yet flushed — the usage endpoint adds this to the stored total. */
    fun pending(ownerId: String): Long = counts[ownerId]?.get() ?: 0
}

interface MessageUsageRepository {
    /** Adds [delta] to the (owner, period) row, creating it at zero first. */
    fun add(ownerId: String, period: String, delta: Long)

    /** The stored total for one owner in one period. */
    fun usage(ownerId: String, period: String): Long

    companion object {
        /** `"2026-08"`, UTC — the billing month must not depend on server locale. */
        fun periodOf(epochMs: Long): String {
            val d = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC)
            return "%04d-%02d".format(d.year, d.monthValue)
        }
    }
}

class SqliteMessageUsageRepository : MessageUsageRepository {

    override fun add(ownerId: String, period: String, delta: Long) {
        if (delta <= 0) return
        transaction {
            val updated = MessageUsageTable.update({
                (MessageUsageTable.ownerId eq ownerId) and (MessageUsageTable.period eq period)
            }) {
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it[count] = count + delta
                }
            }
            if (updated == 0) {
                MessageUsageTable.insert {
                    it[MessageUsageTable.ownerId] = ownerId
                    it[MessageUsageTable.period]  = period
                    it[count]                     = delta
                }
            }
        }
    }

    override fun usage(ownerId: String, period: String): Long = transaction {
        MessageUsageTable.selectAll()
            .where { (MessageUsageTable.ownerId eq ownerId) and (MessageUsageTable.period eq period) }
            .singleOrNull()?.get(MessageUsageTable.count) ?: 0L
    }
}
