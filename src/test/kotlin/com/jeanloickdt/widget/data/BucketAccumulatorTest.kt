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

package com.jeanloickdt.widget.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests the aggregation math of [BucketAccumulator] — the min/max/avg/count
 * that gets persisted into the minute/hour/day history tiers and kept (for the
 * day tier) potentially forever. A bug here means permanently wrong summaries.
 */
class BucketAccumulatorTest {

    private fun bucket() = BucketAccumulator(
        widgetId = "w", projectId = "p", ownerId = "o", seriesId = null, bucketAt = 0L
    )

    @Test
    fun `single sample yields min equals max equals avg equals value`() {
        val b = bucket()
        b.addSample(23.5)
        val s = b.snapshot()
        assertEquals(23.5, s.minValue)
        assertEquals(23.5, s.maxValue)
        assertEquals(23.5, s.avgValue, 1e-9)
        assertEquals(1, s.sampleCount)
    }

    @Test
    fun `multiple samples compute min, max, avg and count`() {
        val b = bucket()
        listOf(10.0, 30.0, 20.0).forEach(b::addSample)
        val s = b.snapshot()
        assertEquals(10.0, s.minValue)
        assertEquals(30.0, s.maxValue)
        assertEquals(20.0, s.avgValue, 1e-9) // (10+30+20)/3
        assertEquals(3, s.sampleCount)
    }

    @Test
    fun `average is the mean of all samples, not a running approximation`() {
        val b = bucket()
        listOf(1.0, 2.0, 4.0).forEach(b::addSample)
        // 7 / 3 = 2.333... — exercises non-terminating division
        assertEquals(7.0 / 3.0, b.snapshot().avgValue, 1e-9)
    }

    @Test
    fun `negative and mixed values are handled`() {
        val b = bucket()
        listOf(-5.0, 5.0, -10.0, 10.0).forEach(b::addSample)
        val s = b.snapshot()
        assertEquals(-10.0, s.minValue)
        assertEquals(10.0, s.maxValue)
        assertEquals(0.0, s.avgValue, 1e-9)
        assertEquals(4, s.sampleCount)
    }

    @Test
    fun `empty bucket snapshot has count zero and avg zero`() {
        val s = bucket().snapshot()
        assertEquals(0, s.sampleCount)
        assertEquals(0.0, s.avgValue, 1e-9)
        // documented initial sentinels for an untouched bucket
        assertEquals(Double.POSITIVE_INFINITY, s.minValue)
        assertEquals(Double.NEGATIVE_INFINITY, s.maxValue)
    }

    @Test
    fun `snapshot is non-destructive and reflects later samples`() {
        val b = bucket()
        b.addSample(10.0)
        val first = b.snapshot()
        assertEquals(1, first.sampleCount)

        b.addSample(20.0)
        val second = b.snapshot()
        assertEquals(2, second.sampleCount)
        assertEquals(15.0, second.avgValue, 1e-9)
        // the earlier snapshot is immutable — unaffected by later samples
        assertEquals(1, first.sampleCount)
        assertEquals(10.0, first.avgValue, 1e-9)
    }

    @Test
    fun `snapshot carries the bucket identity fields`() {
        val b = BucketAccumulator("widgetX", "projY", "ownerZ", "L1", 60_000L)
        b.addSample(1.0)
        val s = b.snapshot()
        assertEquals("widgetX", s.widgetId)
        assertEquals("projY", s.projectId)
        assertEquals("ownerZ", s.ownerId)
        assertEquals("L1", s.seriesId)
        assertEquals(60_000L, s.bucketAt)
    }

    @Test
    fun `concurrent samples are all counted`() {
        val b = bucket()
        val threads = (1..8).map { t ->
            Thread {
                repeat(1000) { b.addSample((t * 1000 + it).toDouble()) }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        // 8 threads × 1000 samples — no lost updates under the internal lock
        assertEquals(8000, b.snapshot().sampleCount)
    }
}
