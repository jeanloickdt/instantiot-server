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
    val role: String
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
    val localIp: String
)

@Serializable
data class UpdateConfigRequest(
    val httpPort: Int? = null,
    val tcpPort: Int? = null
)

@Serializable
data class LicenceRequest(
    val key: String
)

@Serializable
data class LicenceResponse(
    val id: String,
    /**
     * 0 = lifetime (claim `exp` absent du JWT). Sinon timestamp ms epoch.
     */
    val expiresAt: Long,
    /**
     * Token JWT auto-login généré uniquement quand l'activation
     * a aussi déclenché le bootstrap admin (V1 first-launch flow).
     * Null sinon (re-activation, simple GET) — l'user passera par
     * /api/login normalement.
     */
    val token: String? = null
)

/**
 * Body de POST /api/setup/forgot-password.
 * L'user présente sa clé licence pour prouver qu'il est bien le
 * propriétaire du serveur. Si la clé matche celle activée, on reset
 * le password de l'user "admin" à `licence.id` (le default original).
 */
@Serializable
data class ForgotPasswordRequest(
    val licenceKey: String
)

/**
 * Body de POST /api/setup/welcome (V1 first-launch flow).
 *
 *   action = "renew" : update credentials admin (au moins un de
 *                      `username`/`password` doit être fourni)
 *   action = "skip"  : conserve les credentials par défaut
 *                      (admin / licence.id)
 *
 * Dans les deux cas, le marker `~/.instantiot/setup.done` est créé
 * → welcome ne réapparaîtra plus.
 */
@Serializable
data class WelcomeRequest(
    val action: String,
    val username: String? = null,
    val password: String? = null
)

@Serializable
data class UpdateConfigResponse(
    val message: String,
    val httpPort: Int,
    val tcpPort: Int
)

// ============================================================
// HISTORIQUE — config exposée dans le panel admin
// ============================================================

@Serializable
data class HistoryConfigResponse(
    val retentionRawDays: Int,
    val retentionOpaqueDays: Int,
    val throttleRawIntervalSeconds: Long,
    val retentionMinDays: Int,
    val retentionHourDays: Int,
    val retentionDayDays: Int,     // -1 = infini
    val downsampleIntervalMinutes: Int
)

@Serializable
data class UpdateHistoryConfigRequest(
    val retentionRawDays: Int? = null,
    val retentionOpaqueDays: Int? = null,
    val throttleRawIntervalSeconds: Long? = null,
    val retentionMinDays: Int? = null,
    val retentionHourDays: Int? = null,
    val retentionDayDays: Int? = null,
    val downsampleIntervalMinutes: Int? = null
)
// ============================================================
// BACKUP — admin panel V1
// ============================================================

@Serializable
data class BackupConfigResponse(
    val enabled: Boolean,
    val intervalHours: Int,
    val retentionCount: Int,
    val lastBackupAtMs: Long,        // 0 si jamais
    val backupCount: Int,            // nombre de backups actuellement présents
    val backupDirPath: String        // pour info dans l'UI
)

@Serializable
data class UpdateBackupConfigRequest(
    val enabled: Boolean? = null,
    val intervalHours: Int? = null,
    val retentionCount: Int? = null
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
    val message: String,             // ex: "Restart required to load restored DB"
    val safetyNetFilename: String    // ancien DB renommé
)
