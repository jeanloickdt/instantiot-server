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

// widget/data/WidgetHistoryTable.kt
package com.jeanloickdt.widget.data

import org.jetbrains.exposed.sql.Table

object WidgetHistoryTable : Table("widget_history") {
    // auto-increment — internal table, not exposed in the API
    val id         = integer("id").autoIncrement()
    val widgetId   = text("widget_id")   // FK → widgets.id
    val projectId  = text("project_id")  // query per project without JOIN
    val ownerId    = text("owner_id")    // isolation without JOIN
    val payload    = text("payload")     // raw base64 PAYLOAD — opaque server
    val recordedAt = long("recorded_at") // record timestamp
    override val primaryKey = PrimaryKey(id)
}