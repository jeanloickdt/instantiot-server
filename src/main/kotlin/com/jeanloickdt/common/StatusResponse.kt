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
    val setup_required: Boolean
)