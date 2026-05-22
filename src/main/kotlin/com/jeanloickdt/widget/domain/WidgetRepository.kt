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

// widget/domain/WidgetRepository.kt
package com.jeanloickdt.widget.domain

interface WidgetRepository {

    // Register a widget — called when the app adds a widget
    // The server stores id + type only — no geometry nor settings
    fun register(id: String, projectId: String, ownerId: String, type: String)

    /**
     * Registers the widget if it does not exist yet (no-op otherwise).
     *
     * Used by the auto-register in `DeviceRelay.handleDeviceFrame`:
     * the first frame of an unknown protocolId widget creates the row
     * in `widgets` with `type="auto"` → REST history lookups then
     * work without the app having to POST explicitly.
     *
     * SQLite implementation: `INSERT OR IGNORE`. Returns `true` if the
     * row was created, `false` if it already existed.
     */
    fun registerIfAbsent(id: String, projectId: String, ownerId: String, type: String): Boolean

    // Find a widget by its id
    fun findById(id: String): WidgetRow?

    // List all widgets of a project — for GET /api/projects/{id}/states
    fun findAllByProject(projectId: String): List<WidgetRow>

    // Update last_payload + last_seen_at — called by the relay only
    fun updateLastPayload(id: String, payload: String, timestamp: Long)

    // Delete a widget + cascade history
    fun delete(id: String): Boolean

    // Delete all widgets of a project — called on DELETE /api/projects/{id}
    fun deleteAllByProject(projectId: String)
}