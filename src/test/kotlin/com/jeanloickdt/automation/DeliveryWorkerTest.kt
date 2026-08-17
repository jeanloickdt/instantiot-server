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

import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.widget.data.WidgetHistoryDayTable
import com.jeanloickdt.widget.data.WidgetHistoryHourTable
import com.jeanloickdt.widget.data.WidgetHistoryMinTable
import com.jeanloickdt.widget.data.WidgetHistoryNumericTable
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The frontier's consuming side. The two tests that matter most simulate the
 * process dying BETWEEN the send and the mark — the moment where "did it go
 * out?" has no answer — and prove the two types resolve that doubt in
 * opposite directions, each for its own reason.
 */
class DeliveryWorkerTest {

    private val repo = SqlitePendingActionRepository()
    private var now = 1_000_000L
    private val clock = { now }

    private class RecordingSender(var result: SendResult = SendResult.Ok) : ActionSender {
        val sent = mutableListOf<String>()
        override suspend fun send(action: PendingAction): SendResult {
            sent += action.idempotencyKey
            return result
        }
    }

    @BeforeTest
    fun setup() {
        val db = File.createTempFile("instantiot-delivery-", ".db").apply { deleteOnExit() }
        DatabaseFactory.init(
            UserTable, ProjectTable, DeviceTable, WidgetTable,
            WidgetHistoryTable, WidgetHistoryNumericTable,
            WidgetHistoryMinTable, WidgetHistoryHourTable, WidgetHistoryDayTable,
            *AutomationTables.ALL,
            dbFile = db
        )
    }

    private fun worker(vararg senders: Pair<String, ActionSender>) =
        DeliveryWorker(repo, senders.toMap(), clock = clock)

    private fun enqueue(key: String, type: String = "PUSH"): Boolean =
        repo.enqueue(key, "u1", "r1", type, "{}", occurredAt = now, nowMs = now)

    // ── L'idempotence à l'entrée ──────────────────────────────────────────

    @Test
    fun `the same event enqueued twice delivers once`() = runBlocking {
        assertTrue(enqueue("r1:evt1"))
        assertFalse(enqueue("r1:evt1"), "the duplicate must be refused, quietly")

        val push = RecordingSender()
        worker("PUSH" to push).runOnce()

        assertEquals(listOf("r1:evt1"), push.sent)
    }

    // ── Le chemin heureux ─────────────────────────────────────────────────

    @Test
    fun `a PUSH is sent then marked SENT`() = runBlocking {
        enqueue("k1")
        val push = RecordingSender()

        assertEquals(1, worker("PUSH" to push).runOnce())
        assertEquals(1, push.sent.size)
        assertEquals(0, worker("PUSH" to push).runOnce(), "a SENT row must never be re-leased")
    }

    // ── Reprise et backoff ────────────────────────────────────────────────

    @Test
    fun `a retryable failure backs off exponentially, then succeeds`() = runBlocking {
        enqueue("k1")
        val push = RecordingSender(SendResult.Retry("FCM 503"))
        val w = worker("PUSH" to push)

        w.runOnce()                              // attempt 1 fails → +2 s
        assertEquals(1, push.sent.size)
        w.runOnce()
        assertEquals(1, push.sent.size, "before the backoff expires, nothing is retried")

        now += 2_100                             // past the first backoff
        push.result = SendResult.Ok
        w.runOnce()
        assertEquals(2, push.sent.size, "after the backoff, the retry goes out")
        assertEquals(0, w.runOnce(), "and the row is settled")
    }

    @Test
    fun `after max attempts the row goes DEAD, kept and counted`() = runBlocking {
        enqueue("k1")
        val push = RecordingSender(SendResult.Retry("always down"))
        val w = worker("PUSH" to push)

        repeat(DeliveryWorker.MAX_ATTEMPTS) {
            w.runOnce()
            now += 20 * 60_000L                  // leap past any backoff
        }

        assertEquals(DeliveryWorker.MAX_ATTEMPTS, push.sent.size)
        assertEquals(1, repo.deadCount(), "DEAD is a metric — an alert somebody paid for and did not get")
        assertEquals(0, w.runOnce(), "DEAD is never retried")
    }

    @Test
    fun `a fatal failure goes DEAD immediately — retrying noise is still noise`() = runBlocking {
        enqueue("k1")
        val push = RecordingSender(SendResult.Fatal("token unregistered"))

        worker("PUSH" to push).runOnce()

        assertEquals(1, push.sent.size)
        assertEquals(1, repo.deadCount())
    }

    @Test
    fun `a type with no sender goes DEAD, not into a spin`() = runBlocking {
        enqueue("k1", type = "CARRIER_PIGEON")
        assertEquals(1, worker().runOnce())
        assertEquals(1, repo.deadCount())
    }

    // ── Le bail ───────────────────────────────────────────────────────────

    @Test
    fun `a leased row is invisible to a concurrent pass`() = runBlocking {
        enqueue("k1")
        // First worker leases and then "hangs": simulate by leasing directly.
        assertEquals(1, repo.lease(now, leaseMs = 300_000, limit = 10).size)

        val push = RecordingSender()
        assertEquals(0, worker("PUSH" to push).runOnce(), "two workers must never share a row")
        assertEquals(0, push.sent.size)
    }

    @Test
    fun `an expired lease is picked up by anyone — the whole crash story`() = runBlocking {
        enqueue("k1")
        repo.lease(now, leaseMs = 300_000, limit = 10)   // the worker that died

        now += 300_001                                    // lease expired
        val push = RecordingSender()
        assertEquals(1, worker("PUSH" to push).runOnce())
        assertEquals(1, push.sent.size)
    }

    // ── LE test : mourir entre l'envoi et le marquage ─────────────────────

    @Test
    fun `a PUSH interrupted between send and mark is sent again — at least once`() = runBlocking {
        enqueue("k1")
        // The crash: the row is leased, the push went out the door… and the
        // process dies before markSent. Simulated by leasing + "sending"
        // without settling.
        repo.lease(now, leaseMs = 300_000, limit = 10)
        val firstDelivery = 1   // the push DID reach the phone

        now += 300_001
        val push = RecordingSender()
        worker("PUSH" to push).runOnce()

        assertEquals(firstDelivery + 1, firstDelivery + push.sent.size,
            "the phone gets the push twice — a duplicate annoys, a missed leak alert floods a house")
    }

    @Test
    fun `a COMMAND interrupted after its mark is never sent again — at most once`() = runBlocking {
        enqueue("k1", type = "COMMAND")
        // The worker marks BEFORE sending. A sender that dies mid-send is the
        // crash: the mark is already durable.
        val dying = object : ActionSender {
            var calls = 0
            override suspend fun send(action: PendingAction): SendResult {
                calls++
                throw IllegalStateException("process died mid-send")
            }
        }
        worker("COMMAND" to dying).runOnce()
        assertEquals(1, dying.calls)

        // Restart, lease horizon long past — nothing to pick up: the mark
        // preceded the send, so the doubt resolves to ZERO deliveries.
        now += 24 * 3600_000L
        assertEquals(0, worker("COMMAND" to dying).runOnce(),
            "\"open the valve\" executed twice is a physical act — zero beats two")
        assertEquals(1, dying.calls)
    }

    @Test
    fun `a COMMAND that fails cleanly is not retried either`() = runBlocking {
        // Even a CLEAN failure does not reopen the window: the mark already
        // happened, and distinguishing "surely not delivered" from "unknown"
        // is a per-sender subtlety the at-most-once contract refuses to bet on.
        enqueue("k1", type = "COMMAND")
        val cmd = RecordingSender(SendResult.Retry("device offline"))
        val w = worker("COMMAND" to cmd)

        w.runOnce()
        now += 3600_000L
        assertEquals(0, w.runOnce())
        assertEquals(1, cmd.sent.size)
    }

    // ── L'observabilité ───────────────────────────────────────────────────

    @Test
    fun `the oldest PENDING age is the health metric`() = runBlocking {
        assertEquals(null, repo.oldestPendingAgeMs(now), "empty queue, no age")

        enqueue("k1")
        now += 45_000
        enqueue("k2")

        assertEquals(45_000L, repo.oldestPendingAgeMs(now), "the OLDEST row dates the trouble")

        worker("PUSH" to RecordingSender()).runOnce()
        assertEquals(null, repo.oldestPendingAgeMs(now), "drained queue, no age again")
    }
}
