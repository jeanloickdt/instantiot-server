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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalHistoryQueryTest {

    private val MINUTE = 60_000L
    private val HOUR = 3_600_000L
    private val DAY = 86_400_000L

    private fun win(g: String, available: Boolean = true, retention: String = "30d", fromMs: Long? = null) =
        HistoryWindows.Window(g, available, retention, fromMs)

    /** Tout ouvert, sans borne — le cas auto-hébergé. */
    private val open = HistoryWindows.TIERS.map { win(it.granularity, retention = "unlimited") }

    // ── Le choix automatique ──────────────────────────────────────────────

    @Test
    fun `a short span gets the finest tier`() {
        assertEquals("min", SignalHistoryQuery.autoPick(0L, 6 * HOUR))
    }

    @Test
    fun `the default 24h view lands on the minute — the case the target is calibrated for`() {
        // 1440 seaux. Si la cible descendait sous ce chiffre, la vue par
        // defaut passerait a l'heure et rendrait 24 points pour une journee.
        assertEquals("min", SignalHistoryQuery.autoPick(0L, DAY))
    }

    @Test
    fun `a span of weeks steps up to the hour`() {
        // 30 jours en minute, ce serait 43 200 seaux — bien au-dessus de la
        // cible de mille.
        assertEquals("hour", SignalHistoryQuery.autoPick(0L, 30 * DAY))
    }

    @Test
    fun `a span of years steps up to the day`() {
        assertEquals("day", SignalHistoryQuery.autoPick(0L, 365 * DAY))
    }

    @Test
    fun `every automatic choice stays under the target point count`() {
        // La propriete qui compte, verifiee sur toute la gamme plutot que
        // sur trois exemples choisis : la reponse a la meme taille que la
        // plage soit d'une heure ou d'un an.
        val spans = listOf(MINUTE, HOUR, 6 * HOUR, DAY, 7 * DAY, 30 * DAY, 365 * DAY)
        for (span in spans) {
            val tier = SignalHistoryQuery.autoPick(0L, span)
            val buckets = span / SignalHistoryQuery.BUCKET_MS[tier]!!
            assertTrue(
                buckets <= SignalHistoryQuery.TARGET_POINTS,
                "span=$span → $tier donne $buckets seaux, au-dessus de la cible"
            )
        }
    }

    @Test
    fun `auto never picks raw — its density cannot be bounded in advance`() {
        val spans = listOf(0L, MINUTE, HOUR, DAY, 365 * DAY, 3650 * DAY)
        for (span in spans) {
            assertTrue(
                SignalHistoryQuery.autoPick(0L, span) != "raw",
                "auto a choisi raw sur span=$span — sa densite est inconnaissable d'avance"
            )
        }
    }

    @Test
    fun `an absurd span still answers, with the coarsest tier`() {
        // Presque trois ans depasse la cible meme en jour. Refuser serait
        // pire que rendre trois mille points.
        assertEquals("day", SignalHistoryQuery.autoPick(0L, 10_000 * DAY))
    }

    @Test
    fun `a reversed range does not crash`() {
        assertNotNull(SignalHistoryQuery.autoPick(1000L, 0L))
    }

    // ── La demande explicite est honorée ──────────────────────────────────

    @Test
    fun `an explicit resolution is served as asked, silently`() {
        val d = SignalHistoryQuery.resolve("raw", 0L, HOUR, open).getOrThrow()
        assertEquals("raw", d.resolution)
        assertNull(d.notice, "servir ce qui est demande ne merite aucune annonce")
    }

    @Test
    fun `an unknown resolution is refused by name`() {
        val r = SignalHistoryQuery.resolve("weekly", 0L, HOUR, open)
        assertTrue(r.isFailure)
    }

    // ── Ne pas savoir n'est pas savoir que non ────────────────────────────

    @Test
    fun `with no window information at all, the request is served plainly`() {
        val d = SignalHistoryQuery.resolve("min", 0L, HOUR, emptyList()).getOrThrow()
        assertEquals("min", d.resolution)
        assertNull(d.notice)
    }

    // ── Le palier non vendu bascule, il ne rend pas du vide ───────────────

    @Test
    fun `a tier the plan does not sell falls back to a coarser one, and says so`() {
        val windows = listOf(
            win("raw", available = false, retention = "0d"),
            win("min", available = true, retention = "7d"),
            win("hour", available = true, retention = "7d"),
            win("day", available = true, retention = "7d")
        )
        val d = SignalHistoryQuery.resolve("raw", 0L, HOUR, windows).getOrThrow()
        assertEquals("min", d.resolution, "on bascule sur ce qui existe")
        assertNotNull(d.notice, "une bascule muette laisserait croire a une panne")
        assertTrue(d.notice!!.contains("raw"))
    }

    @Test
    fun `when nothing at all is sold, the refusal is explicit rather than an empty list`() {
        val windows = HistoryWindows.TIERS.map { win(it.granularity, available = false, retention = "0d") }
        val d = SignalHistoryQuery.resolve("min", 0L, HOUR, windows).getOrThrow()
        assertNotNull(d.notice)
    }

    // ── La rétention dépassée se nomme ────────────────────────────────────

    @Test
    fun `asking beyond the retention still serves, and names the boundary`() {
        val now = 1_000_000_000L
        val windows = listOf(
            win("min", available = true, retention = "7d", fromMs = now - 7 * DAY),
            win("hour", available = true, retention = "30d", fromMs = now - 30 * DAY),
            win("day", available = true, retention = "unlimited", fromMs = null)
        )
        val d = SignalHistoryQuery.resolve("min", now - 30 * DAY, now, windows).getOrThrow()
        assertEquals("min", d.resolution, "on sert ce qu'on a plutot que de refuser")
        assertNotNull(d.notice)
        assertTrue(d.notice!!.contains("7d"), "la frontiere doit etre nommee — c'est le moment de conversion")
    }

    @Test
    fun `a range fully inside the retention says nothing`() {
        val now = 1_000_000_000L
        val windows = listOf(win("min", available = true, retention = "7d", fromMs = now - 7 * DAY))
        val d = SignalHistoryQuery.resolve("min", now - DAY, now, windows).getOrThrow()
        assertNull(d.notice)
    }

    @Test
    fun `an unlimited tier never announces a boundary`() {
        val windows = listOf(win("day", available = true, retention = "unlimited", fromMs = null))
        val d = SignalHistoryQuery.resolve("day", 0L, 1_000_000_000L, windows).getOrThrow()
        assertNull(d.notice)
    }

    // ── Le plafond de lignes existe et il est nommé ───────────────────────

    @Test
    fun `the row cap is above the target point count — truncation is the exception`() {
        assertTrue(
            SignalHistoryQuery.MAX_ROWS > SignalHistoryQuery.TARGET_POINTS,
            "un auto qui vise mille points ne doit jamais declencher la troncature"
        )
    }
}
