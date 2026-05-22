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

// device/data/DeviceTable.kt
package com.jeanloickdt.device.data

import org.jetbrains.exposed.sql.Table

object DeviceTable : Table("devices") {
    val id            = text("id")
    val projectId     = text("project_id")
    val ownerId       = text("owner_id")
    val name          = text("name")
    val tokenHash     = text("token_hash")  // SHA-256 of the token
    val lastSeen      = long("last_seen").nullable()
    val isOnline      = bool("is_online").default(false)
    /**
     * Hardware type of the device (e.g. `ESP32`, `ARDUINO_UNO_R4_WIFI`).
     * Stored as String = name of the `DeviceType` enum.
     * Nullable for backward compat with devices created before this feature
     * — new devices reject null via route validation.
     */
    val deviceType    = text("device_type").nullable()
    /**
     * Physical connectivity mode (`WIFI` or `ETHERNET`).
     * Stored as String = name of the `DeviceConnectivity` enum.
     * Nullable for the same backward-compat reason.
     */
    val connectivity  = text("connectivity").nullable()
    override val primaryKey = PrimaryKey(id)
}