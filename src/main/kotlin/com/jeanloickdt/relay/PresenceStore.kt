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
    private val deviceRepository: DeviceRepository
) : PresenceStore {

    private val online = ConcurrentHashMap.newKeySet<String>()
    private val lastSeen = ConcurrentHashMap<String, Long>()

    override suspend fun markOnline(deviceId: String, at: Long) {
        online.add(deviceId)
        lastSeen[deviceId] = at
        withContext(Dispatchers.IO) {
            deviceRepository.updateOnlineStatus(deviceId, isOnline = true)
            deviceRepository.updateLastSeen(deviceId, at)
        }
    }

    override suspend fun markOffline(deviceId: String) {
        online.remove(deviceId)
        withContext(Dispatchers.IO) {
            deviceRepository.updateOnlineStatus(deviceId, isOnline = false)
        }
    }

    override fun isOnline(deviceId: String): Boolean = deviceId in online

    override fun lastSeenAt(deviceId: String): Long? = lastSeen[deviceId]
}
