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

// widget/domain/WidgetHistoryNumericRow.kt
package com.jeanloickdt.widget.domain

/**
 * Row of the decoded numeric history.
 *
 * `seriesId` is `null` for simple widgets (gauge, metric, level,
 * slider). For multi-series charts, it is the series identifier
 * sent by the device ("line1", "temperature", etc.).
 */
data class WidgetHistoryNumericRow(
    val id: Int,
    val widgetId: String,
    val projectId: String,
    val ownerId: String,
    val seriesId: String?,
    val value: Double,
    val recordedAt: Long
)