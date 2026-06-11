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

// relay/LastValueCache.kt
package com.jeanloickdt.relay

import java.util.concurrent.ConcurrentHashMap

/**
 * Identity of a widget across the node-local RAM structures (last-value cache,
 * declared-set). widgetId (= protocolId) is a GLOBAL identifier, but protocolIds
 * (gauge1, temp…) are chosen locally per user and collide across tenants — so a
 * widget is identified by (ownerId, widgetId), never widgetId alone, otherwise
 * two owners' live values stomp the same cache entry (last-writer-wins).
 */
data class WidgetKey(val ownerId: String, val widgetId: String)

/** A widget's last received payload (base64) and when it arrived. */
data class LastValue(val payload: String, val at: Long)

/**
 * The real-time "last value" of every widget — the read path the app uses
 * (states on reconnect) and the single write the device read-loop performs
 * per frame (pure RAM, never DB).
 *
 * Persistence of `widgets.last_payload` is COALESCED: the 5s flush job calls
 * [drainDirty] and batch-upserts one row per changed widget (instead of one
 * DB write per frame). The DB column is therefore a cold-start fallback that
 * may lag ≤5s — the live value always comes from this cache.
 *
 * Certain seam: a future multi-node deployment swaps [InMemoryLastValueCache]
 * for a shared implementation (e.g. Redis) without touching any call site.
 */
interface LastValueCache {
    fun put(ownerId: String, widgetId: String, payload: String, at: Long)
    fun get(ownerId: String, widgetId: String): LastValue?

    /**
     * Returns the entries modified since the last drain and clears their dirty
     * mark — the flush job persists exactly these. Keyed by [WidgetKey] so the
     * flush can upsert `widgets` by (owner_id, id). Accepted design point: a
     * frame landing between the mark-clear and the value read may defer that
     * widget's persistence by one cycle (≤10s worst case); harmless because
     * the live value is RAM and the DB column is cold-start only.
     */
    fun drainDirty(): Map<WidgetKey, LastValue>

    /** Drops a widget's entry (widget deleted) — RAM-leak/correctness hook. */
    fun evict(ownerId: String, widgetId: String)
}

class InMemoryLastValueCache : LastValueCache {
    private val values = ConcurrentHashMap<WidgetKey, LastValue>()
    private val dirty = ConcurrentHashMap.newKeySet<WidgetKey>()

    override fun put(ownerId: String, widgetId: String, payload: String, at: Long) {
        val key = WidgetKey(ownerId, widgetId)
        values[key] = LastValue(payload, at)
        dirty.add(key)
    }

    override fun get(ownerId: String, widgetId: String): LastValue? = values[WidgetKey(ownerId, widgetId)]

    override fun drainDirty(): Map<WidgetKey, LastValue> {
        val keys = HashSet(dirty)
        dirty.removeAll(keys)
        return keys.mapNotNull { key -> values[key]?.let { key to it } }.toMap()
    }

    override fun evict(ownerId: String, widgetId: String) {
        val key = WidgetKey(ownerId, widgetId)
        values.remove(key)
        dirty.remove(key)
    }
}
