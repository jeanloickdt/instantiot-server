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

// auth/UserRepository.kt
package com.jeanloickdt.auth.domain

interface UserRepository {
    /**
     * Create a new user in the users table.
     *
     * @param passwordChanged whether the supplied password is the user's own
     *   chosen one (`true`, the default — e.g. self-registration) or a
     *   server-assigned default that must be changed (`false` — the bootstrap
     *   `admin/admin`). Drives the `passwordChanged` field returned at login.
     */
    fun create(
        username: String,
        pwdHash: String,
        role: String = "user",
        passwordChanged: Boolean = true
    ): String

    fun findByUsername(username: String): UserRow?
    fun findById(id: String): UserRow?
    fun findAll(): List<UserRow>

    /**
     * @param passwordChanged `true` (default) when the user sets their own
     *   password; `false` when the server assigns a default (e.g. the
     *   reset-admin recovery flow resetting back to `admin`).
     */
    fun updatePassword(id: String, newHash: String, passwordChanged: Boolean = true)

    /**
     * Partial update: if an argument is `null`, the corresponding field
     * stays unchanged. No-op if both are null. Used by the V1
     * first-launch welcome (Renew action) which may modify one and/or
     * the other.
     */
    fun updateCredentials(id: String, newUsername: String?, newPwdHash: String?)

    fun count(): Long
}