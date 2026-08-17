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

import com.jeanloickdt.relay.InMemoryLastValueCache
import com.jeanloickdt.relay.WidgetKey
import kotlin.test.Test
import kotlin.test.assertEquals

private const val MIN = 60_000L

/**
 * The sweeper's one real behaviour is the EPISODE: a sensor silent for six
 * hours crosses hundreds of sweeps and must produce exactly one alert — then
 * one more if it speaks and goes silent again. Everything else is a cache
 * read.
 */
class WidgetStaleSweeperTest {

    private val T0 = 10_000_000L
    private val cache = InMemoryLastValueCache()
    private val sinks = EventSinks()
    private val sweeper = WidgetStaleSweeper(cache, sinks, silenceMs = 15 * MIN)

    private val w1 = WidgetKey("u1", "w1")

    private fun drained(): List<RelayEvent.WidgetStale> = buildList {
        while (true) add((sinks.discrete.tryReceive().getOrNull() ?: break) as RelayEvent.WidgetStale)
    }

    @Test
    fun `a live sensor never alerts`() {
        cache.put("u1", "w1", "AA==", T0)
        assertEquals(0, sweeper.sweep(setOf(w1), T0 + 5 * MIN))
    }

    @Test
    fun `one silence, hundreds of sweeps, exactly one alert`() {
        cache.put("u1", "w1", "AA==", T0)

        var published = 0
        // Six hours of minute sweeps over a sensor that went quiet at T0.
        for (m in 16..360) published += sweeper.sweep(setOf(w1), T0 + m * MIN)

        assertEquals(1, published, "the owner must not get the same alert 344 times a night")
        val event = drained().single()
        assertEquals(T0, event.lastSeenAt)
    }

    @Test
    fun `speaking again closes the episode, the next silence fires anew`() {
        cache.put("u1", "w1", "AA==", T0)
        sweeper.sweep(setOf(w1), T0 + 20 * MIN)          // first silence → alert

        cache.put("u1", "w1", "AA==", T0 + 30 * MIN)     // the sensor speaks
        assertEquals(0, sweeper.sweep(setOf(w1), T0 + 31 * MIN), "alive again — no alert")

        // …and goes quiet a second time.
        assertEquals(1, sweeper.sweep(setOf(w1), T0 + 50 * MIN), "a NEW silence is a new alert")
        assertEquals(2, drained().size)
    }

    @Test
    fun `an unwatched widget is invisible, however silent`() {
        cache.put("u1", "w1", "AA==", T0)
        assertEquals(0, sweeper.sweep(emptySet(), T0 + 300 * MIN))
    }

    @Test
    fun `a widget with no value yet is not stale — it is unborn`() {
        // Nothing in the cache: the sensor never spoke. "Went quiet" needs a
        // moment it was last heard; absence of history is a different fact.
        assertEquals(0, sweeper.sweep(setOf(w1), T0 + 300 * MIN))
    }

    @Test
    fun `unwatching a widget forgets its episode`() {
        cache.put("u1", "w1", "AA==", T0)
        sweeper.sweep(setOf(w1), T0 + 20 * MIN)          // alerted
        sweeper.sweep(emptySet(), T0 + 21 * MIN)         // rule deleted

        // Rule re-created during the SAME silence: it fires again — the new
        // rule's owner never saw the first alert.
        assertEquals(1, sweeper.sweep(setOf(w1), T0 + 25 * MIN))
    }

    @Test
    fun `two tenants, same widget id, separate silences`() {
        val w1b = WidgetKey("u2", "w1")
        cache.put("u1", "w1", "AA==", T0)
        cache.put("u2", "w1", "AA==", T0 + 14 * MIN)     // still fresh at first sweep

        assertEquals(1, sweeper.sweep(setOf(w1, w1b), T0 + 16 * MIN))
        val alone = drained().single()
        assertEquals("u1", alone.ownerId)
    }
}
