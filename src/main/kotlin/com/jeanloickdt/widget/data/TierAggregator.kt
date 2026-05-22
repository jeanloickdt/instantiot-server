/*
 * InstantIoT Server — self-hosted IoT relay for makers.
 * Copyright (C) 2026 InstantIoT
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

// widget/data/TierAggregator.kt
package com.jeanloickdt.widget.data

import java.util.concurrent.ConcurrentHashMap

/**
 * RAM aggregator for one tier (minute, hour or day).
 *
 * Maintains a [ConcurrentHashMap] of [BucketAccumulator] indexed by
 * (widgetId, seriesId, bucketAt). When a sample arrives via [collect],
 * it feeds the current bucket; at regular intervals, the
 * flush job calls [extractClosedBuckets] to retrieve the buckets
 * whose window has ended and insert them into the DB.
 *
 * The "current" bucket (the one whose window has not yet closed)
 * stays in RAM to keep accumulating incoming samples.
 *
 * Thread-safety: the `ConcurrentHashMap` protects the insertions, and
 * each [BucketAccumulator] has its own internal lock for atomic
 * updates. No need for a global lock → high throughput even under
 * heavy multi-coroutine load.
 *
 * Architecture inspired by the Blynk Legacy Server (open source).
 *
 * @param bucketSizeMs bucket size in ms (60_000 / 3_600_000 / 86_400_000)
 */
class TierAggregator(
    val bucketSizeMs: Long
) {
    /**
     * Composite key identifying a bucket in the map. Reduced to a
     * hashable immutable triple to serve as a `ConcurrentHashMap` key.
     */
    private data class BucketKey(
        val widgetId: String,
        val seriesId: String?,
        val bucketAt: Long
    )

    private val buckets = ConcurrentHashMap<BucketKey, BucketAccumulator>()

    /**
     * Feeds the bucket corresponding to timestamp `ts` with `value`.
     * Creates the bucket if absent (race-free via `computeIfAbsent`).
     *
     * @param widgetId widget protocolId
     * @param seriesId series for multi-curve charts (null = single)
     * @param ts sample timestamp (ms epoch)
     * @param value numeric value of the sample
     * @param projectId propagated down to the bucket for owner isolation
     * @param ownerId propagated down to the bucket for owner isolation
     */
    fun collect(
        widgetId: String,
        seriesId: String?,
        ts: Long,
        value: Double,
        projectId: String,
        ownerId: String
    ) {
        val bucketAt = (ts / bucketSizeMs) * bucketSizeMs
        val key = BucketKey(widgetId, seriesId, bucketAt)
        val bucket = buckets.computeIfAbsent(key) {
            BucketAccumulator(
                widgetId  = widgetId,
                projectId = projectId,
                ownerId   = ownerId,
                seriesId  = seriesId,
                bucketAt  = bucketAt
            )
        }
        bucket.addSample(value)
    }

    /**
     * Extracts (= removes from the map + returns) all buckets whose
     * window has ended at `now`. A bucket is "closed" when
     * `bucketAt + bucketSizeMs <= now` (the bucket's window starts
     * at `bucketAt` and ends at `bucketAt + bucketSizeMs`).
     *
     * Called by the flush job every 5s. The current bucket
     * (`bucketAt + bucketSizeMs > now`) stays in the map to
     * keep accumulating.
     */
    fun extractClosedBuckets(now: Long): List<BucketAccumulator.Snapshot> {
        // We iterate over the keys; the map may be mutated meanwhile — that is
        // safe (ConcurrentHashMap) and concurrent insertions on the
        // current bucket are none of our concern.
        val closed = mutableListOf<BucketAccumulator.Snapshot>()
        val keysToRemove = mutableListOf<BucketKey>()

        for ((key, bucket) in buckets) {
            if (key.bucketAt + bucketSizeMs <= now) {
                closed += bucket.snapshot()
                keysToRemove += key
            }
        }

        // Remove in a second pass to avoid mutating during iteration
        // (even though ConcurrentHashMap supports it, this stays clearer).
        for (key in keysToRemove) {
            buckets.remove(key)
        }

        return closed
    }

    /**
     * Extracts ALL buckets, including the current one (= whose
     * window has not yet closed). Used ONLY on a clean shutdown
     * via the `ApplicationStopping` hook to lose nothing
     * during a controlled restart.
     */
    fun extractAllBuckets(): List<BucketAccumulator.Snapshot> {
        val all = mutableListOf<BucketAccumulator.Snapshot>()
        val keysToRemove = ArrayList(buckets.keys)
        for (key in keysToRemove) {
            buckets.remove(key)?.let { all += it.snapshot() }
        }
        return all
    }

    /** Number of buckets currently in RAM (useful for metrics / debug). */
    fun size(): Int = buckets.size
}