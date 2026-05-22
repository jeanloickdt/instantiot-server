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

// widget/domain/WidgetRow.kt
package com.jeanloickdt.widget.domain

data class WidgetRow(
    val id: String,
    val projectId: String,
    val ownerId: String,
    val type: String,          // "display" | "command"
    val lastPayload: String?,  // raw base64 PAYLOAD — written by relay only
    val lastSeenAt: Long?      // timestamp of last ESP frame — written by relay
)