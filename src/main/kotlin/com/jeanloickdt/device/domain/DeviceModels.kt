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

// device/domain/DeviceModels.kt
package com.jeanloickdt.device.domain

import kotlinx.serialization.Serializable

@Serializable
data class CreateDeviceRequest(
    val name: String,
    val projectId: String,
    /**
     * Name of the [DeviceType] enum (e.g. `"ESP32"`, `"ARDUINO_UNO_R4_WIFI"`).
     * Validated by the route — 400 rejection if value is unknown.
     */
    val deviceType: String,
    /**
     * Name of the [DeviceConnectivity] enum (e.g. `"WIFI"`, `"ETHERNET"`).
     * Validated by the route — 400 rejection if value is unknown OR if the
     * `(deviceType, connectivity)` pair is not in [DEVICE_CONNECTIVITY_MAP].
     */
    val connectivity: String
)

@Serializable
data class DeviceResponse(
    val id: String,
    val name: String,
    val projectId: String,
    val isOnline: Boolean,
    val lastSeen: Long?,
    val deviceType: String? = null,
    val connectivity: String? = null
)

@Serializable
data class CreateDeviceResponse(
    val id: String,
    val name: String,
    val projectId: String,
    val token: String,  // shown only once — never stored in plaintext
    val deviceType: String,
    val connectivity: String
)

@Serializable
data class UpdateDeviceNameRequest(
    val name: String
)