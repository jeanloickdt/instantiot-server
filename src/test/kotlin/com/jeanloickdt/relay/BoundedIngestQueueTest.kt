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

package com.jeanloickdt.relay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ceiling, and what happens at it.
 *
 * Written against the queue rather than through the relay: the whole point of
 * this class is that the policy became reachable. Before it, "the buffer is
 * unbounded" was a property nothing could assert.
 */
class BoundedIngestQueueTest {

    // ── La règle de capacité ──────────────────────────────────────────────

    @Test
    fun `capacity is the drain rate times three flush periods`() {
        // 2 000 lignes/s × 5 s × 3 périodes = 30 000
        assertEquals(30_000, BoundedIngestQueue.capacityFor(2_000, 5_000))
    }

    @Test
    fun `a faster writer earns a larger ceiling`() {
        val slow = BoundedIngestQueue.capacityFor(2_000, 5_000)
        val fast = BoundedIngestQueue.capacityFor(8_000, 5_000)
        assertTrue(fast > slow, "le plafond doit suivre ce que l'écrivain tient")
        assertEquals(slow * 4, fast)
    }

    @Test
    fun `a tiny writer still gets a usable floor`() {
        // Sans plancher, un écrivain lent produirait un plafond qui refuserait
        // une rafale ordinaire — et une file qui refuse du trafic normal est
        // pire que pas de file du tout.
        assertEquals(BoundedIngestQueue.MIN_CAPACITY, BoundedIngestQueue.capacityFor(1, 5_000))
    }

    @Test
    fun `the slack is what makes it a burst allowance and not a cliff`() {
        val oneperiod = BoundedIngestQueue.capacityFor(4_000, 5_000, periodsOfSlack = 1)
        val three = BoundedIngestQueue.capacityFor(4_000, 5_000, periodsOfSlack = 3)
        assertEquals(oneperiod * 3, three)
    }

    // ── Le comportement à saturation ──────────────────────────────────────

    @Test
    fun `entries are accepted up to the ceiling`() {
        val q = BoundedIngestQueue<Int>("test", capacity = 10)
        repeat(10) { assertTrue(q.offer(it), "l'entrée $it doit passer") }
        assertEquals(10, q.size)
        assertEquals(0L, q.refusedCount)
    }

    @Test
    fun `the entry past the ceiling is refused, not queued`() {
        val q = BoundedIngestQueue<Int>("test", capacity = 3)
        repeat(3) { q.offer(it) }

        assertFalse(q.offer(99), "la file est pleine — l'appelant doit le savoir")
        assertEquals(3, q.size, "rien ne s'ajoute au-delà du plafond")
        assertEquals(1L, q.refusedCount)
    }

    @Test
    fun `the oldest entries survive — refusing never rewrites history`() {
        // La décision de conception : on refuse la nouvelle plutôt que de jeter
        // l'ancienne. Une moyenne amputée de ses premiers échantillons reste
        // une moyenne d'apparence normale — c'est le pire des deux maux.
        val q = BoundedIngestQueue<String>("test", capacity = 2)
        q.offer("première")
        q.offer("seconde")
        q.offer("refusée")

        assertEquals(listOf("première", "seconde"), q.drain())
    }

    @Test
    fun `draining frees the ceiling again`() {
        val q = BoundedIngestQueue<Int>("test", capacity = 2)
        q.offer(1); q.offer(2)
        assertFalse(q.offer(3))

        q.drain()

        assertTrue(q.offer(3), "après vidage, la file reprend des entrées")
        assertEquals(1, q.size)
    }

    @Test
    fun `draining an empty queue is not an error`() {
        val q = BoundedIngestQueue<Int>("test", capacity = 5)
        assertTrue(q.drain().isEmpty())
        assertEquals(0, q.size)
    }

    // ── Ce qui rend la saturation observable ──────────────────────────────

    @Test
    fun `refusals accumulate so the flush loop can report them`() {
        val q = BoundedIngestQueue<Int>("test", capacity = 1)
        q.offer(1)
        repeat(5) { q.offer(it) }

        assertEquals(5L, q.refusedCount)
    }

    @Test
    fun `the peak is remembered after the queue empties`() {
        // L'indicateur avancé : une file qui s'est remplie puis vidée n'a rien
        // refusé, mais elle a frôlé — et c'est ce qu'on veut voir venir.
        val q = BoundedIngestQueue<Int>("test", capacity = 100)
        repeat(80) { q.offer(it) }
        q.drain()

        assertEquals(80, q.peak)
        assertEquals(0L, q.refusedCount, "frôler n'est pas refuser")
        assertTrue(q.everStrained, "80 % du plafond mérite d'être signalé")
    }

    @Test
    fun `a quiet queue never reports strain`() {
        val q = BoundedIngestQueue<Int>("test", capacity = 100)
        repeat(10) { q.offer(it) }
        assertFalse(q.everStrained)
    }

    // ── Le chemin chaud est concurrent ────────────────────────────────────

    @Test
    fun `the ceiling holds under concurrent writers`() {
        // Le compteur admet un léger dépassement sous contention — c'est un
        // choix documenté : borner strictement demanderait un verrou sur le
        // chemin le plus chaud du relais. On vérifie que la dérive reste
        // marginale, pas qu'elle est nulle.
        val capacity = 500
        val q = BoundedIngestQueue<Int>("test", capacity)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            pool.submit {
                start.await()
                repeat(1_000) { q.offer(it) }
                done.countDown()
            }
        }
        start.countDown()
        done.await(30, TimeUnit.SECONDS)
        pool.shutdown()

        assertTrue(
            q.size in capacity..(capacity + threads),
            "attendu ~$capacity (dérive ≤ $threads), obtenu ${q.size}"
        )
        assertEquals(8_000L, q.size + q.refusedCount, "aucune entrée ne disparaît sans être comptée")
    }
}
