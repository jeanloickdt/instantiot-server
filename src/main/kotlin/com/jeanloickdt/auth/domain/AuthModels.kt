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

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val role: String,
    /**
     * `false` while the account still uses a server-assigned default password
     * (the bootstrap `admin/admin`). The admin panel already consumes this exact
     * field (`app.js`: `this.passwordChanged = data.passwordChanged !== false`)
     * and routes a `false` login straight to the forced change-password
     * ("setup") screen. Defaults to `true` (= setup done) so a missing field
     * never forces a change spuriously and stays backward-compatible.
     */
    val passwordChanged: Boolean = true
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

@Serializable
data class AdminStatsResponse(
    val users: Long,
    val projects: Long,
    val devicesTotal: Long,
    val devicesOnline: Int,
    val appSessionsActive: Int,
    val deviceSessionsActive: Int
)

@Serializable
data class ServerInfoResponse(
    val version: String,
    val httpPort: Int,
    val tcpPort: Int,
    val uptimeMs: Long,
    val dbSizeBytes: Long,
    val javaVersion: String,
    val osName: String,
    val localIp: String,
    val serverDisplayName: String,         // configured value (empty = auto)
    val effectiveDisplayName: String       // value actually used by mDNS
)

@Serializable
data class UpdateConfigRequest(
    val httpPort: Int? = null,
    val tcpPort: Int? = null,
    val serverDisplayName: String? = null
)

@Serializable
data class UpdateConfigResponse(
    val message: String,
    val httpPort: Int,
    val tcpPort: Int,
    val serverDisplayName: String
)

// ============================================================
// HISTORY — config exposed in the admin panel
// ============================================================

@Serializable
data class HistoryConfigResponse(
    /**
     * Enables the RAW tier (perfect fidelity, off by default).
     * When off, the curves use the aggregated tiers (min/hour/day).
     */
    val rawEnabled: Boolean,
    val retentionRawDays: Int,
    val retentionOpaqueDays: Int,
    val retentionMinDays: Int,
    val retentionHourDays: Int,
    val retentionDayDays: Int     // -1 = unlimited
)

@Serializable
data class UpdateHistoryConfigRequest(
    val rawEnabled: Boolean? = null,
    val retentionRawDays: Int? = null,
    val retentionOpaqueDays: Int? = null,
    val retentionMinDays: Int? = null,
    val retentionHourDays: Int? = null,
    val retentionDayDays: Int? = null
)
// ============================================================
// BACKUP — admin panel V1
// ============================================================

@Serializable
data class BackupConfigResponse(
    val enabled: Boolean,
    val intervalHours: Int,
    val retentionCount: Int,
    val lastBackupAtMs: Long,        // 0 if never
    val backupCount: Int,            // number of backups currently present
    val backupDirPath: String        // for info in the UI
)

@Serializable
data class UpdateBackupConfigRequest(
    val enabled: Boolean? = null,
    val intervalHours: Int? = null,
    val retentionCount: Int? = null
)

@Serializable
data class RegistrationConfigResponse(
    val open: Boolean
)

@Serializable
data class UpdateRegistrationConfigRequest(
    val open: Boolean
)

@Serializable
data class BackupListEntry(
    val filename: String,
    val sizeBytes: Long,
    val createdAtMs: Long,
    val createdAtFormatted: String
)

@Serializable
data class BackupListResponse(
    val backups: List<BackupListEntry>
)

@Serializable
data class RestoreBackupRequest(
    val filename: String
)

@Serializable
data class RestoreBackupResponse(
    val message: String              // staged; applied (with a safety net) at next boot
)

// ============================================================
// USERS — admin panel V1
// ============================================================

@Serializable
data class AdminUserEntry(
    val id: String,
    val username: String,
    val role: String,
    val createdAtMs: Long
)

/**
 * Admin-side account creation — the email-less replacement for an invite
 * link. The password here is PROVISIONAL: the account is created with
 * `passwordChanged=false`, so the first login forces its owner onto a
 * password the admin never knew. Same mechanic as the bootstrap admin/admin.
 */
@Serializable
data class AdminCreateUserRequest(
    val username: String,
    val password: String,
    val role: String = "user"
)

@Serializable
data class AdminUserListResponse(
    val users: List<AdminUserEntry>
)

@Serializable
data class ResetUserPasswordRequest(
    val newPassword: String
)