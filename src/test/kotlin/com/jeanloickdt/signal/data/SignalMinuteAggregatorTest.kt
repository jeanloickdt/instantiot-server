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
import kotlin.test.assertTrue

/**
 * Un seul palier accumulé — pas trois. Heure et jour sont vides jusqu'à
 * l'étape qui les dérive (§3 du brief) ; ce fichier ne teste que ce qui
 * existe déjà : la fermeture des seaux minute.
 */
class SignalMinuteAggregatorTest {

    private val agg = SignalMinuteAggregator(bucketSizeMs = 60_000L)

    @Test
    fun `a bucket whose window has not ended stays in RAM`() {
        agg.collect(1L, "u1", ts = 0L, value = 10.0)
        assertTrue(agg.extractClosedBuckets(now = 30_000L).isEmpty())
        assertEquals(1, agg.size())
    }

    @Test
    fun `a bucket closes once its window has fully elapsed`() {
        agg.collect(1L, "u1", ts = 0L, value = 10.0)
        val closed = agg.extractClosedBuckets(now = 60_000L)
        assertEquals(1, closed.size)
        assertEquals(0, agg.size(), "un seau extrait ne doit pas rester en mémoire")
    }

    @Test
    fun `two signals never share a bucket, even at the same instant`() {
        agg.collect(1L, "u1", ts = 0L, value = 10.0)
        agg.collect(2L, "u1", ts = 0L, value = 999.0)
        val closed = agg.extractClosedBuckets(now = 60_000L)
        assertEquals(2, closed.size)
        assertEquals(10.0, closed.first { it.signalId == 1L }.avgValue)
        assertEquals(999.0, closed.first { it.signalId == 2L }.avgValue)
    }

    @Test
    fun `extractAllBuckets takes the current bucket too — the clean-shutdown path`() {
        agg.collect(1L, "u1", ts = 0L, value = 10.0)
        assertEquals(1, agg.extractAllBuckets().size)
        assertEquals(0, agg.size())
    }
}
