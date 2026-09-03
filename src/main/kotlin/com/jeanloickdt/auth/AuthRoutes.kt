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

package com.jeanloickdt.auth

import com.jeanloickdt.common.ApiError

import com.jeanloickdt.auth.domain.*
import com.jeanloickdt.common.ServerConfig
import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.device.domain.DeviceResponse
import com.jeanloickdt.project.domain.ProjectRepository
import com.jeanloickdt.relay.ConnectionRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory

// ============================================================
// Validation — port availability
// ============================================================
private fun isPortAvailable(port: Int): Boolean {
    return try {
        // explicit bind on 0.0.0.0 with SO_REUSEADDR disabled
        val socket = java.net.ServerSocket()
        socket.reuseAddress = false
        socket.bind(java.net.InetSocketAddress("0.0.0.0", port))
        socket.close()
        // double check on 127.0.0.1 (macOS can have separate binds)
        val socket2 = java.net.ServerSocket()
        socket2.reuseAddress = false
        socket2.bind(java.net.InetSocketAddress("127.0.0.1", port))
        socket2.close()
        true
    } catch (_: Exception) {
        false
    }
}

// ============================================================
// Validation — username and password constraints
// ============================================================
private val USERNAME_REGEX = Regex("^[a-zA-Z0-9_]{3,32}$")
private const val PASSWORD_MIN_LENGTH = 8
private const val PASSWORD_MAX_LENGTH = 128

// Constant-time login: when the username is unknown we still run one bcrypt
// verification against this fixed hash, so a request for a non-existent user
// takes the same time as one for an existing user. Closes username enumeration
// via response-timing. Computed once at class load.
private val DUMMY_BCRYPT_HASH: String = BCrypt.hashpw("dummy-password", BCrypt.gensalt())

// ============================================================
// 🔓 LOGIN — always accessible, even without a license
// Returns the token + role for the dashboard
// ============================================================
fun Route.loginRoute(userRepository: UserRepository, tokenService: TokenService) {
    post("/api/login") {
        val body = call.receive<LoginRequest>()

        val user = userRepository.findByUsername(body.username)
        // Always run exactly one bcrypt check (against a dummy hash when the
        // user is unknown) so the response time does not reveal whether the
        // username exists.
        val passwordOk =
            if (user == null) { BCrypt.checkpw(body.password, DUMMY_BCRYPT_HASH); false }
            else BCrypt.checkpw(body.password, user.pwdHash)

        if (user == null || !passwordOk) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid credentials"))
            return@post
        }

        val token = tokenService.issue(user.id, user.tokenVersion)
        call.respond(AuthResponse(
            token = token,
            role  = user.role,
            // the admin panel forces its change-password ("setup") screen when
            // this is false (still on the bootstrap admin/admin default)
            passwordChanged = user.passwordChanged
        ))
    }
}

// ============================================================
// 📝 REGISTER — controlled by the registrationOpen flag
// ============================================================
fun Route.registerRoute(userRepository: UserRepository, tokenService: TokenService) {
    post("/api/register") {
        // 🔒 Registration controlled by the admin. Multi-user supported, but
        // NO open registration by default: otherwise anyone on the
        // LAN (the app discovers the server via mDNS) could self-register.
        // The admin opens registration from the panel while onboarding
        // their users, then closes it. Gitea/Vaultwarden SIGNUPS_ALLOWED pattern.
        // The admin itself is created at server boot (separate path) →
        // this gate never blocks the first account.
        if (!ServerConfig.registrationOpen) {
            call.respond(
                HttpStatusCode.Forbidden,
                ApiError("Registration is closed")
            )
            return@post
        }

        val body = call.receive<RegisterRequest>()

        // username validation — 3-32 alphanumeric characters + underscore
        if (!USERNAME_REGEX.matches(body.username)) {
            call.respond(HttpStatusCode.BadRequest, ApiError(
                "Username must be 3-32 characters, alphanumeric and underscores only"
            ))
            return@post
        }

        // password validation — 8-128 characters
        if (body.password.length < PASSWORD_MIN_LENGTH || body.password.length > PASSWORD_MAX_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, ApiError(
                "Password must be between $PASSWORD_MIN_LENGTH and $PASSWORD_MAX_LENGTH characters"
            ))
            return@post
        }

        if (userRepository.findByUsername(body.username) != null) {
            call.respond(HttpStatusCode.Conflict, ApiError("Username already exists"))
            return@post
        }

        val hash   = BCrypt.hashpw(body.password, BCrypt.gensalt())
        val userId = userRepository.create(
            username = body.username,
            pwdHash = hash
        )
        val user   = userRepository.findById(userId)!!
        val token  = tokenService.issue(userId, user.tokenVersion)

        call.respond(HttpStatusCode.Created, AuthResponse(
            token = token,
            role  = user.role
        ))
    }
}

// ============================================================
// 🔑 CHANGE PASSWORD — any authenticated user
// ============================================================
fun Route.changePasswordRoute(userRepository: UserRepository, tokenService: TokenService) {
    patch("/api/users/me/password") {
        val userId = call.principal<JWTPrincipal>()?.subject
            ?: return@patch call.respond(HttpStatusCode.Unauthorized)

        val user = userRepository.findById(userId)
            ?: return@patch call.respond(HttpStatusCode.Unauthorized)

        val body = call.receive<ChangePasswordRequest>()

        // verify the old password
        if (!BCrypt.checkpw(body.currentPassword, user.pwdHash)) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid current password"))
            return@patch
        }

        // validate the new password
        if (body.newPassword.length < PASSWORD_MIN_LENGTH || body.newPassword.length > PASSWORD_MAX_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, ApiError(
                "Password must be between $PASSWORD_MIN_LENGTH and $PASSWORD_MAX_LENGTH characters"
            ))
            return@patch
        }

        val newHash = BCrypt.hashpw(body.newPassword, BCrypt.gensalt())
        // updatePassword bumps token_version → revokes ALL prior tokens
        // (other devices logged out). We then re-issue a fresh token for THIS
        // session so the caller stays logged in (decision: re-issue, not
        // force-relogin). The client (admin panel app.js / mobile app) MUST
        // swap to the returned token, otherwise its next request is 401.
        userRepository.updatePassword(userId, newHash)
        val updated = userRepository.findById(userId)!!
        val newToken = tokenService.issue(userId, updated.tokenVersion)

        call.respond(HttpStatusCode.OK, AuthResponse(
            token = newToken,
            role  = updated.role,
            passwordChanged = updated.passwordChanged
        ))
    }
}

// ============================================================
// 📊 ADMIN STATS — admin only
// ============================================================
/**
 * Centralized admin guard. Responds 401 if unauthenticated, 403 ("Admin only")
 * if the caller is not an admin, and returns null in both cases so a handler can
 * bail with `?: return@get`. Replaces the per-route inline checks and the
 * duplicated local `checkAdmin` helpers (same behaviour, single definition).
 */
internal suspend fun io.ktor.server.application.ApplicationCall.requireAdmin(
    userRepository: UserRepository
): com.jeanloickdt.auth.domain.UserRow? {
    val userId = principal<JWTPrincipal>()?.subject
    if (userId == null) {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    val user = userRepository.findById(userId)
    if (user == null || user.role != "admin") {
        respond(HttpStatusCode.Forbidden, ApiError("Admin only"))
        return null
    }
    return user
}

fun Route.adminStatsRoute(
    userRepository: UserRepository,
    projectRepository: ProjectRepository,
    deviceRepository: DeviceRepository,
    connections: ConnectionRegistry
) {
    get("/api/admin/stats") {
        call.requireAdmin(userRepository) ?: return@get

        call.respond(AdminStatsResponse(
            users                = userRepository.count(),
            projects             = projectRepository.countAll(),
            devicesTotal         = deviceRepository.countAll(),
            devicesOnline        = connections.deviceSessions.size,
            appSessionsActive    = connections.appSessions.size,
            deviceSessionsActive = connections.deviceSessions.size
        ))
    }
}

// ============================================================
// 📋 ADMIN DEVICES — admin only — all devices of all users
// ============================================================
fun Route.adminDevicesRoute(
    userRepository: UserRepository,
    deviceRepository: DeviceRepository
) {
    get("/api/admin/devices") {
        call.requireAdmin(userRepository) ?: return@get

        // return ALL devices — not filtered by owner
        val devices = deviceRepository.findAllForAdmin().map {
            DeviceResponse(
                id        = it.id,
                name      = it.name,
                projectId = it.projectId,
                isOnline  = it.isOnline,
                lastSeen  = it.lastSeen
            )
        }

        call.respond(HttpStatusCode.OK, devices)
    }
}

// ============================================================
// 🖥️ ADMIN SERVER INFO — admin only
// ============================================================
fun Route.adminServerInfoRoute(userRepository: UserRepository) {
    get("/api/admin/server-info") {
        call.requireAdmin(userRepository) ?: return@get

        val configured = ServerConfig.serverDisplayName
        val effective = configured.takeIf { it.isNotBlank() }
            ?: System.getenv("HOSTNAME")
            ?: System.getenv("COMPUTERNAME")
            ?: "InstantIoT Server"
        call.respond(ServerInfoResponse(
            version     = ServerConfig.version,
            // Effective ports — what the engine actually bound at startup
            // after PortFinder resolved any conflict. The admin panel and
            // mDNS publication must agree, so we read the running values
            // (not the configured ones, which may differ if 8080/9001
            // were already taken on this machine).
            httpPort    = ServerConfig.runningHttpPort,
            tcpPort     = ServerConfig.runningTcpPort,
            uptimeMs    = ServerConfig.uptimeMs,
            dbSizeBytes = ServerConfig.dbSizeBytes,
            javaVersion = System.getProperty("java.version") ?: "unknown",
            osName      = System.getProperty("os.name") ?: "unknown",
            localIp     = ServerConfig.localIp,
            serverDisplayName = configured,
            effectiveDisplayName = effective
        ))
    }
}

// ============================================================
// ⚙️ ADMIN CONFIG — admin only — modify the ports
// Requires a server restart to apply
// ============================================================
fun Route.adminConfigRoute(userRepository: UserRepository) {
    patch("/api/admin/config") {
        call.requireAdmin(userRepository) ?: return@patch

        val body = call.receive<UpdateConfigRequest>()

        // port validation
        val httpPort = body.httpPort
        val tcpPort = body.tcpPort

        if (httpPort != null && (httpPort < 1 || httpPort > 65535)) {
            call.respond(HttpStatusCode.BadRequest, ApiError("HTTP port must be between 1 and 65535"))
            return@patch
        }
        if (tcpPort != null && (tcpPort < 1 || tcpPort > 65535)) {
            call.respond(HttpStatusCode.BadRequest, ApiError("TCP port must be between 1 and 65535"))
            return@patch
        }
        if (httpPort != null && tcpPort != null && httpPort == tcpPort) {
            call.respond(HttpStatusCode.BadRequest, ApiError("HTTP and TCP ports must be different"))
            return@patch
        }

        // verify the ports are available (not used by another service)
        // we skip the check for the ports currently used by THIS server
        if (httpPort != null && httpPort != ServerConfig.runningHttpPort && httpPort != ServerConfig.runningTcpPort && !isPortAvailable(httpPort)) {
            call.respond(HttpStatusCode.Conflict, ApiError("HTTP port $httpPort is already in use"))
            return@patch
        }
        if (tcpPort != null && tcpPort != ServerConfig.runningHttpPort && tcpPort != ServerConfig.runningTcpPort && !isPortAvailable(tcpPort)) {
            call.respond(HttpStatusCode.Conflict, ApiError("TCP port $tcpPort is already in use"))
            return@patch
        }

        // Server display name validation: 1-64 chars, no control chars,
        // no @/. at the start (mDNS friendly)
        val displayName = body.serverDisplayName?.trim()
        if (displayName != null && displayName.isNotEmpty()) {
            if (displayName.length > 64) {
                return@patch call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("serverDisplayName must be <= 64 characters")
                )
            }
            // Reject control chars
            if (displayName.any { it.code < 32 }) {
                return@patch call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("serverDisplayName cannot contain control characters")
                )
            }
        }

        ServerConfig.save(
            newHttpPort = httpPort,
            newTcpPort = tcpPort,
            newServerDisplayName = displayName
        )

        call.respond(HttpStatusCode.OK, UpdateConfigResponse(
            message  = "Configuration saved — restart server to apply",
            httpPort = ServerConfig.httpPort,
            tcpPort  = ServerConfig.tcpPort,
            serverDisplayName = ServerConfig.serverDisplayName
        ))
    }
}

// ============================================================
// 🗄️ ADMIN HISTORY CONFIG — admin only — retention / downsample params
// No restart required: the values are re-read on each cycle.
// ============================================================
fun Route.adminHistoryConfigRoute(userRepository: UserRepository) {

    get("/api/admin/history-config") {
        call.requireAdmin(userRepository) ?: return@get
        call.respond(HttpStatusCode.OK, currentHistoryConfig())
    }

    patch("/api/admin/history-config") {
        call.requireAdmin(userRepository) ?: return@patch

        val body = call.receive<UpdateHistoryConfigRequest>()

        // validations — positive bounds (except day which accepts -1 = unlimited)
        body.retentionRawDays?.let {
            if (it < 1) return@patch call.respond(HttpStatusCode.BadRequest,
                ApiError("retentionRawDays must be >= 1"))
        }
        body.retentionOpaqueDays?.let {
            if (it < 1) return@patch call.respond(HttpStatusCode.BadRequest,
                ApiError("retentionOpaqueDays must be >= 1"))
        }
        body.retentionMinDays?.let {
            if (it < 1) return@patch call.respond(HttpStatusCode.BadRequest,
                ApiError("retentionMinDays must be >= 1"))
        }
        body.retentionHourDays?.let {
            if (it < 1) return@patch call.respond(HttpStatusCode.BadRequest,
                ApiError("retentionHourDays must be >= 1"))
        }
        body.retentionDayDays?.let {
            // -1 = unlimited, otherwise >= 1
            if (it != -1 && it < 1) return@patch call.respond(HttpStatusCode.BadRequest,
                ApiError("retentionDayDays must be >= 1 or -1 for unlimited"))
        }

        ServerConfig.saveHistoryConfig(
            rawEnabled          = body.rawEnabled,
            retentionRawDays    = body.retentionRawDays,
            retentionOpaqueDays = body.retentionOpaqueDays,
            retentionMinDays    = body.retentionMinDays,
            retentionHourDays   = body.retentionHourDays,
            retentionDayDays    = body.retentionDayDays
        )

        call.respond(HttpStatusCode.OK, currentHistoryConfig())
    }
}

/** Snapshot DTO of the current history config. */
private fun currentHistoryConfig(): HistoryConfigResponse = HistoryConfigResponse(
    rawEnabled          = ServerConfig.historyRawEnabled,
    retentionRawDays    = ServerConfig.historyRetentionRawDays,
    retentionOpaqueDays = ServerConfig.historyRetentionOpaqueDays,
    retentionMinDays    = ServerConfig.historyRetentionMinDays,
    retentionHourDays   = ServerConfig.historyRetentionHourDays,
    retentionDayDays    = ServerConfig.historyRetentionDayDays
)

// ============================================================
// 👥 ADMIN USERS — read-only list + reset password (V1 Phase 4)
// The admin can see all accounts on the server and reset the
// password of any of them (their spouse/partner who forgot, etc.).
// No email in V1 — the admin communicates the new password to
// the user out-of-band (SMS, IRL, etc.).
// ============================================================
fun Route.adminUsersRoute(userRepository: UserRepository, purge: AccountPurge) {

    // ── List (read-only) ──────────────────────────────────
    get("/api/admin/users") {
        call.requireAdmin(userRepository) ?: return@get
        val list = userRepository.findAll().map { row ->
            AdminUserEntry(
                id          = row.id,
                username    = row.username,
                role        = row.role,
                createdAtMs = row.createdAt
            )
        }
        call.respond(HttpStatusCode.OK, AdminUserListResponse(list))
    }

    // ── Create a user (by the admin) ───────────────────────
    // The email-less edition of account management: registration can stay
    // CLOSED (secure-by-default) and the admin provisions accounts directly.
    // The password given here is provisional — passwordChanged=false makes
    // the first login force a change, so the admin never durably knows a
    // user's password. Exactly the bootstrap admin/admin mechanic, reused.
    post("/api/admin/users") {
        call.requireAdmin(userRepository) ?: return@post
        val body = call.receive<AdminCreateUserRequest>()

        if (!USERNAME_REGEX.matches(body.username)) {
            return@post call.respond(HttpStatusCode.BadRequest,
                ApiError("Username must be 3-32 chars (letters, digits, underscore)"))
        }
        if (body.password.length < PASSWORD_MIN_LENGTH || body.password.length > PASSWORD_MAX_LENGTH) {
            return@post call.respond(HttpStatusCode.BadRequest,
                ApiError("Password must be $PASSWORD_MIN_LENGTH-$PASSWORD_MAX_LENGTH characters"))
        }
        if (body.role != "user" && body.role != "admin") {
            return@post call.respond(HttpStatusCode.BadRequest,
                ApiError("Role must be 'user' or 'admin'"))
        }
        if (userRepository.findByUsername(body.username) != null) {
            return@post call.respond(HttpStatusCode.Conflict,
                ApiError("Username already taken"))
        }

        val id = userRepository.create(
            username        = body.username,
            pwdHash         = BCrypt.hashpw(body.password, BCrypt.gensalt()),
            role            = body.role,
            // provisional password → forced change at first login
            passwordChanged = false
        )
        call.respond(HttpStatusCode.Created, AdminUserEntry(
            id          = id,
            username    = body.username,
            role        = body.role,
            createdAtMs = System.currentTimeMillis()
        ))
    }

    // ── Delete MY account — everything, forever ────────────
    // The guard: the LAST admin cannot delete itself. On self-host that
    // would orphan the panel until a restart recreates admin/admin — a
    // footgun, not a freedom. Any other account: gone, with every project,
    // device, widget and history row it owned.
    delete("/api/users/me") {
        val userId = call.principal<JWTPrincipal>()?.subject
            ?: return@delete call.respond(HttpStatusCode.Unauthorized)
        val user = userRepository.findById(userId)
            ?: return@delete call.respond(HttpStatusCode.Unauthorized)

        if (user.role == "admin" && userRepository.countAdmins() <= 1) {
            return@delete call.respond(
                HttpStatusCode.Conflict,
                ApiError("Cannot delete the last admin account")
            )
        }

        val report = purge.purge(userId)
        call.respond(HttpStatusCode.OK, report)
    }

    // ── Delete a user (by the admin) ───────────────────────
    // Not oneself: self-deletion goes through /api/users/me so that
    // destroying your own account is always an explicit, distinct act —
    // never a slip of the finger in a user list.
    delete("/api/admin/users/{id}") {
        val admin = call.requireAdmin(userRepository) ?: return@delete
        val targetId = call.parameters["id"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("Missing user id"))

        if (targetId == admin.id) {
            return@delete call.respond(
                HttpStatusCode.BadRequest,
                ApiError("Use DELETE /api/users/me to delete your own account")
            )
        }
        val target = userRepository.findById(targetId)
            ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("User not found"))
        if (target.role == "admin" && userRepository.countAdmins() <= 1) {
            return@delete call.respond(
                HttpStatusCode.Conflict,
                ApiError("Cannot delete the last admin account")
            )
        }

        val report = purge.purge(targetId)
        call.respond(HttpStatusCode.OK, report)
    }

    // ── Reset a user's password (by the admin) ─────────────
    // The user stays logged in with their old JWT token (no
    // session revoke in V1). On the next logout/expiry, they will
    // have to use the new password.
    post("/api/admin/users/{id}/reset-password") {
        call.requireAdmin(userRepository) ?: return@post
        val targetId = call.parameters["id"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Missing user id"))

        val target = userRepository.findById(targetId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("User not found"))

        val body = call.receive<ResetUserPasswordRequest>()
        val newPassword = body.newPassword
        if (newPassword.length < PASSWORD_MIN_LENGTH || newPassword.length > PASSWORD_MAX_LENGTH) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiError("Password must be $PASSWORD_MIN_LENGTH-$PASSWORD_MAX_LENGTH characters")
            )
        }

        val newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
        userRepository.updatePassword(targetId, newHash)
        LoggerFactory.getLogger("InstantIoT").info(
            "Admin reset password for user '{}' (id prefix={})",
            target.username, targetId.take(8)
        )
        call.respond(HttpStatusCode.OK)
    }

    // ── Registration toggle (GET / PATCH) ──────────────────
    // Controls public registration. Closed by default. The admin opens it
    // while onboarding their users then closes it. Hot-reload.
    get("/api/admin/registration/config") {
        call.requireAdmin(userRepository) ?: return@get
        call.respond(
            HttpStatusCode.OK,
            RegistrationConfigResponse(open = ServerConfig.registrationOpen)
        )
    }

    patch("/api/admin/registration/config") {
        call.requireAdmin(userRepository) ?: return@patch
        val body = call.receive<UpdateRegistrationConfigRequest>()
        ServerConfig.saveRegistrationConfig(body.open)
        LoggerFactory.getLogger("InstantIoT").info(
            "Admin set registration open={}", body.open
        )
        call.respond(
            HttpStatusCode.OK,
            RegistrationConfigResponse(open = ServerConfig.registrationOpen)
        )
    }
}

// ============================================================
// 💾 ADMIN BACKUP — admin only — SQLite snapshots (V1 Phase 4)
// ============================================================
fun Route.adminBackupRoute(userRepository: UserRepository) {

    fun configResponse() = BackupConfigResponse(
        enabled         = ServerConfig.backupEnabled,
        intervalHours   = ServerConfig.backupIntervalHours,
        retentionCount  = ServerConfig.backupRetentionCount,
        lastBackupAtMs  = com.jeanloickdt.backup.BackupManager.lastBackupAtMs,
        backupCount     = com.jeanloickdt.backup.BackupManager.list().size,
        backupDirPath   = ServerConfig.backupDir.absolutePath
    )

    // ── Config (GET / PATCH) ───────────────────────────────
    get("/api/admin/backup/config") {
        call.requireAdmin(userRepository) ?: return@get
        call.respond(HttpStatusCode.OK, configResponse())
    }

    patch("/api/admin/backup/config") {
        call.requireAdmin(userRepository) ?: return@patch

        val body = call.receive<UpdateBackupConfigRequest>()
        body.intervalHours?.let {
            if (it < 1) return@patch call.respond(
                HttpStatusCode.BadRequest,
                ApiError("intervalHours must be >= 1")
            )
        }
        body.retentionCount?.let {
            if (it < 1) return@patch call.respond(
                HttpStatusCode.BadRequest,
                ApiError("retentionCount must be >= 1")
            )
        }

        ServerConfig.saveBackupConfig(
            enabled        = body.enabled,
            intervalHours  = body.intervalHours,
            retentionCount = body.retentionCount
        )
        call.respond(HttpStatusCode.OK, configResponse())
    }

    // ── Manual snapshot ────────────────────────────────────
    post("/api/admin/backup/now") {
        call.requireAdmin(userRepository) ?: return@post
        val file = com.jeanloickdt.backup.BackupManager.snapshotNow()
        if (file == null) {
            return@post call.respond(
                HttpStatusCode.InternalServerError,
                ApiError("Backup failed — see server logs")
            )
        }
        com.jeanloickdt.backup.BackupManager.cleanup()
        call.respond(HttpStatusCode.OK, configResponse())
    }

    // ── List ───────────────────────────────────────────────
    get("/api/admin/backup/list") {
        call.requireAdmin(userRepository) ?: return@get
        val backups = com.jeanloickdt.backup.BackupManager.list().map { info ->
            BackupListEntry(
                filename           = info.filename,
                sizeBytes          = info.sizeBytes,
                createdAtMs        = info.createdAt,
                createdAtFormatted = info.createdAtFormatted
            )
        }
        call.respond(HttpStatusCode.OK, BackupListResponse(backups))
    }

    // ── Restore (staged, applied at next boot) ─────────────
    post("/api/admin/backup/restore") {
        call.requireAdmin(userRepository) ?: return@post
        val body = call.receive<RestoreBackupRequest>()
        if (body.filename.isBlank()) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiError("filename required")
            )
        }
        // Staged, not swapped live: the actual replacement + WAL-complete safety
        // net happen at the next boot, before the connection pool opens.
        val staged = com.jeanloickdt.backup.BackupManager.stageRestore(body.filename)
        if (staged == null) {
            return@post call.respond(
                HttpStatusCode.NotFound,
                ApiError("Backup not found or failed the integrity check — see server logs")
            )
        }
        call.respond(HttpStatusCode.OK, RestoreBackupResponse(
            message = "Restore staged — restart the server to apply it. " +
                "The current database is saved as a safety net during the restart."
        ))
    }
}

// ============================================================
// 🔄 ADMIN RESTART — admin only — clean server shutdown
// The process manager (systemd, jpackage, etc.) will restart it
// ============================================================
fun Route.adminRestartRoute(userRepository: UserRepository) {
    post("/api/admin/restart") {
        call.requireAdmin(userRepository) ?: return@post

        call.respond(HttpStatusCode.OK, mapOf("message" to "Server shutting down..."))

        // clean shutdown after sending the response
        // the process manager (systemd, etc.) will restart the server
        Thread {
            Thread.sleep(500)
            Runtime.getRuntime().exit(0)
        }.start()
    }
}

// ============================================================
// 🔐 COMPLETE AUTH ROUTES — login + register
// Rate limited by IP — 10 requests / minute
// ============================================================
fun Route.authRoutes(
    userRepository: UserRepository,
    projectRepository: ProjectRepository,
    deviceRepository: DeviceRepository,
    connections: ConnectionRegistry,
    tokenService: TokenService,
    purge: AccountPurge
) {
    rateLimit(RateLimitName("auth")) {
        loginRoute(userRepository, tokenService)
        registerRoute(userRepository, tokenService)
    }

    authenticate("jwt") {
        changePasswordRoute(userRepository, tokenService)
        adminStatsRoute(userRepository, projectRepository, deviceRepository, connections)
        adminDevicesRoute(userRepository, deviceRepository)
        adminServerInfoRoute(userRepository)
        adminConfigRoute(userRepository)
        adminHistoryConfigRoute(userRepository)
        adminBackupRoute(userRepository)
        adminUsersRoute(userRepository, purge)
        adminRestartRoute(userRepository)
    }
}