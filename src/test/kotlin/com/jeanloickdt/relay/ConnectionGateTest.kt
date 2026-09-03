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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ceiling on simultaneous boards.
 *
 * The property that matters is not "it counts" — it is that a slot is never
 * lost. A cap that only ever shrinks fails closed hours after the bug, with
 * nothing in the logs pointing at it.
 */
class ConnectionGateTest {

    @Test
    fun `connections are admitted up to the limit`() {
        val gate = ConnectionGate(limit = 3)
        repeat(3) { assertTrue(gate.tryAcquire(), "la connexion $it doit passer") }
        assertEquals(3, gate.active)
        assertEquals(0L, gate.refusedCount)
    }

    @Test
    fun `the connection past the limit is refused and counted`() {
        val gate = ConnectionGate(limit = 2)
        gate.tryAcquire(); gate.tryAcquire()

        assertFalse(gate.tryAcquire())
        assertEquals(2, gate.active, "un refus ne doit pas gonfler le compteur d'actives")
        assertEquals(1L, gate.refusedCount)
    }

    @Test
    fun `releasing frees the slot for the next board`() {
        val gate = ConnectionGate(limit = 1)
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())

        gate.release()

        assertTrue(gate.tryAcquire(), "la place libérée doit se reprendre")
    }

    @Test
    fun `a refusal does not consume a slot`() {
        // Le piège de l'incrément-puis-test : si le refus oubliait de
        // décrémenter, le relais se fermerait tout seul, une place à la fois,
        // et le journal ne dirait rien.
        val gate = ConnectionGate(limit = 2)
        gate.tryAcquire(); gate.tryAcquire()
        repeat(50) { gate.tryAcquire() }

        gate.release(); gate.release()

        assertEquals(0, gate.active)
        assertTrue(gate.tryAcquire(), "après libération, la porte doit rouvrir")
    }

    @Test
    fun `the peak is remembered after the boards leave`() {
        val gate = ConnectionGate(limit = 100)
        repeat(95) { gate.tryAcquire() }
        repeat(95) { gate.release() }

        assertEquals(95, gate.highWaterMark)
        assertEquals(0, gate.active)
        assertTrue(gate.nearingLimit, "95 % du plafond doit se voir venir")
    }

    @Test
    fun `a quiet relay never reports nearing its limit`() {
        val gate = ConnectionGate(limit = 100)
        repeat(10) { gate.tryAcquire() }
        assertFalse(gate.nearingLimit)
    }

    @Test
    fun `the limit holds when boards arrive together`() {
        // La tempête de reconnexion : N cartes qui reviennent dans la même
        // seconde après une coupure. C'est le scénario réel, pas le nominal.
        val limit = 200
        val gate = ConnectionGate(limit)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val admitted = AtomicInteger(0)

        repeat(threads) {
            pool.submit {
                start.await()
                repeat(500) { if (gate.tryAcquire()) admitted.incrementAndGet() }
                done.countDown()
            }
        }
        start.countDown()
        done.await(30, TimeUnit.SECONDS)
        pool.shutdown()

        assertEquals(limit, admitted.get(), "jamais plus que le plafond, même en rafale")
        assertEquals(limit, gate.active)
        assertEquals(4_000L - limit, gate.refusedCount, "tout le reste est compté comme refusé")
    }
}
