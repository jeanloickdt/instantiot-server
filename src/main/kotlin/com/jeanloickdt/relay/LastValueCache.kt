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
    fun put(widgetId: String, payload: String, at: Long)
    fun get(widgetId: String): LastValue?

    /**
     * Returns the entries modified since the last drain and clears their dirty
     * mark — the flush job persists exactly these. Accepted design point: a
     * frame landing between the mark-clear and the value read may defer that
     * widget's persistence by one cycle (≤10s worst case); harmless because
     * the live value is RAM and the DB column is cold-start only.
     */
    fun drainDirty(): Map<String, LastValue>

    /** Drops a widget's entry (widget deleted) — RAM-leak/correctness hook. */
    fun evict(widgetId: String)
}

class InMemoryLastValueCache : LastValueCache {
    private val values = ConcurrentHashMap<String, LastValue>()
    private val dirty = ConcurrentHashMap.newKeySet<String>()

    override fun put(widgetId: String, payload: String, at: Long) {
        values[widgetId] = LastValue(payload, at)
        dirty.add(widgetId)
    }

    override fun get(widgetId: String): LastValue? = values[widgetId]

    override fun drainDirty(): Map<String, LastValue> {
        val ids = HashSet(dirty)
        dirty.removeAll(ids)
        return ids.mapNotNull { id -> values[id]?.let { id to it } }.toMap()
    }

    override fun evict(widgetId: String) {
        values.remove(widgetId)
        dirty.remove(widgetId)
    }
}
