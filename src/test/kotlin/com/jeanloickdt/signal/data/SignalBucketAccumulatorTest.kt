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
 * `min_at`/`max_at` — l'instant exact de l'extrême, pas seulement sa valeur.
 *
 * C'est la seule vraie nouveauté par rapport à `BucketAccumulator` : le
 * reste (min/max/moyenne/compte) est la même mécanique. Ces tests portent
 * donc uniquement sur ce qui est neuf.
 */
class SignalBucketAccumulatorTest {

    @Test
    fun `min_at and max_at track the sample that set them, not the bucket`() {
        val acc = SignalBucketAccumulator(signalId = 1L, ownerId = "u1", bucketAt = 0L)
        acc.addSample(20.0, atMs = 1_000L)
        acc.addSample(5.0,  atMs = 1_500L)   // nouveau min
        acc.addSample(30.0, atMs = 1_900L)   // nouveau max
        acc.addSample(15.0, atMs = 1_950L)   // ni min ni max — ne doit rien deplacer

        val s = acc.snapshot()
        assertEquals(5.0, s.minValue)
        assertEquals(1_500L, s.minAt)
        assertEquals(30.0, s.maxValue)
        assertEquals(1_900L, s.maxAt)
    }

    @Test
    fun `a single sample is its own min and max, at its own instant`() {
        val acc = SignalBucketAccumulator(1L, "u1", 0L)
        acc.addSample(42.0, atMs = 777L)
        val s = acc.snapshot()
        assertEquals(777L, s.minAt)
        assertEquals(777L, s.maxAt)
    }

    @Test
    fun `a non-finite sample is dropped, min_at included`() {
        // Meme garde que BucketAccumulator : un NaN comparerait toujours
        // faux et empoisonnerait min/max pour la vie du seau — donc min_at
        // aussi, silencieusement, si la garde manquait.
        val acc = SignalBucketAccumulator(1L, "u1", 0L)
        acc.addSample(10.0, atMs = 100L)
        acc.addSample(Double.NaN, atMs = 200L)
        acc.addSample(Double.POSITIVE_INFINITY, atMs = 300L)
        val s = acc.snapshot()
        assertEquals(1, s.sampleCount)
        assertEquals(100L, s.minAt)
        assertEquals(100L, s.maxAt)
    }

    @Test
    fun `the weighted pieces are unaffected — count, sum, average`() {
        val acc = SignalBucketAccumulator(1L, "u1", 0L)
        acc.addSample(10.0, 1L)
        acc.addSample(20.0, 2L)
        acc.addSample(30.0, 3L)
        val s = acc.snapshot()
        assertEquals(3, s.sampleCount)
        assertEquals(20.0, s.avgValue)
    }

    @Test
    fun `an empty bucket has no meaningful instant, but does not crash`() {
        val s = SignalBucketAccumulator(1L, "u1", 0L).snapshot()
        assertEquals(0, s.sampleCount)
        assertTrue(s.minValue.isInfinite() && s.maxValue.isInfinite())
    }
}
