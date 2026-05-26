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

// widget/domain/WidgetHistoryAggregateRow.kt
package com.jeanloickdt.widget.domain

/**
 * Row of an aggregation bucket (minute / hour / day).
 *
 * `bucketAt` is the timestamp of the bucket start, aligned to the granularity.
 * `sampleCount` is useful for weighting later aggregations (hour →
 * day) — in practice we do `SUM(avg * count) / SUM(count)`.
 */
data class WidgetHistoryAggregateRow(
    val id: Int,
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