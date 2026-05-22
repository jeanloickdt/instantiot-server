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

// project/data/WidgetTable.kt
package com.jeanloickdt.project.data

import org.jetbrains.exposed.sql.Table

object ProjectTable : Table("projects") {
    val id         = text("id")
    val ownerId    = text("owner_id")
    val name       = text("name")
    val layoutJson = text("layout_json").default("{}") // full ProjectLayout — opaque blob
    val createdAt  = long("created_at")
    val updatedAt  = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}