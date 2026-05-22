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

// device/domain/DeviceRow.kt
package com.jeanloickdt.device.domain

data class DeviceRow(
    val id: String,
    val projectId: String,
    val ownerId: String,
    val name: String,
    val tokenHash: String,
    val lastSeen: Long?,
    val isOnline: Boolean,
    /**
     * Hardware type — nullable for devices predating the addition of
     * this column. New devices require a non-null type.
     */
    val deviceType: DeviceType?,
    /**
     * Physical connectivity mode. Nullable for the same reason.
     * Must be in [DEVICE_CONNECTIVITY_MAP]\[deviceType\] at
     * creation time to be accepted.
     */
    val connectivity: DeviceConnectivity?
)