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

// widget/domain/WidgetHistoryNumericRepository.kt
package com.jeanloickdt.widget.domain

interface WidgetHistoryNumericRepository {

    /** Batch insert — called by the relay every 5s. */
    fun insertBatch(entries: List<WidgetHistoryNumericRow>)

    /**
     * Numeric history by time range.
     * If [seriesId] is `null`, returns **all** samples of the widget
     * (all series combined for a chart, or the single series for simple
     * widgets). If non-null, filters on that specific series.
     */
    fun findByWidgetAndRange(
        widgetId: String,
        from: Long,
        to: Long,
        seriesId: String? = null
    ): List<WidgetHistoryNumericRow>

    /** Deletes rows older than [timestamp] ms — called by the cleanup cron. */
    fun deleteOlderThan(timestamp: Long)

    /** Deletes all numeric history of a widget — DELETE /api/widgets/{id}. */
    fun deleteAllByWidget(widgetId: String)

    /** Deletes all numeric history of a project — DELETE /api/projects/{id}. */
    fun deleteAllByProject(projectId: String)
}