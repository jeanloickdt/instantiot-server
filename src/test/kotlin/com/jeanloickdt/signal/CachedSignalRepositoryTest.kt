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

package com.jeanloickdt.signal

import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.signal.data.CachedSignalRepository
import com.jeanloickdt.signal.data.SignalTable
import com.jeanloickdt.signal.data.SignalTouch
import com.jeanloickdt.signal.data.ExposedSignalRepository
import com.jeanloickdt.signal.domain.SignalRepository
import com.jeanloickdt.signal.domain.SignalRow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cache, judged on the two things that break caches.
 *
 * It must actually stop hitting the database — otherwise it is decoration —
 * and it must never serve a row that has changed. The counting delegate below
 * is what makes the first half provable: without it, a "cache" that forwards
 * every call would pass every behavioural test in this file.
 */
class CachedSignalRepositoryTest {

    private val OWNER = "u1"
    private val TT = "dev-tt"

    /** Wraps the real repository and counts what actually reaches it. */
    private class Counting(private val inner: SignalRepository) : SignalRepository by inner {
        val finds = AtomicInteger()
        val touchAlls = AtomicInteger()
        val rowsWritten = AtomicInteger()

        override fun find(ownerId: String, deviceId: String, address: Int): SignalRow? {
            finds.incrementAndGet()
            return inner.find(ownerId, deviceId, address)
        }

        override fun touchAll(batch: List<SignalTouch>): Int {
            touchAlls.incrementAndGet()
            return inner.touchAll(batch).also { rowsWritten.addAndGet(it) }
        }
    }

    private lateinit var counting: Counting
    private lateinit var cache: CachedSignalRepository

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.fresh()
        counting = Counting(ExposedSignalRepository())
        cache = CachedSignalRepository(counting)
    }

    private fun declare(address: Int, type: String = SignalTable.TYPE_FLOAT) = cache.create(
        ownerId = OWNER, deviceId = TT, address = address, label = "s$address",
        type = type, historised = true, nowMs = 0
    )

    // ── Il évite vraiment la base ─────────────────────────────────────────

    @Test
    fun `a thousand reads of the same signal cost one database hit`() {
        declare(0)
        counting.finds.set(0)

        repeat(1_000) { cache.find(OWNER, TT, 0) }

        assertEquals(1, counting.finds.get())
    }

    @Test
    fun `an absent signal is remembered as absent`() {
        // Le cas de la boucle : une carte qui écrit sur une adresse jamais
        // déclarée. Ne cacher que les présences laisserait ce pire cas payer
        // une lecture par trame.
        counting.finds.set(0)

        repeat(500) { assertNull(cache.find(OWNER, TT, 99)) }

        assertEquals(1, counting.finds.get())
    }

    // ── Il n'écrit qu'au vidage, et en une fois ───────────────────────────

    @Test
    fun `a thousand values become one transaction`() {
        declare(0)

        repeat(1_000) { cache.touchBuffered(OWNER, TT, 0, "AAAA", 1_000L + it) }
        assertEquals(0, counting.touchAlls.get(), "rien ne doit partir avant le vidage")

        cache.flushPendingValues()

        assertEquals(1, counting.touchAlls.get())
        assertEquals(1, counting.rowsWritten.get(), "un seul signal, une seule ligne")
    }

    @Test
    fun `only the last value of a round reaches the disk`() {
        declare(0)
        cache.touchBuffered(OWNER, TT, 0, "OLD", 1_000)
        cache.touchBuffered(OWNER, TT, 0, "NEW", 2_000)

        cache.flushPendingValues()

        val row = assertNotNull(cache.find(OWNER, TT, 0))
        assertEquals("NEW", row.lastPayload)
        assertEquals(2_000L, row.lastSeenAt)
    }

    @Test
    fun `flushing an empty buffer touches nothing`() {
        assertEquals(0, cache.flushPendingValues())
        assertEquals(0, counting.touchAlls.get())
    }

    @Test
    fun `a value for an undeclared signal is refused rather than buffered`() {
        assertTrue(!cache.touchBuffered(OWNER, TT, 42, "AAAA", 1_000))
        assertEquals(0, cache.pendingCount())
    }

    // ── Il n'a jamais le droit de servir une ligne périmée ────────────────

    @Test
    fun `changing the type is visible on the next read`() {
        declare(0, SignalTable.TYPE_FLOAT)
        assertEquals(SignalTable.TYPE_FLOAT, cache.find(OWNER, TT, 0)?.type)

        cache.update(
            ownerId = OWNER, deviceId = TT, address = 0,
            label = null, unit = null, decimals = null, minValue = null, maxValue = null,
            historised = null, replayOnConnect = null, automationVisible = null,
            type = SignalTable.TYPE_BOOL, nowMs = 1
        )

        // Sans éviction, la garde de type continuerait d'accepter des flottants
        // sur une case devenue booléenne.
        assertEquals(SignalTable.TYPE_BOOL, cache.find(OWNER, TT, 0)?.type)
    }

    @Test
    fun `a deleted signal stops being found`() {
        declare(0)
        assertNotNull(cache.find(OWNER, TT, 0))

        cache.delete(OWNER, TT, 0)

        assertNull(cache.find(OWNER, TT, 0))
    }

    @Test
    fun `a signal created after a miss is found`() {
        // L'absence a été mémorisée : la création doit la lever, sinon un
        // signal tout neuf resterait invisible jusqu'au redémarrage.
        assertNull(cache.find(OWNER, TT, 3))

        declare(3)

        assertNotNull(cache.find(OWNER, TT, 3))
    }

    @Test
    fun `deleting a board forgets all its signals and their pending values`() {
        declare(0); declare(1)
        cache.touchBuffered(OWNER, TT, 0, "AAAA", 1_000)
        cache.find(OWNER, TT, 0); cache.find(OWNER, TT, 1)

        cache.deleteByDevice(OWNER, TT)

        assertNull(cache.find(OWNER, TT, 0))
        assertNull(cache.find(OWNER, TT, 1))
        assertEquals(0, cache.pendingCount(), "une valeur orpheline échouerait en silence")
    }

    @Test
    fun `purging an account forgets its signals`() {
        declare(0)
        cache.find(OWNER, TT, 0)

        cache.deleteByOwner(OWNER)

        assertNull(cache.find(OWNER, TT, 0))
    }

    // ── La consigne ne passe pas par le tampon ────────────────────────────

    @Test
    fun `a setpoint is on disk before the flush ever runs`() {
        declare(0)

        cache.touch(OWNER, TT, 0, "SETPOINT", 5_000)

        // Rien n'attend, et la valeur est lisible immédiatement : c'est ce qui
        // garantit qu'un redémarrage brutal ne rejoue pas une ancienne consigne.
        assertEquals(0, cache.pendingCount())
        assertEquals("SETPOINT", cache.find(OWNER, TT, 0)?.lastPayload)
    }

    @Test
    fun `a setpoint refreshes what the cache holds`() {
        declare(0)
        cache.find(OWNER, TT, 0)              // met l'ancienne ligne en cache

        cache.touch(OWNER, TT, 0, "SETPOINT", 5_000)

        assertEquals("SETPOINT", cache.find(OWNER, TT, 0)?.lastPayload)
    }
}
