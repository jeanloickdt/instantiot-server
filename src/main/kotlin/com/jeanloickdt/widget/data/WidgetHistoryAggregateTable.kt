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

// widget/data/WidgetHistoryAggregateTable.kt
package com.jeanloickdt.widget.data

import org.jetbrains.exposed.sql.Table

/**
 * Shared schema of the 3 history aggregation tables:
 *   - widget_history_min   (bucket = 1 minute)
 *   - widget_history_hour  (bucket = 1 hour)
 *   - widget_history_day   (bucket = 1 day)
 *
 * Each bucket stores:
 *   - `avg_value`: average of the raw samples falling within the window
 *   - `min_value` / `max_value`: extrema (useful for band charts)
 *   - `sample_count`: number of aggregated points
 *   - `bucket_at`: start timestamp of the bucket (aligned on the granularity)
 *
 * Uniqueness via UNIQUE INDEX `(widget_id, COALESCE(series_id, ''), bucket_at)`
 * created in `DatabaseFactory.init` → idempotent INSERT OR IGNORE.
 */
abstract class WidgetHistoryAggregateTable(tableName: String) : Table(tableName) {
    val id          = integer("id").autoIncrement()
    val widgetId    = text("widget_id")
    val projectId   = text("project_id")
    val ownerId     = text("owner_id")
    val seriesId    = text("series_id").nullable()
    val avgValue    = double("avg_value")
    val minValue    = double("min_value")
    val maxValue    = double("max_value")
    val sampleCount = integer("sample_count")
    val bucketAt    = long("bucket_at")
    override val primaryKey = PrimaryKey(id)
}

object WidgetHistoryMinTable  : WidgetHistoryAggregateTable("widget_history_min")
object WidgetHistoryHourTable : WidgetHistoryAggregateTable("widget_history_hour")
object WidgetHistoryDayTable  : WidgetHistoryAggregateTable("widget_history_day")