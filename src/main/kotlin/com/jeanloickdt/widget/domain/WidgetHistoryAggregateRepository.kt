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

// widget/domain/WidgetHistoryAggregateRepository.kt
package com.jeanloickdt.widget.domain

/**
 * Repository for an aggregation table (min / hour / day).
 * One instance = one granularity (bound to the matching SQLite table).
 */
interface WidgetHistoryAggregateRepository {

    /**
     * Reads the buckets for a widget and a time window.
     * `seriesId` null → all series (otherwise filters on the series).
     */
    fun findByWidgetAndRange(
        widgetId: String,
        from: Long,
        to: Long,
        seriesId: String? = null
    ): List<WidgetHistoryAggregateRow>

    /**
     * Inserts a batch of buckets.
     *
     * Idempotent via the UNIQUE INDEX `(widget_id, COALESCE(series_id, ''),
     * bucket_at)` created in `DatabaseFactory.init` — a retry after a
     * partial crash does not create duplicates.
     *
     * Used by the 5s flush job that drains closed buckets from the
     * in-RAM `TierAggregator` to the DB.
     */
    fun insertBatch(rows: List<AggregateInsertRow>)

    /**
     * Insert row (without the auto-incremented `id`).
     * Lighter than [WidgetHistoryAggregateRow] which holds the
     * post-insert id.
     */
    data class AggregateInsertRow(
        val widgetId: String,
        val projectId: String,
        val ownerId: String,
        val seriesId: String?,
        val avgValue: Double,
        val minValue: Double,
        val maxValue: Double,
        val sampleCount: Int,
        val bucketAt: Long
    )

    /**
     * Deletes rows older than `timestamp` ms — called by the
     * cleanup cron (per-tier retention, configurable).
     */
    fun deleteOlderThan(timestamp: Long)

    /** Cascade DELETE widget. */
    fun deleteAllByWidget(widgetId: String)

    /** Cascade DELETE project. */
    fun deleteAllByProject(projectId: String)
}