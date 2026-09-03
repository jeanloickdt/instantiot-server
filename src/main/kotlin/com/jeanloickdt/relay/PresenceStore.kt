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

// relay/PresenceStore.kt
package com.jeanloickdt.relay

import com.jeanloickdt.device.domain.DeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Device presence (online/offline + last-seen) — the seam that a future
 * multi-node deployment swaps for a shared implementation.
 *
 * Deliberately mono-node: no `whereIs(deviceId): NodeId` here — that method
 * presupposes a node topology we have NOT designed. It will be ADDED
 * (Open/Closed) together with the message bus when multi-node lands.
 */
interface PresenceStore {
    suspend fun markOnline(deviceId: String, at: Long)
    suspend fun markOffline(deviceId: String)
    fun isOnline(deviceId: String): Boolean
    fun lastSeenAt(deviceId: String): Long?
}

/**
 * Mono-node implementation: tracks presence in RAM and mirrors it into the
 * `devices` table (is_online / last_seen) exactly as the relay did inline
 * before — the REST device lists keep reading the DB unchanged.
 */
class DbBackedPresenceStore(
    /**
     * Le contrat de PRESENCE, pas le depot entier.
     *
     * Ces ecritures ne portent pas de proprietaire — elles sont internes au
     * relais, pour une carte qu'il vient d'authentifier par son jeton. Dependre
     * du contrat etroit rend cette absence visible ici, au lieu de la laisser
     * passer pour un oubli de la regle.
     */
    private val deviceRepository: com.jeanloickdt.device.domain.DevicePresenceWriter
) : PresenceStore {

    private val online = ConcurrentHashMap.newKeySet<String>()
    private val lastSeen = ConcurrentHashMap<String, Long>()

    /**
     * Boards whose presence has changed in RAM and not yet reached the table.
     *
     * The reason this exists is one realistic incident, not a benchmark: a
     * regional carrier hiccups, three thousand boards drop and come back inside
     * the same minute, and the old code turned that into **six thousand
     * synchronous writes** — while the five-second flush was writing too. The
     * network event became a database event.
     *
     * Presence was already held in RAM; what was missing was the patience. Now
     * the truth is in memory the instant it changes, and the table catches up
     * on the next round.
     */
    private val dirty = ConcurrentHashMap<String, Boolean>()

    override suspend fun markOnline(deviceId: String, at: Long) {
        online.add(deviceId)
        lastSeen[deviceId] = at
        dirty[deviceId] = true
    }

    override suspend fun markOffline(deviceId: String) {
        online.remove(deviceId)
        dirty[deviceId] = false
    }

    /**
     * Writes the pending changes, and returns how many.
     *
     * Drained before writing so a board that reconnects mid-flush is recorded
     * on the next round rather than lost with a cleared key. A reconnection
     * storm therefore costs one round of writes, not two per board.
     */
    suspend fun flushPending(): Int = withContext(com.jeanloickdt.common.ServerDispatchers.storage) {
        if (dirty.isEmpty()) return@withContext 0
        val batch = dirty.keys.toList().mapNotNull { id -> dirty.remove(id)?.let { id to it } }
        batch.forEach { (deviceId, isOnline) ->
            deviceRepository.updateOnlineStatus(deviceId, isOnline)
            if (isOnline) lastSeen[deviceId]?.let { deviceRepository.updateLastSeen(deviceId, it) }
        }
        batch.size
    }

    /** How many presence changes are waiting — for the flush loop's log line. */
    fun pendingCount(): Int = dirty.size

    override fun isOnline(deviceId: String): Boolean = deviceId in online

    override fun lastSeenAt(deviceId: String): Long? = lastSeen[deviceId]
}
