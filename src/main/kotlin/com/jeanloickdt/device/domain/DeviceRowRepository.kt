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

package com.jeanloickdt.device.domain

// device/domain/DeviceRepository.kt

interface DeviceRepository {
    fun create(
        name: String,
        projectId: String,
        ownerId: String,
        tokenHash: String,
        deviceType: DeviceType,
        connectivity: DeviceConnectivity
    ): String
    fun findById(id: String): DeviceRow?
    fun findByTokenHash(tokenHash: String): DeviceRow?
    fun findAll(): List<DeviceRow>
    fun findAllByOwner(ownerId: String): List<DeviceRow>
    fun findAllByProject(projectId: String): List<DeviceRow>
    fun updateOnlineStatus(id: String, isOnline: Boolean)
    /**
     * Bulk reset: marks all devices `isOnline = false`.
     *
     * Called at server startup to clean up stale states after an abrupt
     * kill (Ctrl+C that skips the `finally` of `handleDevice`).
     * Without this, the DB may keep `isOnline=true` while no TCP session
     * is active → the app shows phantom "online" devices.
     */
    fun markAllOffline()
    fun updateLastSeen(id: String, timestamp: Long)
    /** Renames a device. The active TCP session stays open. */
    fun updateName(id: String, newName: String)
    fun delete(id: String): Boolean
    fun deleteAllByProject(projectId: String)
    fun renewToken(id: String, newTokenHash: String)
    fun count(): Long
    fun countOnline(): Long

}