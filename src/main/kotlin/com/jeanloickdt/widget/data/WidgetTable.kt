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

// widget/data/WidgetTable.kt
package com.jeanloickdt.widget.data

import org.jetbrains.exposed.sql.Table

object WidgetTable : Table("widgets") {
    val id          = text("id")
    val projectId   = text("project_id")
    val ownerId     = text("owner_id")
    val type        = text("type")                     // "display" | "command" — for widget_history
    val lastPayload = text("last_payload").nullable()  // raw base64 PAYLOAD — written by relay only
    val lastSeenAt  = long("last_seen_at").nullable()  // timestamp of last ESP frame — written by relay

    // Composite PK (owner_id, id): widgetId is global but protocolIds collide
    // across users. With a single-column PK, the 2nd owner registering "gauge1"
    // is a silent INSERT-OR-IGNORE no-op → they never get a row, get locked out
    // (404 on their own widget) while the 1st owner's reads spill the 2nd's data.
    // Keying on (owner_id, id) gives each tenant their own row. Migrating an
    // existing single-column-PK DB to this is non-additive — see DatabaseFactory.
    override val primaryKey = PrimaryKey(ownerId, id)
}