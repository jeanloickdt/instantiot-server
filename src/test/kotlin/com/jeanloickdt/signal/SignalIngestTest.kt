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

import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.event.EventSinks
import com.jeanloickdt.event.RelayEvent
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.relay.HistoryBuffers
import com.jeanloickdt.relay.InMemoryLastValueCache
import com.jeanloickdt.relay.SignalRef
import com.jeanloickdt.signal.data.SignalTable
import com.jeanloickdt.signal.data.ExposedSignalRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Une trame SIGNAL synthétique atteint le store, la cascade et les sinks de
 * règles.
 *
 * L'assertion sur la cascade a changé de cible depuis l'étape 1 du passage au
 * modèle signal : un signal historisé alimente désormais
 * `SignalAggregators.minute` (indexé par `signal.id`, l'entier de l'étape 0)
 * — pas `HistoryAggregators` (indexé par la chaîne `widgetId`), qui reste le
 * chemin de l'ancien modèle jusqu'à ce qu'il soit retiré.
 *
 * Les assertions les plus intéressantes restent les refus. Un chemin qui
 * accepte trop corrompt l'historique de quelqu'un en silence, et le silence
 * est exactement le défaut que ce chemin existe pour terminer.
 */
class SignalIngestTest {

    private val OWNER = "u1"
    private val TT = "dev-tt"
    private val BB = "dev-bb"
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
        UndeclaredSignals.reset()
        // Isolation entre tests : l'agrégateur est un singleton global au
        // process de test, pas une instance par classe.
        com.jeanloickdt.signal.data.SignalAggregators.minute.extractAllBuckets()
    }

    private fun declare(
        device: String, address: Int, historised: Boolean = true, label: String = "s$address"
    ) = signals.create(
        ownerId = OWNER, deviceId = device, address = address, label = label,
        type = SignalTable.TYPE_FLOAT, historised = historised, nowMs = 0
    )

    private fun ingest(
        device: String, address: Int, value: Float, watched: Boolean = false, now: Long = 1_000
    ) = ingestSignalFrame(
        frameBytes = SignalFrame.build(address, SignalFrame.TAG_FLOAT, SignalFrame.floatBytes(value)),
        ownerId = OWNER, deviceId = device, deviceName = device, projectId = PROJ,
        signals = signals, buffers = buffers, lastValues = lastValues,
        rawAllowed = true, sinks = sinks, watched = { watched }, nowMs = now
    )

    private fun drained(): List<RelayEvent.SignalValue> = buildList {
        while (true) add((sinks.values.tryReceive().getOrNull() ?: break) as RelayEvent.SignalValue)
    }

    // ── Le chemin nominal ─────────────────────────────────────────────────

    @Test
    fun `a declared address lands in the store, the cascade and the sinks`() {
        declare(TT, 5)

        assertTrue(ingest(TT, 5, 23.4f, watched = true))

        val key = signalKey(TT, 5)
        assertNotNull(lastValues.get(OWNER, key), "the widget must have something to paint on open")
        assertNotNull(signals.find(OWNER, TT, 5)!!.lastSeenAt)

        val event = drained().single()
        assertEquals(23.4, event.value, 0.0001)
        assertEquals(key, event.signalKey, "the rule keys on the signal, board included")

        val bucket = com.jeanloickdt.signal.data.SignalAggregators.minute.extractAllBuckets().single()
        assertEquals(
            signals.find(OWNER, TT, 5)!!.id, bucket.signalId,
            "le seau doit être indexé par l'entier du signal, pas par la chaîne widgetId"
        )
    }

    // ── Les refus ─────────────────────────────────────────────────────────

    @Test
    fun `an UNDECLARED address is refused — and no longer in silence`() {
        val accepted = ingest(TT, 9, 1f)

        assertFalse(accepted)
        assertNull(lastValues.get(OWNER, signalKey(TT, 9)))
        assertEquals(1L, UndeclaredSignals.snapshot()["$TT:9"],
            "a firmware typo used to leave no trace at all — that is what this counter ends")
    }

    @Test
    fun `an address declared on ANOTHER board is refused`() {
        declare(BB, 5)   // I5 exists — but on bb

        assertFalse(ingest(TT, 5, 1f),
            "addresses are enumerated per board precisely so two sketches never have to agree")
        assertNull(lastValues.get(OWNER, signalKey(TT, 5)))
    }

    @Test
    fun `the same address on two boards never collides`() {
        declare(TT, 5); declare(BB, 5)

        ingest(TT, 5, 10f, watched = true)
        ingest(BB, 5, 20f, watched = true)

        assertEquals(10.0, SignalFrame.numericValue(
            SignalFrame.build(5, SignalFrame.TAG_FLOAT, SignalFrame.floatBytes(10f)))!!, 0.0)
        val keys = drained().map { it.signalKey }.toSet()
        assertEquals(setOf(signalKey(TT, 5), signalKey(BB, 5)), keys,
            "one rule on tt's I5 must never fire on bb's")
    }

    // ── L'historisation est un booléen ────────────────────────────────────

    @Test
    fun `historised false keeps the last value but feeds nothing`() {
        declare(TT, 3, historised = false)

        assertTrue(ingest(TT, 3, 42f))

        assertNotNull(lastValues.get(OWNER, signalKey(TT, 3)),
            "the current value always exists — it costs one overwritten row")
        assertEquals(0, com.jeanloickdt.signal.data.SignalAggregators.minute.extractAllBuckets().size,
            "…but nothing is kept: that is the whole point of being able to say no")
        assertEquals(0, buffers.signalRawBuffer.size)
    }

    @Test
    fun `a rule can watch a signal nobody historises`() {
        declare(TT, 4, historised = false)

        ingest(TT, 4, 7f, watched = true)

        assertEquals(1, drained().size,
            "the rule feed is not conditioned on history — an alert on an unkept value is legitimate")
    }

    // ── Les types ─────────────────────────────────────────────────────────

    @Test
    fun `a string is stored and relayed, but never aggregated`() {
        // Déclarée EN TEXTE : depuis la garde de type, une case flottante
        // refuse un texte — un texte n'entre que dans une case texte.
        signals.create(
            ownerId = OWNER, deviceId = TT, address = 6, label = "s6",
            type = SignalTable.TYPE_STRING, historised = true, nowMs = 0
        )

        val accepted = ingestSignalFrame(
            frameBytes = SignalFrame.build(6, SignalFrame.TAG_STRING, "OK".toByteArray()),
            ownerId = OWNER, deviceId = TT, deviceName = TT, projectId = PROJ,
            signals = signals, buffers = buffers, lastValues = lastValues,
            rawAllowed = true, sinks = sinks, watched = { true }, nowMs = 1_000
        )

        assertTrue(accepted, "a text is a legitimate value — it is simply not a curve")
        assertNotNull(lastValues.get(OWNER, signalKey(TT, 6)))
        assertEquals(0, com.jeanloickdt.signal.data.SignalAggregators.minute.extractAllBuckets().size)
        assertTrue(drained().isEmpty(), "no numeric sample, so no rule event")
    }

    @Test
    fun `a malformed address is dropped without touching anything`() {
        declare(TT, 1)
        // A signal TYPE with a multi-byte WID: the address cannot be read.
        val body = byteArrayOf(0x00, 0x04) + "btn1".toByteArray() +
            byteArrayOf(SignalFrame.TYPE_SIGNAL.toByte(), SignalFrame.TAG_FLOAT.toByte()) +
            SignalFrame.floatBytes(1f)
        var crc = 0
        for (b in body) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        val frame = byteArrayOf(0xAA.toByte(), 0x01, body.size.toByte(), 0x00) + body + byteArrayOf(crc.toByte())

        assertFalse(
            ingestSignalFrame(
                frameBytes = frame, ownerId = OWNER, deviceId = TT, deviceName = TT, projectId = PROJ,
                signals = signals, buffers = buffers, lastValues = lastValues,
                rawAllowed = true, sinks = sinks, watched = { true }, nowMs = 1_000
            )
        )
        assertEquals(0, com.jeanloickdt.signal.data.SignalAggregators.minute.extractAllBuckets().size)
    }

    // ── Le dépôt ──────────────────────────────────────────────────────────

    @Test
    fun `addresses are attributed densely, and a taken one is refused`() {
        assertEquals(0, signals.nextFreeAddress(OWNER, TT))
        declare(TT, 0); declare(TT, 1)
        assertEquals(2, signals.nextFreeAddress(OWNER, TT),
            "dense, so the sketch reads I0 I1 I2 instead of a scatter")

        assertFalse(declare(TT, 1), "a taken address is an ordinary refusal, not an exception")
        assertTrue(declare(BB, 1), "…and it is free on another board")
    }

    @Test
    fun `deleting a board erases its signals and only its own`() {
        declare(TT, 0); declare(TT, 1); declare(BB, 0)

        assertEquals(2, signals.deleteByDevice(OWNER, TT))
        assertTrue(signals.listByDevice(OWNER, TT).isEmpty())
        assertEquals(1, signals.listByDevice(OWNER, BB).size)
    }

    // ── La contre-pression : le brut d'abord, jamais la courbe ────────────

    @Test
    fun `under back-pressure the raw tier is dropped and the curve is not`() {
        // L'acceptation de la contre-pression, vue du chemin d'ingestion.
        // [IngestBackPressureTest] prouve la regle ; celui-ci prouve qu'elle
        // est CONSULTEE — une regle juste que personne n'appelle protege
        // exactement rien.
        declare(TT, 5)
        // Le brut est actif par defaut ; si un jour ce n'est plus vrai, ce
        // test mesurerait le mauvais interrupteur et il vaut mieux qu'il le
        // dise que de passer pour une raison qui n'est pas la sienne.
        assertTrue(com.jeanloickdt.common.ServerConfig.historyRawEnabled)

        // Deux tours de vidage au-dela de leur periode : la pression engage.
        repeat(2) { buffers.backPressure.record(5_000L, 5_000L) }
        assertTrue(buffers.backPressure.isRawSuspended)

        assertTrue(ingest(TT, 5, 23.4f))

        assertEquals(0, buffers.signalRawBuffer.size, "le brut est lache — c'est le confort")
        assertEquals(1L, buffers.backPressure.droppedRaw, "et l'ecart se compte")
        assertEquals(
            1, com.jeanloickdt.signal.data.SignalAggregators.minute.extractAllBuckets().size,
            "la courbe, elle, reste complete — c'est le produit"
        )
    }

    @Test
    fun `once the pressure releases the raw tier fills again`() {
        declare(TT, 5)
        assertTrue(com.jeanloickdt.common.ServerConfig.historyRawEnabled)

        repeat(2) { buffers.backPressure.record(5_000L, 5_000L) }
        repeat(3) { buffers.backPressure.record(10L, 5_000L) }

        assertTrue(ingest(TT, 5, 23.4f))
        assertEquals(1, buffers.signalRawBuffer.size)
    }
}
