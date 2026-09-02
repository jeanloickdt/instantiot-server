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
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.event.EventSinks
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.relay.HistoryBuffers
import com.jeanloickdt.relay.InMemoryLastValueCache
import com.jeanloickdt.signal.data.SignalTable
import com.jeanloickdt.signal.data.ExposedSignalRepository
import com.jeanloickdt.auth.data.UserTable
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guard, seen from the relay rather than from the rule.
 *
 * [SignalTypeCompatTest] proves the rank. This proves the consequence: a frame
 * that does not fit reaches neither RAM nor disk, and the reason is kept where
 * the app can read it.
 */
class SignalTypeGuardIngestTest {

    private val OWNER = "u1"
    private val TT = "dev-tt"
    private val PROJ = "p1"

    private lateinit var signals: ExposedSignalRepository
    private lateinit var buffers: HistoryBuffers
    private lateinit var lastValues: InMemoryLastValueCache
    private lateinit var sinks: EventSinks

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.fresh()
        signals = ExposedSignalRepository()
        buffers = HistoryBuffers()
        lastValues = InMemoryLastValueCache()
        sinks = EventSinks()
        TypeMismatches.reset()
        UndeclaredSignals.reset()
        // Isolation entre tests : l'agrégateur est un singleton global au
        // process de test, pas une instance par classe.
        com.jeanloickdt.signal.data.SignalAggregators.minute.extractAllBuckets()
    }

    private fun declare(address: Int, type: String) = signals.create(
        ownerId = OWNER, deviceId = TT, address = address, label = "s$address",
        type = type, historised = true, nowMs = 0
    )

    private fun send(address: Int, tag: Int, payload: ByteArray, now: Long = 1_000) =
        ingestSignalFrame(
            frameBytes = SignalFrame.build(address, tag, payload),
            ownerId = OWNER, deviceId = TT, deviceName = TT, projectId = PROJ,
            signals = signals, buffers = buffers, lastValues = lastValues,
            rawAllowed = true, sinks = sinks, watched = { false }, nowMs = now
        )

    private fun intBytes(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    // Indexé par `signal.id`, l'entier interne — l'agrégateur widget qui
    // vivait à côté, indexé par une chaîne, a été retiré avec son modèle.
    private fun storedBuckets() =
        com.jeanloickdt.signal.data.SignalAggregators.minute.extractAllBuckets().size

    // ── Ce qui entre sans perte passe ─────────────────────────────────────

    @Test
    fun `an int frame is accepted by a float slot`() {
        // instant.write(I0, 0) — la surcharge entière de la lib sur une case
        // flottante. Le cas qui a fait la règle.
        declare(0, SignalTable.TYPE_FLOAT)

        assertTrue(send(0, SignalFrame.TAG_INT, intBytes(21)))
        assertEquals(1, storedBuckets())
        assertNull(TypeMismatches.stateOf(TT, 0))
    }

    @Test
    fun `a bool frame is accepted by an int slot`() {
        declare(1, SignalTable.TYPE_INT)
        assertTrue(send(1, SignalFrame.TAG_BOOL, byteArrayOf(1)))
        assertEquals(1, storedBuckets())
    }

    // ── Ce qui perdrait est refusé, et ne touche rien ─────────────────────

    @Test
    fun `a float frame is refused by a bool slot and reaches nothing`() {
        declare(6, SignalTable.TYPE_BOOL)

        assertFalse(send(6, SignalFrame.TAG_FLOAT, SignalFrame.floatBytes(21.5f)))

        // Ni disque, ni agrégat, ni valeur courante : la trame n'existe pas.
        assertEquals(0, storedBuckets())
        assertEquals(0, buffers.signalRawBuffer.size)
        assertNull(signals.find(OWNER, TT, 6)?.lastPayload)
    }

    @Test
    fun `a text frame is refused by a numeric slot`() {
        declare(2, SignalTable.TYPE_FLOAT)
        assertFalse(send(2, SignalFrame.TAG_STRING, "ouvert".toByteArray()))
        assertEquals(0, storedBuckets())
    }

    @Test
    fun `a numeric frame is refused by a text slot`() {
        declare(3, SignalTable.TYPE_STRING)
        assertFalse(send(3, SignalFrame.TAG_FLOAT, SignalFrame.floatBytes(1f)))
        assertEquals(0, storedBuckets())
    }

    // ── Le refus se raconte ───────────────────────────────────────────────

    @Test
    fun `a refusal keeps what the app needs to explain it`() {
        declare(6, SignalTable.TYPE_BOOL)
        send(6, SignalFrame.TAG_FLOAT, SignalFrame.floatBytes(21.5f), now = 5_000)

        val state = assertNotNull(TypeMismatches.stateOf(TT, 6))
        assertEquals(1L, state.count)
        assertEquals(SignalTable.TYPE_BOOL, state.expectedType)
        assertEquals(SignalTable.TYPE_FLOAT, state.receivedType)
        assertEquals(5_000L, state.lastAtMs)
    }

    @Test
    fun `refusals accumulate on the same signal`() {
        declare(6, SignalTable.TYPE_BOOL)
        repeat(3) { send(6, SignalFrame.TAG_FLOAT, SignalFrame.floatBytes(1f), now = 1_000L + it) }

        assertEquals(3L, assertNotNull(TypeMismatches.stateOf(TT, 6)).count)
    }

    @Test
    fun `clearing forgets a complaint about a declaration that changed`() {
        declare(6, SignalTable.TYPE_BOOL)
        send(6, SignalFrame.TAG_FLOAT, SignalFrame.floatBytes(1f))
        assertNotNull(TypeMismatches.stateOf(TT, 6))

        TypeMismatches.clear(TT, 6)

        assertNull(TypeMismatches.stateOf(TT, 6))
    }

    // ── Le trou se referme tout seul ──────────────────────────────────────

    @Test
    fun `the board agreeing again resumes ingestion without any action`() {
        // Le scénario complet : I6 passe en booléen, la carte envoie encore du
        // flottant, puis elle est reflashée. Rien à réactiver entre les deux.
        declare(6, SignalTable.TYPE_BOOL)

        assertFalse(send(6, SignalFrame.TAG_FLOAT, SignalFrame.floatBytes(21.5f), now = 1_000))
        assertEquals(0, storedBuckets())

        assertTrue(send(6, SignalFrame.TAG_BOOL, byteArrayOf(1), now = 120_000))
        assertEquals(1, storedBuckets())
    }

}
