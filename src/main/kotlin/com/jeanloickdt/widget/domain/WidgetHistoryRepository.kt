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

// widget/domain/WidgetHistoryRepository.kt
package com.jeanloickdt.widget.domain

interface WidgetHistoryRepository {

    // Insert a payload into the history — called by the relay
    // Throttle 1/sec max per widget — handled on the relay side
    fun insert(widgetId: String, projectId: String, ownerId: String, payload: String)

    // Batch insert — called by the relay every 5s
    // More performant than N individual inserts
    fun insertBatch(entries: List<WidgetHistoryRow>)

    // History by time range — for GET /api/widgets/{id}/history-raw.
    // Scoped to ownerId: widgetId is a global PK but protocolIds collide across
    // users, so owner_id (carried by the table for isolation) must be in the
    // query, not only the route gate.
    fun findByWidgetAndRange(widgetId: String, ownerId: String, from: Long, to: Long): List<WidgetHistoryRow>

    // Cleanup — delete rows older than 24h
    // Called automatically at startup and every hour
    fun deleteOlderThan(timestamp: Long)

    // Delete all history of a widget — called on DELETE /api/widgets/{id}
    // Scoped to (ownerId, widgetId): another owner may hold history under the
    // same widgetId — deleting one widget must not wipe theirs.
    fun deleteAllByWidget(ownerId: String, widgetId: String)

    // Delete all history of a project — called on DELETE /api/projects/{id}
    fun deleteAllByProject(projectId: String)
}