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

package com.jeanloickdt.event

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sinks are the seam between "fast and lossy where losing is free" and
 * "rare and precious where losing is an unsent alert". Every test here checks
 * that an event lands on the side its nature demands — and that the losses,
 * when they happen, happen to the right side and are counted.
 */
class EventSinksTest {

    private val T0 = 1_000_000L

    private fun value(n: Int = 0) =
        RelayEvent.SignalValue("u1", "w1", null, n.toDouble(), T0 + n)

    private fun offline(id: String = "d1") =
        RelayEvent.DeviceOffline("u1", id, "disconnected", T0)

    // ── Le routage ────────────────────────────────────────────────────────

    @Test
    fun `values go to the lossy channel, the rest to the precious one`() {
        val sinks = EventSinks()

        sinks.publish(value())
        sinks.publish(RelayEvent.SignalText("u1", "w1", "AA==", T0))
        sinks.publish(offline())
        sinks.publish(RelayEvent.SignalStale("u1", "w1", T0 - 60_000, T0))
        sinks.publish(RelayEvent.QuotaReached("u1", "devices.max", T0))

        var values = 0
        while (sinks.values.tryReceive().isSuccess) values++
        var discrete = 0
        while (sinks.discrete.tryReceive().isSuccess) discrete++

        assertEquals(2, values)
        assertEquals(3, discrete)
    }

    // ── La politique de perte, côté valeurs ───────────────────────────────

    @Test
    fun `a full values channel keeps the FRESHEST samples`() {
        val sinks = EventSinks(valuesCapacity = 4)

        repeat(10) { sinks.publish(value(it)) }

        val kept = buildList {
            while (true) {
                val r = sinks.values.tryReceive().getOrNull() ?: break
                add((r as RelayEvent.SignalValue).value.toInt())
            }
        }
        // 0..5 evicted, 6..9 kept — the newest survive, by design: the next
        // sample supersedes a stale one, so freshness IS the value's worth.
        assertEquals(listOf(6, 7, 8, 9), kept)
        assertEquals(6, sinks.droppedValueCount.toInt())
    }

    @Test
    fun `publishing a value never fails and never suspends`() {
        val sinks = EventSinks(valuesCapacity = 1)
        // 100 000 publishes against a capacity of 1, no consumer, no thread
        // to unblock us — this returning at all proves it never waits.
        repeat(100_000) { sinks.publish(value(it)) }
        assertEquals(99_999, sinks.droppedValueCount.toInt())
    }

    // ── La politique de perte, côté discret ───────────────────────────────

    @Test
    fun `discrete events are never displaced by later ones`() {
        val sinks = EventSinks(discreteCapacity = 2)

        sinks.publish(offline("d1"))
        sinks.publish(offline("d2"))
        sinks.publish(offline("d3"))   // full — dropped, not displacing d1

        val kept = buildList {
            while (true) {
                val r = sinks.discrete.tryReceive().getOrNull() ?: break
                add((r as RelayEvent.DeviceOffline).deviceId)
            }
        }
        // The OLDEST discrete events survive: they were accepted first, and an
        // accepted alert must not be silently evicted by a newer one.
        assertEquals(listOf("d1", "d2"), kept)
        assertEquals(1, sinks.droppedDiscreteCount.toInt())
    }

    @Test
    fun `a healthy discrete channel loses nothing`() {
        val sinks = EventSinks()
        repeat(100) { sinks.publish(offline("d$it")) }
        assertEquals(0, sinks.droppedDiscreteCount.toInt())
    }

    @Test
    fun `discrete losses are counted even after the first logged one`() {
        val sinks = EventSinks(discreteCapacity = 1)
        sinks.publish(offline())
        repeat(5) { sinks.publish(offline()) }
        assertEquals(5, sinks.droppedDiscreteCount.toInt())
    }

    // ── Le contrat du vocabulaire ─────────────────────────────────────────

    @Test
    fun `depth defaults to zero — caused by the outside world`() {
        assertEquals(0, value().depth)
        assertEquals(0, offline().depth)
    }

    @Test
    fun `a consumer drains in order`() = runBlocking {
        val sinks = EventSinks()
        sinks.publish(offline("d1"))
        sinks.publish(offline("d2"))

        assertEquals("d1", (sinks.discrete.receive() as RelayEvent.DeviceOffline).deviceId)
        assertEquals("d2", (sinks.discrete.receive() as RelayEvent.DeviceOffline).deviceId)
        assertNull(sinks.discrete.tryReceive().getOrNull())
    }

    @Test
    fun `every event family routes without throwing`() {
        val sinks = EventSinks()
        listOf(
            value(),
            RelayEvent.SignalText("u1", "w1", "AA==", T0),
            RelayEvent.DeviceOnline("u1", "d1", T0),
            offline(),
            RelayEvent.SignalStale("u1", "w1", T0 - 1, T0),
            RelayEvent.TimeReached("u1", "r1", T0, T0),
            RelayEvent.QuotaReached("u1", "devices.max", T0),
            RelayEvent.DeviceRejected("u1", "d1", "rate_limited", T0),
            RelayEvent.PlanChanged("u1", "pro", "grace", T0)
        ).forEach { sinks.publish(it) }

        var total = 0
        while (sinks.values.tryReceive().isSuccess) total++
        while (sinks.discrete.tryReceive().isSuccess) total++
        assertTrue(total == 9, "all nine families must land somewhere — got $total")
    }
}
