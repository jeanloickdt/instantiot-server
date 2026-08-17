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

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("DeliveryWorker")

/** What one delivery attempt reported. */
sealed interface SendResult {
    /** Delivered. */
    data object Ok : SendResult

    /** Definitely NOT delivered — network down, provider 5xx. Safe to retry. */
    data class Retry(val reason: String) : SendResult

    /** Will never work — bad token, malformed payload. Retrying is noise. */
    data class Fatal(val reason: String) : SendResult
}

/**
 * One delivery channel. Étape 6 provides the real ones (FCM, Brevo, the
 * DeviceOutbox bridge); until then the worker runs against an empty registry
 * and simply finds nothing to do.
 */
interface ActionSender {
    suspend fun send(action: PendingAction): SendResult
}

/**
 * Drains `pending_actions` — the consuming side of the durability frontier.
 *
 * ## The loop
 *
 * lease a batch → deliver each → settle (SENT / retry with backoff / DEAD).
 * The lease means a worker that dies mid-batch loses nothing: its rows sit
 * leased until the lease expires, then any pass picks them up again. That is
 * the whole crash story — no recovery code, just an expiry.
 *
 * ## Two delivery semantics, decided by TYPE
 *
 * The question that decides everything: what happens if the process dies
 * BETWEEN the send and the mark? We cannot know whether the send went out.
 *
 * | type | order | crash between → | why |
 * |---|---|---|---|
 * | PUSH / EMAIL | **send, then mark** | delivered twice | a duplicate annoys; a missed leak alert floods a house |
 * | COMMAND | **mark, then send** | delivered zero times | "open the valve" executed twice is a physical act |
 *
 * A COMMAND whose send then fails is NOT retried — it was marked, and
 * retrying would reopen the double-execution window the ordering exists to
 * close. It is logged loudly instead: a lost command is visible, a doubled
 * one is dangerous.
 *
 * ## Backoff
 *
 * `base × 2^attempts`, capped. After [maxAttempts] the row goes DEAD — kept,
 * counted, never retried. DEAD rows are a metric, not garbage: each one is an
 * alert somebody paid for and did not get.
 */
class DeliveryWorker(
    private val repo: PendingActionRepository,
    private val senders: Map<String, ActionSender>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val leaseMs: Long = LEASE_MS,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val backoffBaseMs: Long = BACKOFF_BASE_MS
) {

    /** One pass: lease, deliver, settle. Returns how many rows were handled. */
    suspend fun runOnce(batch: Int = BATCH): Int {
        val now = clock()
        val leased = repo.lease(now, leaseMs, batch)
        leased.forEach { action -> deliver(action) }
        return leased.size
    }

    private suspend fun deliver(action: PendingAction) {
        val sender = senders[action.type]
        if (sender == null) {
            // No channel for this type: misconfiguration, not a transient
            // failure. Retrying would spin forever; DEAD is honest and counted.
            logger.error("No sender for type=${action.type} — action ${action.id} goes DEAD")
            repo.markDead(action.id)
            return
        }

        val atMostOnce = action.type == TYPE_COMMAND
        if (atMostOnce) {
            // Mark FIRST: if we die during the send, the command is lost, not
            // doubled. The row says SENT either way — "at most once" accepts
            // zero, never two.
            repo.markSent(action.id)
        }

        val result = try {
            sender.send(action)
        } catch (e: Exception) {
            // A sender that throws is a sender that does not know — for PUSH
            // that means retry; for COMMAND the mark already settled it.
            SendResult.Retry(e.message ?: e::class.simpleName ?: "unknown")
        }

        when {
            atMostOnce -> when (result) {
                is SendResult.Ok -> Unit   // already marked
                is SendResult.Retry, is SendResult.Fatal ->
                    // Lost, deliberately — retrying would reopen the
                    // double-execution window. Loud, because a silent lost
                    // command is a support mystery.
                    logger.error(
                        "COMMAND ${action.id} (${action.idempotencyKey}) could not be delivered " +
                            "and will NOT be retried (at-most-once): $result"
                    )
            }

            result is SendResult.Ok -> repo.markSent(action.id)

            result is SendResult.Fatal -> {
                logger.error("Action ${action.id} is undeliverable — DEAD: ${result.reason}")
                repo.markDead(action.id)
            }

            result is SendResult.Retry -> {
                val attempts = action.attempts + 1
                if (attempts >= maxAttempts) {
                    logger.error(
                        "Action ${action.id} exhausted $maxAttempts attempts — DEAD " +
                            "(an alert somebody paid for and did not get): ${result.reason}"
                    )
                    repo.markDead(action.id)
                } else {
                    val delay = backoffBaseMs shl (attempts - 1).coerceAtMost(BACKOFF_MAX_SHIFT)
                    repo.reschedule(action.id, attempts, clock() + delay)
                }
            }
        }
    }

    companion object {
        const val TYPE_PUSH    = "PUSH"
        const val TYPE_EMAIL   = "EMAIL"
        const val TYPE_COMMAND = "COMMAND"

        /** A crashed worker's rows wait this long before anyone retries them. */
        const val LEASE_MS = 5 * 60_000L

        const val BATCH = 50

        /** 2 s, 4 s, 8 s … capped at ~17 min; 8 tries ≈ half an hour of trying. */
        const val MAX_ATTEMPTS = 8
        const val BACKOFF_BASE_MS = 2_000L
        const val BACKOFF_MAX_SHIFT = 9
    }
}
