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
 * The ceiling one account may put on the relay.
 *
 * The case it exists for cannot be caught anywhere else: a hundred boards, each
 * perfectly within the per-board fuse, summing to more than a node holds. No
 * per-board rule can see a sum.
 */
class AccountRateLimiterTest {

    private var now = 0L
    private fun limiter(vararg limits: Pair<String, Int>) = AccountRateLimiter(
        limitFor = { owner -> limits.toMap()[owner] ?: 0 },
        clock = { now }
    )

    @Test
    fun `frames pass up to the account ceiling`() {
        val l = limiter("u1" to 5)
        repeat(5) { assertTrue(l.tryAcquire("u1"), "la trame $it doit passer") }
    }

    @Test
    fun `the frame past the ceiling is refused and counted`() {
        val l = limiter("u1" to 3)
        repeat(3) { l.tryAcquire("u1") }

        assertFalse(l.tryAcquire("u1"))
        assertEquals(1L, l.refusedCount("u1"))
    }

    @Test
    fun `the window rolls and the account breathes again`() {
        val l = limiter("u1" to 2)
        l.tryAcquire("u1"); l.tryAcquire("u1")
        assertFalse(l.tryAcquire("u1"))

        now += AccountRateLimiter.WINDOW_MS

        assertTrue(l.tryAcquire("u1"), "une seconde plus tard, le compte repart")
    }

    @Test
    fun `silence is not banked`() {
        // Le choix contre le seau à jetons : accumuler une heure de silence
        // pour la dépenser d'un coup est exactement la pointe qu'un nœud
        // unique ne sait pas absorber.
        val l = limiter("u1" to 2)
        now += 3_600_000L   // une heure sans rien envoyer

        assertTrue(l.tryAcquire("u1"))
        assertTrue(l.tryAcquire("u1"))
        assertFalse(l.tryAcquire("u1"), "le silence ne se capitalise pas")
    }

    @Test
    fun `accounts are smoothed independently`() {
        val l = limiter("u1" to 1, "u2" to 1)
        assertTrue(l.tryAcquire("u1"))
        assertFalse(l.tryAcquire("u1"))

        assertTrue(l.tryAcquire("u2"), "le plafond d'un compte n'est pas celui d'un autre")
    }

    @Test
    fun `a zero limit means unmetered — the self-hosted case`() {
        // Pas de fichier de plans, pas de lissage : la machine de l'utilisateur
        // est sa seule limite, et c'est son droit.
        val l = limiter("u1" to 0)
        repeat(10_000) { assertTrue(l.tryAcquire("u1")) }
        assertEquals(0L, l.refusedCount("u1"))
    }

    @Test
    fun `a hundred boards at ten frames a second exceed a Maker Pro ceiling`() {
        // Le scénario chiffré du brief : chaque carte est dans son droit,
        // la somme ne l'est pas.
        val l = limiter("pro" to 250)
        var accepted = 0
        repeat(100 * 10) { if (l.tryAcquire("pro")) accepted++ }

        assertEquals(250, accepted, "le lissage doit voir la somme")
        assertEquals(750L, l.refusedCount("pro"))
    }

    @Test
    fun `forgetting an account frees its window but keeps its diagnostic`() {
        val l = limiter("u1" to 1)
        l.tryAcquire("u1"); l.tryAcquire("u1")
        assertEquals(1L, l.refusedCount("u1"))

        l.forget("u1")

        assertTrue(l.tryAcquire("u1"), "la fenêtre est repartie de zéro")
        assertEquals(
            1L, l.refusedCount("u1"),
            "le compteur survit — l'utilisateur peut encore le lire dans l'app"
        )
    }
}
