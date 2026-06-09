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
import kotlin.test.assertTrue

/**
 * Tests [TierAggregator] — bucket keying, window alignment, and the close
 * semantics that decide WHEN a summary is flushed. This is exactly the logic
 * behind "the day bucket is only written when its 24h window ends": a bucket
 * closes only when `bucketAt + bucketSize <= now`, the current bucket stays in
 * RAM, and `extractAllBuckets` (shutdown) drains even the open one.
 *
 * Uses a small 1000 ms bucket so the alignment math is easy to read.
 */
class TierAggregatorTest {

    private val SIZE = 1000L
    private fun aggregator() = TierAggregator(bucketSizeMs = SIZE)

    private fun collect(agg: TierAggregator, ts: Long, value: Double, widgetId: String = "w", seriesId: String? = null) =
        agg.collect(widgetId, seriesId, ts, value, projectId = "p", ownerId = "o")

    @Test
    fun `bucketAt is floor-aligned to the window start`() {
        val agg = aggregator()
        collect(agg, ts = 1500, value = 1.0) // window [1000, 2000)
        val closed = agg.extractAllBuckets()
        assertEquals(1, closed.size)
        assertEquals(1000L, closed[0].bucketAt)
    }

    @Test
    fun `samples in the same window land in one bucket`() {
        val agg = aggregator()
        collect(agg, ts = 100, value = 10.0)
        collect(agg, ts = 200, value = 20.0)
        collect(agg, ts = 999, value = 30.0) // still window [0, 1000)
        assertEquals(1, agg.size())

        val s = agg.extractAllBuckets().single()
        assertEquals(0L, s.bucketAt)
        assertEquals(10.0, s.minValue)
        assertEquals(30.0, s.maxValue)
        assertEquals(20.0, s.avgValue, 1e-9) // (10+20+30)/3
        assertEquals(3, s.sampleCount)
    }

    @Test
    fun `samples in different windows create separate buckets`() {
        val agg = aggregator()
        collect(agg, ts = 500, value = 1.0)  // window [0, 1000)
        collect(agg, ts = 1500, value = 2.0) // window [1000, 2000)
        assertEquals(2, agg.size())
    }

    @Test
    fun `different series in the same window are separate buckets`() {
        val agg = aggregator()
        collect(agg, ts = 100, value = 1.0, seriesId = "L1")
        collect(agg, ts = 100, value = 2.0, seriesId = "L2")
        assertEquals(2, agg.size())
    }

    @Test
    fun `different widgets in the same window are separate buckets`() {
        val agg = aggregator()
        collect(agg, ts = 100, value = 1.0, widgetId = "wA")
        collect(agg, ts = 100, value = 2.0, widgetId = "wB")
        assertEquals(2, agg.size())
    }

    @Test
    fun `extractClosedBuckets does NOT return a still-open bucket`() {
        val agg = aggregator()
        collect(agg, ts = 100, value = 1.0) // window [0, 1000)
        // now = 500 → 0 + 1000 > 500 → not closed
        val closed = agg.extractClosedBuckets(now = 500)
        assertTrue(closed.isEmpty())
        // the open bucket stays in RAM to keep accumulating
        assertEquals(1, agg.size())
    }

    @Test
    fun `extractClosedBuckets returns and removes a bucket once its window ended`() {
        val agg = aggregator()
        collect(agg, ts = 100, value = 7.0) // window [0, 1000)
        // now = 1000 → 0 + 1000 <= 1000 → closed
        val closed = agg.extractClosedBuckets(now = 1000)
        assertEquals(1, closed.size)
        assertEquals(0L, closed[0].bucketAt)
        assertEquals(7.0, closed[0].avgValue, 1e-9)
        // removed from the map after extraction
        assertEquals(0, agg.size())
    }

    @Test
    fun `extractClosedBuckets flushes only the closed windows, keeping the current one`() {
        val agg = aggregator()
        collect(agg, ts = 100, value = 1.0)  // closed window [0, 1000)
        collect(agg, ts = 1500, value = 2.0) // current window [1000, 2000)
        // now = 1200 → window [0,1000) closed, window [1000,2000) still open
        val closed = agg.extractClosedBuckets(now = 1200)
        assertEquals(1, closed.size)
        assertEquals(0L, closed[0].bucketAt)
        // the current bucket survives
        assertEquals(1, agg.size())
    }

    @Test
    fun `extractAllBuckets drains everything including the open current bucket`() {
        val agg = aggregator()
        collect(agg, ts = 100, value = 1.0)
        collect(agg, ts = 1500, value = 2.0)
        // shutdown path: take everything, even the not-yet-closed bucket
        val all = agg.extractAllBuckets()
        assertEquals(2, all.size)
        assertEquals(0, agg.size())
    }

    @Test
    fun `extracting from an empty aggregator returns nothing`() {
        val agg = aggregator()
        assertTrue(agg.extractClosedBuckets(now = 999_999).isEmpty())
        assertTrue(agg.extractAllBuckets().isEmpty())
        assertEquals(0, agg.size())
    }
}
