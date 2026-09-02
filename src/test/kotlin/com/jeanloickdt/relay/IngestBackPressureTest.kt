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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ce qu'on sacrifie quand l'écrivain ne suit plus.
 *
 * La règle est pure et sans base de données exprès : elle décide de jeter des
 * données, et ce genre de décision doit être atteignable par un test qui ne
 * demande ni serveur ni disque saturé.
 */
class IngestBackPressureTest {

    private val PERIOD = 5_000L

    /** Par défaut : 2 tours lents pour lâcher, 3 sains pour reprendre. */
    private fun pressure() = IngestBackPressure()

    private fun IngestBackPressure.slow(n: Int) = repeat(n) { record(PERIOD, PERIOD) }
    private fun IngestBackPressure.healthy(n: Int) = repeat(n) { record(100L, PERIOD) }

    // ── Un tour lent n'est pas une saturation ─────────────────────────────

    @Test
    fun `a single slow round changes nothing`() {
        // Un point de contrôle SQLite, une sauvegarde, un voisin bruyant sur
        // le VPS. Engager là-dessus ferait clignoter le brut plusieurs fois
        // par minute, et un palier troué est pire qu'un palier absent : le
        // trou se confond avec un capteur muet.
        val p = pressure()
        p.slow(1)
        assertFalse(p.isRawSuspended)
        assertTrue(p.allowRaw())
    }

    @Test
    fun `two slow rounds in a row let the raw tier go`() {
        val p = pressure()
        p.slow(2)
        assertTrue(p.isRawSuspended)
        assertFalse(p.allowRaw(), "le brut est un confort, la courbe est le produit")
    }

    @Test
    fun `a healthy round in between resets the streak`() {
        // Lent, sain, lent : deux tours lents mais pas d'affilée. Ce n'est pas
        // une dérive, c'est du bruit.
        val p = pressure()
        p.slow(1); p.healthy(1); p.slow(1)
        assertFalse(p.isRawSuspended)
    }

    // ── On reprend plus lentement qu'on ne lâche ──────────────────────────

    @Test
    fun `releasing takes more healthy rounds than engaging took slow ones`() {
        // Délibéré : la reprise coûte — le brut se remet à remplir la file que
        // l'écrivain vient tout juste de rattraper. Il faut qu'il ait rattrapé
        // pour de bon, pas qu'il ait soufflé un tour.
        val p = pressure()
        p.slow(2)
        assertTrue(p.isRawSuspended)

        p.healthy(2)
        assertTrue(p.isRawSuspended, "deux tours sains ne suffisent pas à reprendre")

        p.healthy(1)
        assertFalse(p.isRawSuspended)
    }

    @Test
    fun `a slow round during the recovery restarts the wait`() {
        val p = pressure()
        p.slow(2)
        p.healthy(2)
        p.slow(1)          // rechute
        p.healthy(2)
        assertTrue(p.isRawSuspended, "le compte des tours sains repart de zéro à la rechute")
    }

    // ── Le changement d'état se dit une fois ──────────────────────────────

    @Test
    fun `record reports the transition, not the state`() {
        // L'appelant journalise sur ce retour. S'il valait « suspendu » plutôt
        // que « vient de changer », le journal porterait la même ligne toutes
        // les cinq secondes pendant toute la durée de l'incident.
        val p = pressure()
        assertFalse(p.record(PERIOD, PERIOD), "premier tour lent : pas encore")
        assertTrue(p.record(PERIOD, PERIOD), "deuxième : c'est le basculement")
        assertFalse(p.record(PERIOD, PERIOD), "troisième : déjà dit")
    }

    // ── La dégradation se compte ──────────────────────────────────────────

    @Test
    fun `every dropped sample is counted`() {
        // Une dégradation que personne ne mesure est une dégradation qu'on
        // découvre par une plainte.
        val p = pressure()
        p.slow(2)
        repeat(7) { p.allowRaw() }
        assertEquals(7L, p.droppedRaw)
    }

    @Test
    fun `nothing is counted while the tier is flowing`() {
        val p = pressure()
        repeat(100) { p.allowRaw() }
        assertEquals(0L, p.droppedRaw)
    }

    // ── La frontière exacte ───────────────────────────────────────────────

    @Test
    fun `a round costing exactly its period counts as slow`() {
        // La boucle est `delay(période)` PUIS le travail : à durée égale à la
        // période, l'intervalle effectif a déjà doublé. C'est le début de la
        // dérive, pas encore son milieu.
        val p = pressure()
        repeat(2) { p.record(PERIOD, PERIOD) }
        assertTrue(p.isRawSuspended)
    }

    @Test
    fun `a round just under its period is healthy`() {
        val p = pressure()
        repeat(10) { p.record(PERIOD - 1, PERIOD) }
        assertFalse(p.isRawSuspended)
    }
}
