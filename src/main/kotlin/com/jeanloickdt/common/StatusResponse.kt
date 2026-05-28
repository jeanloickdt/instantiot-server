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

// common/Models.kt
package com.jeanloickdt.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response of `GET /api/status` — exposed without authentication.
 *
 * V1.3: no more licensing system. The server always starts ready
 * (admin account created automatically on first boot). [setupState]
 * is therefore always `"ready"` — field kept for backward compat
 * with the admin panel that routes on it.
 */
@Serializable
data class StatusResponse(
    val status: String,
    @SerialName("setup_state")
    val setupState: String,
    // Legacy — kept for compat with the old admin panel.
    val setup_required: Boolean,
    /**
     * TCP relay port (effective `runningTcpPort`) where ESP devices
     * must connect. Exposed publicly here so the mobile app can fetch
     * it before generating an Arduino sketch — without requiring admin
     * role (the admin-only `/api/admin/server-info` returns it too,
     * but that endpoint is gated on role=admin so non-admin users
     * couldn't generate code with the correct port).
     *
     * Optional in the response for forward compat with older clients,
     * but always set by current servers (v1.1.3+).
     */
    val tcpPort: Int? = null
)