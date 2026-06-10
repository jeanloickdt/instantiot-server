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

package com.jeanloickdt.auth.domain

data class UserRow(
    val id: String,
    val username: String,
    val pwdHash: String,
    val role: String,
    /**
     * `false` while the account still uses a server-assigned default
     * password (the bootstrap `admin/admin`, or an admin reset). Flipped to
     * `true` the moment the owner sets their own password. Drives the
     * `passwordChanged` field returned at login.
     */
    val passwordChanged: Boolean,
    /** Revocation counter — a token whose `ver` claim is below this is rejected. */
    val tokenVersion: Int,
    val createdAt: Long
)