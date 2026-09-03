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

class SignalRollupTest {

    private fun minute(
        bucketAt: Long, min: Double, minAt: Long, max: Double, maxAt: Long, avg: Double, count: Int,
        signalId: Long = 1L
    ) = SignalBucketAccumulator.Snapshot(
        signalId = signalId, ownerId = "u1", bucketAt = bucketAt,
        minValue = min, minAt = minAt, maxValue = max, maxAt = maxAt,
        avgValue = avg, sampleCount = count
    )

    @Test
    fun `sixty minutes fold into one hour, tagged on the hour's own bucket`() {
        val minutes = (0 until 60).map { i ->
            minute(bucketAt = i * 60_000L, min = 10.0 + i, minAt = i * 60_000L,
                max = 10.0 + i, maxAt = i * 60_000L, avg = 10.0 + i, count = 1)
        }
        val hour = SignalRollup.combine(0L, minutes)
        assertEquals(0L, hour.bucketAt, "le seau produit porte le bucket CIBLE, pas celui d'un morceau")
        assertEquals(60, hour.sampleCount)
        assertEquals(10.0, hour.minValue)
        assertEquals(69.0, hour.maxValue)
    }

    @Test
    fun `the source bucketAt of each piece is irrelevant to the result — only the values matter`() {
        // Deux jeux de morceaux, memes valeurs, bucketAt source completement
        // differents (l'un a des bucketAt d'heure, l'autre de minute) : le
        // resultat doit etre identique, puisque combine() retague avant de
        // fusionner.
        val a = SignalRollup.combine(999L, listOf(
            minute(bucketAt = 0L,      min = 5.0, minAt = 1L, max = 9.0, maxAt = 2L, avg = 7.0, count = 4),
            minute(bucketAt = 60_000L, min = 3.0, minAt = 3L, max = 12.0, maxAt = 4L, avg = 8.0, count = 2)
        ))
        val b = SignalRollup.combine(999L, listOf(
            minute(bucketAt = 5_000_000L, min = 5.0, minAt = 1L, max = 9.0, maxAt = 2L, avg = 7.0, count = 4),
            minute(bucketAt = 6_000_000L, min = 3.0, minAt = 3L, max = 12.0, maxAt = 4L, avg = 8.0, count = 2)
        ))
        assertEquals(a, b)
    }

    @Test
    fun `the fold order does not change the result — the same guarantee merge already has`() {
        val pieces = listOf(
            minute(0L, 5.0, 1L, 9.0, 2L, 7.0, 4),
            minute(60_000L, 3.0, 3L, 12.0, 4L, 8.0, 2),
            minute(120_000L, 1.0, 5L, 20.0, 6L, 15.0, 9)
        )
        assertEquals(SignalRollup.combine(0L, pieces), SignalRollup.combine(0L, pieces.reversed()))
        assertEquals(SignalRollup.combine(0L, pieces), SignalRollup.combine(0L, pieces.shuffled(kotlin.random.Random(42))))
    }

    @Test
    fun `an empty group is refused rather than producing a fake bucket`() {
        assertFailsWith<IllegalArgumentException> { SignalRollup.combine(0L, emptyList()) }
    }

    @Test
    fun `mixing two signals is refused, not silently blended`() {
        val pieces = listOf(
            minute(0L, 1.0, 0L, 1.0, 0L, 1.0, 1, signalId = 1L),
            minute(0L, 1.0, 0L, 1.0, 0L, 1.0, 1, signalId = 2L)
        )
        assertFailsWith<IllegalArgumentException> { SignalRollup.combine(0L, pieces) }
    }

    // ── truncateTo ──────────────────────────────────────────────────────────

    @Test
    fun `truncateTo finds the start of the containing window`() {
        assertEquals(0L, SignalRollup.truncateTo(0L, SignalRollup.HOUR_MS))
        assertEquals(0L, SignalRollup.truncateTo(3_599_999L, SignalRollup.HOUR_MS))
        assertEquals(SignalRollup.HOUR_MS, SignalRollup.truncateTo(3_600_000L, SignalRollup.HOUR_MS))
    }

    @Test
    fun `a full day of hours truncates to the same day bucket`() {
        val dayStart = 10 * SignalRollup.DAY_MS
        for (h in 0 until 24) {
            assertEquals(dayStart, SignalRollup.truncateTo(dayStart + h * SignalRollup.HOUR_MS, SignalRollup.DAY_MS))
        }
    }
}
