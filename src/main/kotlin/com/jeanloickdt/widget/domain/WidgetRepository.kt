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

    // Find a widget by its (ownerId, id) — widgetId alone is ambiguous under the
    // composite PK (one row per owner for a colliding protocolId).
    fun findById(ownerId: String, id: String): WidgetRow?

    // List all widgets of a project — for GET /api/projects/{id}/states
    fun findAllByProject(projectId: String): List<WidgetRow>

    // Every widget across all owners/projects — used once at boot to seed the
    // declared-widgets cache (knownWidgetIds) so it reflects the table.
    fun findAll(): List<WidgetRow>

    /**
     * Batch variant — one transaction for N widgets. Called by the 5s flush
     * job with the LastValueCache's dirty entries (coalesced persistence:
     * N frames per cycle → at most 1 write per changed widget, never per frame).
     * Each update targets (owner_id, id): an owner-blind WHERE id=? would write
     * one owner's payload onto every other owner's row sharing that protocolId.
     */
    fun updateLastPayloadBatch(updates: List<LastPayloadUpdate>)

    // Delete a widget + cascade history — scoped to (ownerId, id)
    fun delete(ownerId: String, id: String): Boolean

    // Delete all widgets of a project — called on DELETE /api/projects/{id}
    fun deleteAllByProject(projectId: String)
}

/** One coalesced last-payload persistence entry (see [WidgetRepository.updateLastPayloadBatch]). */
data class LastPayloadUpdate(
    val ownerId: String,
    val widgetId: String,
    val payload: String,
    val at: Long
)