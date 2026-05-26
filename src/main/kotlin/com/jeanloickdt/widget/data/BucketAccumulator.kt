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

// widget/data/BucketAccumulator.kt
package com.jeanloickdt.widget.data

/**
 * Thread-safe accumulator for an in-RAM aggregation bucket.
 *
 * One instance represents ONE bucket (= ONE (widgetId, seriesId) pair
 * × ONE time interval) being filled. The samples that
 * arrive during this bucket's window are accumulated via
 * [addSample], then the bucket is flushed to the DB by [TierAggregator]
 * once its window has closed.
 *
 * Thread-safety: all mutable fields are protected by an internal
 * lock (`synchronized`). Several samples may arrive
 * simultaneously from different coroutines (TCP relay), which is fine.
 *
 * Trivial serialization (primitive types only) — preparation
 * for the future anti-crash SnapshotManager.
 *
 * @param widgetId widget protocolId (shared app/device key)
 * @param projectId project the widget belongs to
 * @param ownerId project owner (multi-user isolation)
 * @param seriesId series for multi-curve charts (null = single-series)
 * @param bucketAt start timestamp of the bucket in ms (aligned on bucketSize)
 */
class BucketAccumulator(
    val widgetId: String,
    val projectId: String,
    val ownerId: String,
    val seriesId: String?,
    val bucketAt: Long
) {
    // Internal lock — synchronized() on this. The cost of a monitor enter
    // over a few nanoseconds is negligible vs the benefit of being able to
    // handle bursts (a sensor at 5Hz × 100 widgets = 500 samples/s).
    private var _minValue: Double = Double.POSITIVE_INFINITY
    private var _maxValue: Double = Double.NEGATIVE_INFINITY
    private var _sumValue: Double = 0.0
    private var _sampleCount: Int = 0

    /**
     * Adds a sample to the bucket. Updates min/max/sum/count
     * atomically.
     */
    fun addSample(value: Double) {
        synchronized(this) {
            if (value < _minValue) _minValue = value
            if (value > _maxValue) _maxValue = value
            _sumValue += value
            _sampleCount++
        }
    }

    /**
     * Immutable snapshot of the accumulators at call time.
     * Used by [TierAggregator.extractClosedBuckets] before the DB flush.
     */
    fun snapshot(): Snapshot = synchronized(this) {
        Snapshot(
            widgetId    = widgetId,
            projectId   = projectId,
            ownerId     = ownerId,
            seriesId    = seriesId,
            bucketAt    = bucketAt,
            minValue    = _minValue,
            maxValue    = _maxValue,
            avgValue    = if (_sampleCount > 0) _sumValue / _sampleCount else 0.0,
            sampleCount = _sampleCount
        )
    }

    /**
     * Immutable representation of a bucket ready to be flushed to the DB.
     * All fields are primitive / String → easily serializable
     * for a future anti-crash snapshot.
     */
    data class Snapshot(
        val widgetId: String,
        val projectId: String,
        val ownerId: String,
        val seriesId: String?,
        val bucketAt: Long,
        val minValue: Double,
        val maxValue: Double,
        val avgValue: Double,
        val sampleCount: Int
    )
}