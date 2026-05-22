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

// widget/data/WidgetHistoryNumericTable.kt
package com.jeanloickdt.widget.data

import org.jetbrains.exposed.sql.Table

/**
 * **Numeric** history of analog widgets (gauge, metric,
 * level, slider, chart). Populated in parallel with the `widget_history`
 * table (opaque Base64) but only when `FrameParser.extractNumericValue`
 * can decode a value.
 *
 * ## Why a separate table
 * - Fast `AVG(value)` / `MIN/MAX` query for downsampling (Phase 2)
 * - No Base64 decoding on read → the REST API serves JSON directly
 * - The opaque `widget_history` remains the source of truth for non-numeric payloads
 *   (buttons, segmented switch, direction pad, etc.)
 *
 * ## Series (seriesId)
 * `null` for simple widgets (gauge, metric, level, slider).
 * For multi-series charts: "line1", "temp", etc.
 */
object WidgetHistoryNumericTable : Table("widget_history_numeric") {
    val id         = integer("id").autoIncrement()
    val widgetId   = text("widget_id")              // FK → widgets.id
    val projectId  = text("project_id")             // query per project without JOIN
    val ownerId    = text("owner_id")               // isolation without JOIN
    val seriesId   = text("series_id").nullable()   // null for non-chart widgets
    val value      = double("value")                // IEEE 754 double (promoted from Float)
    val recordedAt = long("recorded_at")            // timestamp ms epoch
    override val primaryKey = PrimaryKey(id)
}