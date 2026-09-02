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
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * This ledger will one day decide what a customer is billed against — so the
 * property that matters is: **no increment is ever lost, whatever the
 * interleaving of hot path and flush.**
 */
class MessageUsageTest {

    private val counter = MessageUsageCounter()
    private val repo = ExposedMessageUsageRepository()

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.fresh()
    }

    // ── Le compteur RAM ───────────────────────────────────────────────────

    @Test
    fun `drain hands over everything and resets`() {
        repeat(5) { counter.increment("u1") }
        repeat(3) { counter.increment("u2") }

        assertEquals(mapOf("u1" to 5L, "u2" to 3L), counter.drain())
        assertEquals(emptyMap(), counter.drain(), "a second drain must find nothing")
    }

    @Test
    fun `no increment is lost across concurrent bumps and drains`() {
        // 8 threads × 10 000 increments racing a draining thread. Whatever the
        // interleaving, drained + pending must equal exactly what was counted.
        val threads = 8
        val perThread = 10_000
        val start = CountDownLatch(1)
        var drainedTotal = 0L

        val drainer = thread {
            start.await()
            repeat(200) { drainedTotal += counter.drain()["u1"] ?: 0L }
        }
        val workers = (1..threads).map {
            thread { start.await(); repeat(perThread) { counter.increment("u1") } }
        }
        start.countDown()
        workers.forEach { it.join() }
        drainer.join()
        drainedTotal += counter.drain()["u1"] ?: 0L

        assertEquals(threads.toLong() * perThread, drainedTotal, "an increment was lost in the race")
    }

    // ── La persistance ────────────────────────────────────────────────────

    @Test
    fun `deltas accumulate across flush cycles`() {
        repo.add("u1", "2026-08", 100)
        repo.add("u1", "2026-08", 250)

        assertEquals(350, repo.usage("u1", "2026-08"))
    }

    @Test
    fun `a new month is a new row — the old one stops growing, nothing resets`() {
        repo.add("u1", "2026-08", 500)
        repo.add("u1", "2026-09", 10)

        assertEquals(500, repo.usage("u1", "2026-08"), "last month doubles as billing history")
        assertEquals(10, repo.usage("u1", "2026-09"))
    }

    @Test
    fun `owners never mix`() {
        repo.add("u1", "2026-08", 100)
        repo.add("u2", "2026-08", 7)

        assertEquals(100, repo.usage("u1", "2026-08"))
        assertEquals(7, repo.usage("u2", "2026-08"))
        assertEquals(0, repo.usage("ghost", "2026-08"))
    }

    @Test
    fun `the period key is UTC, not server locale`() {
        // 2026-08-31 23:30 UTC — a server in UTC+2 would already be in
        // September and would bill this frame on the wrong month.
        assertEquals("2026-08", MessageUsageRepository.periodOf(1_788_219_000_000L))
        // Epoch itself, as a fixed point.
        assertEquals("1970-01", MessageUsageRepository.periodOf(0L))
    }

    @Test
    fun `counter and repo compose — the flush cycle end to end`() {
        repeat(42) { counter.increment("u1") }
        val period = MessageUsageRepository.periodOf(System.currentTimeMillis())

        counter.drain().forEach { (owner, delta) -> repo.add(owner, period, delta) }
        repeat(8) { counter.increment("u1") }
        counter.drain().forEach { (owner, delta) -> repo.add(owner, period, delta) }

        assertEquals(50, repo.usage("u1", period))
        assertTrue(counter.pending("u1") == 0L)
    }
}
