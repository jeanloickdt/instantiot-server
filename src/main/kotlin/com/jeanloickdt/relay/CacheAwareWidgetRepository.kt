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

// relay/CacheAwareWidgetRepository.kt
package com.jeanloickdt.relay

import com.jeanloickdt.widget.domain.LastPayloadUpdate
import com.jeanloickdt.widget.domain.WidgetRepository
import com.jeanloickdt.widget.domain.WidgetRow

/**
 * Decorator that makes the node-local RAM caches an invariant of every write to
 * the `widgets` table — `knownWidgetIds` and `lastValues` always reflect what
 * the table holds.
 *
 * The bug this closes: cache sync used to live in the routes, so the two delete
 * paths diverged. `DELETE /widgets/{id}` purged the caches, but the project
 * cascade (`DELETE /projects/{id}` → [deleteAllByProject]) did NOT — it didn't
 * even receive the caches — leaving phantom keys in RAM (and, with auto-register
 * on, a widget that could never re-register, so no history if it re-emitted).
 * Centralising sync in this one decorator closes the cascade hole BY
 * CONSTRUCTION: any caller that mutates widgets goes through here.
 *
 * The wrapped [inner] (SqliteWidgetRepository) stays pure data-access — it knows
 * nothing about the relay caches. This decorator is built at the composition
 * root (Application.module), where the per-run caches exist.
 *
 * Boot seeding of `knownWidgetIds` from the table is done once in module()
 * (via [findAll]); this decorator only keeps it in sync afterwards.
 */
class CacheAwareWidgetRepository(
    private val inner: WidgetRepository,
    private val knownWidgetIds: MutableSet<WidgetKey>,
    private val lastValues: LastValueCache
) : WidgetRepository {

    override fun register(id: String, projectId: String, ownerId: String, type: String) {
        inner.register(id, projectId, ownerId, type)
        knownWidgetIds.add(WidgetKey(ownerId, id))
    }

    override fun registerIfAbsent(id: String, projectId: String, ownerId: String, type: String): Boolean {
        val created = inner.registerIfAbsent(id, projectId, ownerId, type)
        // Add regardless of `created`: if the row exists in the table it must be
        // in the cache (idempotent on a concurrent set).
        knownWidgetIds.add(WidgetKey(ownerId, id))
        return created
    }

    override fun delete(ownerId: String, id: String): Boolean {
        val deleted = inner.delete(ownerId, id)
        if (deleted) purge(ownerId, id)
        return deleted
    }

    /**
     * Cascade delete of a project's widgets. Order is non-negotiable: read the
     * widgets FIRST (to learn their (ownerId, id) keys), THEN delete, THEN purge
     * the cache. Deleting first would lose the very keys we need to purge — the
     * cascade bug.
     */
    override fun deleteAllByProject(projectId: String) {
        val keys = inner.findAllByProject(projectId).map { WidgetKey(it.ownerId, it.id) }
        inner.deleteAllByProject(projectId)
        keys.forEach { purge(it.ownerId, it.widgetId) }
    }

    private fun purge(ownerId: String, id: String) {
        knownWidgetIds.remove(WidgetKey(ownerId, id))
        lastValues.evict(ownerId, id)
    }

    // ── pure delegation (no cache impact) ──
    override fun findById(ownerId: String, id: String): WidgetRow? = inner.findById(ownerId, id)
    override fun findAllByProject(projectId: String): List<WidgetRow> = inner.findAllByProject(projectId)
    override fun findAll(): List<WidgetRow> = inner.findAll()
    override fun updateLastPayloadBatch(updates: List<LastPayloadUpdate>) = inner.updateLastPayloadBatch(updates)
}
