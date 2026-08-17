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

import com.jeanloickdt.automation.data.PendingActionTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** One durable action. Everything after its INSERT is replayable. */
data class PendingAction(
    val id: Int,
    val idempotencyKey: String,
    val ownerId: String,
    val ruleId: String?,
    /** `PUSH` | `EMAIL` | `COMMAND` — decides the delivery semantics. */
    val type: String,
    /** Channel-owned JSON. */
    val payload: String,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: Long,
    val occurredAt: Long
) {
    companion object {
        const val PENDING = "PENDING"
        const val SENT    = "SENT"
        const val DEAD    = "DEAD"
    }
}

/**
 * The durable side of the frontier. Small on purpose: enqueue, claim, settle.
 * The retry arithmetic lives in the worker — this class only knows rows.
 */
interface PendingActionRepository {

    /**
     * The one INSERT that makes an action durable. Returns false when the
     * idempotency key already exists — the engine evaluated the same event
     * twice (restart mid-batch), and a duplicate here is the system WORKING,
     * not failing: the unique index turned two evaluations into one delivery.
     */
    fun enqueue(
        idempotencyKey: String, ownerId: String, ruleId: String?,
        type: String, payload: String, occurredAt: Long, nowMs: Long
    ): Boolean

    /**
     * Atomically claim up to [limit] due rows: PENDING, due, and either never
     * leased or lease expired. The lease is what lets a crashed worker's rows
     * be picked up again — by anyone, [leaseMs] later — without ever being
     * processed by two workers at once.
     */
    fun lease(nowMs: Long, leaseMs: Long, limit: Int): List<PendingAction>

    fun markSent(id: Int)
    fun markDead(id: Int)

    /** Back on the queue, further in the future, one attempt heavier. */
    fun reschedule(id: Int, attempts: Int, nextAttemptAt: Long)

    /**
     * Age of the oldest PENDING row — THE health metric of the whole
     * subsystem. `null` = queue empty. Once alerts are sold, a stuck queue no
     * longer means "my dashboard lags", it means "nobody was warned".
     */
    fun oldestPendingAgeMs(nowMs: Long): Long?

    /** DEAD rows — the other number worth watching. */
    fun deadCount(): Long

    /** Rows still awaiting delivery — context for the age, not a health signal
     *  by itself: ten 3-second-old rows are fine, ONE ten-minute-old row is not. */
    fun pendingCount(): Long
}

class SqlitePendingActionRepository : PendingActionRepository {

    override fun enqueue(
        idempotencyKey: String, ownerId: String, ruleId: String?,
        type: String, payload: String, occurredAt: Long, nowMs: Long
    ): Boolean = try {
        transaction {
            PendingActionTable.insert {
                it[PendingActionTable.idempotencyKey] = idempotencyKey
                it[PendingActionTable.ownerId]        = ownerId
                it[PendingActionTable.ruleId]         = ruleId
                it[PendingActionTable.type]           = type
                it[PendingActionTable.payload]        = payload
                it[status]                            = PendingAction.PENDING
                it[attempts]                          = 0
                it[nextAttemptAt]                     = nowMs
                it[PendingActionTable.occurredAt]     = occurredAt
                it[createdAt]                         = nowMs
            }
        }
        true
    } catch (e: ExposedSQLException) {
        // The unique index on idempotency_key did its job.
        if (e.message?.contains("UNIQUE", ignoreCase = true) == true) false else throw e
    }

    override fun lease(nowMs: Long, leaseMs: Long, limit: Int): List<PendingAction> = transaction {
        // SELECT then UPDATE inside one transaction: SQLite's single writer
        // makes the pair atomic, and UPDATE…LIMIT is not portable.
        val due = PendingActionTable.selectAll()
            .where {
                (PendingActionTable.status eq PendingAction.PENDING) and
                    (PendingActionTable.nextAttemptAt lessEq nowMs) and
                    (PendingActionTable.leasedUntil.isNull() or
                        (PendingActionTable.leasedUntil lessEq nowMs))
            }
            .orderBy(PendingActionTable.nextAttemptAt)
            .limit(limit)
            .map { it.toAction() }

        if (due.isNotEmpty()) {
            PendingActionTable.update({ PendingActionTable.id inList due.map { it.id } }) {
                it[leasedUntil] = nowMs + leaseMs
            }
        }
        due
    }

    override fun markSent(id: Int) {
        transaction {
            PendingActionTable.update({ PendingActionTable.id eq id }) {
                it[status] = PendingAction.SENT
                it[leasedUntil] = null
            }
        }
    }

    override fun markDead(id: Int) {
        transaction {
            PendingActionTable.update({ PendingActionTable.id eq id }) {
                it[status] = PendingAction.DEAD
                it[leasedUntil] = null
            }
        }
    }

    override fun reschedule(id: Int, attempts: Int, nextAttemptAt: Long) {
        transaction {
            PendingActionTable.update({ PendingActionTable.id eq id }) {
                it[PendingActionTable.attempts]      = attempts
                it[PendingActionTable.nextAttemptAt] = nextAttemptAt
                it[leasedUntil]                      = null
            }
        }
    }

    override fun oldestPendingAgeMs(nowMs: Long): Long? = transaction {
        PendingActionTable.selectAll()
            .where { PendingActionTable.status eq PendingAction.PENDING }
            .orderBy(PendingActionTable.createdAt)
            .limit(1)
            .singleOrNull()
            ?.let { nowMs - it[PendingActionTable.createdAt] }
    }

    override fun deadCount(): Long = transaction {
        PendingActionTable.selectAll()
            .where { PendingActionTable.status eq PendingAction.DEAD }
            .count()
    }

    override fun pendingCount(): Long = transaction {
        PendingActionTable.selectAll()
            .where { PendingActionTable.status eq PendingAction.PENDING }
            .count()
    }

    private fun ResultRow.toAction() = PendingAction(
        id             = this[PendingActionTable.id],
        idempotencyKey = this[PendingActionTable.idempotencyKey],
        ownerId        = this[PendingActionTable.ownerId],
        ruleId         = this[PendingActionTable.ruleId],
        type           = this[PendingActionTable.type],
        payload        = this[PendingActionTable.payload],
        status         = this[PendingActionTable.status],
        attempts       = this[PendingActionTable.attempts],
        nextAttemptAt  = this[PendingActionTable.nextAttemptAt],
        occurredAt     = this[PendingActionTable.occurredAt]
    )
}
