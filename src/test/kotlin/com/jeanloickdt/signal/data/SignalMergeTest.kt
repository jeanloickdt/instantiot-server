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

package com.jeanloickdt.signal.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * La fonction dont dépend tout le reste du chantier : sans elle, l'étape 1
 * écrit un `INSERT ... DO NOTHING` qui perd la moitié la plus large de
 * deux essais au lieu de les fusionner — exactement le défaut d'aujourd'hui.
 */
class SignalMergeTest {

    private fun snap(
        min: Double, minAt: Long, max: Double, maxAt: Long, avg: Double, count: Int,
        signalId: Long = 1L, bucketAt: Long = 0L
    ) = SignalBucketAccumulator.Snapshot(
        signalId = signalId, ownerId = "u1", bucketAt = bucketAt,
        minValue = min, minAt = minAt, maxValue = max, maxAt = maxAt,
        avgValue = avg, sampleCount = count
    )

    // ── La moyenne pondérée, pas la moyenne des moyennes ──────────────────

    @Test
    fun `a weighted average, not an average of averages — the brief's own example`() {
        // 600 échantillons à 20, 6 échantillons à 30 : 20,1 pondéré, 25 en
        // naïf — 24% d'erreur sur le cas le plus banal de l'IoT, un device
        // qui décroche.
        val a = snap(min = 20.0, minAt = 0, max = 20.0, maxAt = 0, avg = 20.0, count = 600)
        val b = snap(min = 30.0, minAt = 1, max = 30.0, maxAt = 1, avg = 30.0, count = 6)
        val merged = SignalMerge.merge(a, b)
        assertEquals(20.099, merged.avgValue, 0.001)
        assertEquals(606, merged.sampleCount)
    }

    @Test
    fun `equal weights average plainly`() {
        val a = snap(10.0, 0, 10.0, 0, 10.0, 5)
        val b = snap(10.0, 0, 10.0, 0, 20.0, 5)
        assertEquals(15.0, SignalMerge.merge(a, b).avgValue)
    }

    // ── min_at / max_at suivent le côté qui gagne ─────────────────────────

    @Test
    fun `min_at follows whichever side actually holds the minimum`() {
        val a = snap(min = 5.0, minAt = 111L, max = 5.0, maxAt = 111L, avg = 5.0, count = 1)
        val b = snap(min = 2.0, minAt = 222L, max = 8.0, maxAt = 333L, avg = 5.0, count = 1)
        val merged = SignalMerge.merge(a, b)
        assertEquals(2.0, merged.minValue)
        assertEquals(222L, merged.minAt, "le minimum vient de b, son instant doit suivre")
        assertEquals(8.0, merged.maxValue)
        assertEquals(333L, merged.maxAt)
    }

    // ── LE piège : l'égalité stricte sur la valeur ────────────────────────

    @Test
    fun `on an exact tie, the older instant wins — deterministically`() {
        // Le cas que le brief nomme explicitement : meme minimum des deux
        // cotes. Sans regle, merge(a,b) et merge(b,a) pourraient diverger
        // sur min_at alors qu'ils s'accordent sur min_value.
        val a = snap(min = 5.0, minAt = 2_000L, max = 10.0, maxAt = 0, avg = 5.0, count = 1)
        val b = snap(min = 5.0, minAt = 1_000L, max = 10.0, maxAt = 0, avg = 5.0, count = 1)
        assertEquals(1_000L, SignalMerge.merge(a, b).minAt, "le plus ancien doit gagner, cote a ou b")
        assertEquals(1_000L, SignalMerge.merge(b, a).minAt, "et dans l'autre sens aussi — sinon ce n'est pas commutatif")
    }

    @Test
    fun `a tie on the maximum resolves the same way`() {
        val a = snap(min = 0.0, minAt = 0, max = 9.0, maxAt = 5_000L, avg = 9.0, count = 1)
        val b = snap(min = 0.0, minAt = 0, max = 9.0, maxAt = 3_000L, avg = 9.0, count = 1)
        assertEquals(3_000L, SignalMerge.merge(a, b).maxAt)
    }

    // ── Commutative ────────────────────────────────────────────────────────

    @Test
    fun `merge is commutative on every field`() {
        val a = snap(min = 5.0, minAt = 10L, max = 30.0, maxAt = 40L, avg = 18.0, count = 7)
        val b = snap(min = 2.0, minAt = 20L, max = 25.0, maxAt = 15L, avg = 12.0, count = 3)
        assertEquals(SignalMerge.merge(a, b), SignalMerge.merge(b, a))
    }

    // ── Associative — ce qui rend un rollup relancé sûr ───────────────────

    @Test
    fun `merge is associative — three independent samples, grouped either way`() {
        val a = snap(min = 5.0, minAt = 1L, max = 5.0, maxAt = 1L, avg = 5.0, count = 1)
        val b = snap(min = 10.0, minAt = 2L, max = 10.0, maxAt = 2L, avg = 10.0, count = 1)
        val c = snap(min = 1.0, minAt = 3L, max = 20.0, maxAt = 3L, avg = 15.0, count = 1)

        val leftFirst  = SignalMerge.merge(SignalMerge.merge(a, b), c)
        val rightFirst = SignalMerge.merge(a, SignalMerge.merge(b, c))

        assertEquals(leftFirst, rightFirst)
    }

    // ── Le redémarrage en milieu de fenêtre — test d'acceptation §7.2 ─────

    @Test
    fun `a window cut by a restart merges whole — the brief's second acceptance test`() {
        // Le seau minute est flushe, le process redemarre, le meme seau
        // reprend et se flushe a nouveau : deux morceaux d'UNE fenetre.
        val beforeRestart = snap(min = 18.0, minAt = 100L, max = 22.0, maxAt = 400L, avg = 20.0, count = 40)
        val afterRestart  = snap(min = 15.0, minAt = 500L, max = 25.0, maxAt = 900L, avg = 20.0, count = 20)

        val whole = SignalMerge.merge(beforeRestart, afterRestart)

        assertEquals(60, whole.sampleCount, "aucune fenetre amputee — les deux moities comptent")
        assertEquals(15.0, whole.minValue)
        assertEquals(25.0, whole.maxValue)
    }

    // ── Le garde-fou ───────────────────────────────────────────────────────

    @Test
    fun `merging two different buckets is refused, not silently averaged`() {
        val a = snap(min = 1.0, minAt = 0, max = 1.0, maxAt = 0, avg = 1.0, count = 1, signalId = 1L)
        val b = snap(min = 1.0, minAt = 0, max = 1.0, maxAt = 0, avg = 1.0, count = 1, signalId = 2L)
        assertFailsWith<IllegalArgumentException> { SignalMerge.merge(a, b) }
    }

    @Test
    fun `merging two different time windows of the same signal is refused too`() {
        val a = snap(min = 1.0, minAt = 0, max = 1.0, maxAt = 0, avg = 1.0, count = 1, bucketAt = 0L)
        val b = snap(min = 1.0, minAt = 0, max = 1.0, maxAt = 0, avg = 1.0, count = 1, bucketAt = 60_000L)
        assertFailsWith<IllegalArgumentException> { SignalMerge.merge(a, b) }
    }
}
