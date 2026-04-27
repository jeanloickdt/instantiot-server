package com.jeanloickdt.auth

import com.jeanloickdt.auth.domain.*
import com.jeanloickdt.common.ServerConfig
import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.device.domain.DeviceResponse
import com.jeanloickdt.project.domain.ProjectRepository
import com.jeanloickdt.relay.SessionRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.mindrot.jbcrypt.BCrypt

// ============================================================
// Validation — port disponible
// ============================================================
private fun isPortAvailable(port: Int): Boolean {
    return try {
        // bind explicite sur 0.0.0.0 avec SO_REUSEADDR désactivé
        val socket = java.net.ServerSocket()
        socket.reuseAddress = false
        socket.bind(java.net.InetSocketAddress("0.0.0.0", port))
        socket.close()
        // double check sur 127.0.0.1 (macOS peut avoir des binds séparés)
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
// Validation — contraintes username et password
// ============================================================
private val USERNAME_REGEX = Regex("^[a-zA-Z0-9_]{3,32}$")
private const val PASSWORD_MIN_LENGTH = 8
private const val PASSWORD_MAX_LENGTH = 128

// ============================================================
// 🔓 LOGIN — toujours accessible, même sans licence
// Retourne le token + role pour le dashboard
// ============================================================
fun Route.loginRoute(userRepository: UserRepository) {
    post("/api/login") {
        val body = call.receive<LoginRequest>()

        val user = userRepository.findByUsername(body.username)
        if (user == null || !BCrypt.checkpw(body.password, user.pwdHash)) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
            return@post
        }

        val token = JwtConfig.generateToken(user.id)
        call.respond(AuthResponse(
            token = token,
            role  = user.role
        ))
    }
}

// ============================================================
// 📝 REGISTER — bloqué si licence invalide
// ============================================================
fun Route.registerRoute(userRepository: UserRepository) {
    post("/api/register") {
        // licence requise pour créer un compte
        if (!LicenceValidator.isActivated()) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Licence required"))
            return@post
        }

        val body = call.receive<RegisterRequest>()

        // validation username — 3-32 caractères alphanumériques + underscore
        if (!USERNAME_REGEX.matches(body.username)) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Username must be 3-32 characters, alphanumeric and underscores only"
            ))
            return@post
        }

        // validation password — 8-128 caractères
        if (body.password.length < PASSWORD_MIN_LENGTH || body.password.length > PASSWORD_MAX_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Password must be between $PASSWORD_MIN_LENGTH and $PASSWORD_MAX_LENGTH characters"
            ))
            return@post
        }

        if (userRepository.findByUsername(body.username) != null) {
            call.respond(HttpStatusCode.Conflict, "Username already exists")
            return@post
        }

        val hash   = BCrypt.hashpw(body.password, BCrypt.gensalt())
        val userId = userRepository.create(
            username = body.username,
            pwdHash = hash
        )
        val user   = userRepository.findById(userId)!!
        val token  = JwtConfig.generateToken(userId)

        call.respond(HttpStatusCode.Created, AuthResponse(
            token = token,
            role  = user.role
        ))
    }
}

// ============================================================
// 🔑 CHANGE PASSWORD — tout user authentifié
// ============================================================
fun Route.changePasswordRoute(userRepository: UserRepository) {
    patch("/api/users/me/password") {
        val userId = call.principal<JWTPrincipal>()?.subject
            ?: return@patch call.respond(HttpStatusCode.Unauthorized)

        val user = userRepository.findById(userId)
            ?: return@patch call.respond(HttpStatusCode.Unauthorized)

        val body = call.receive<ChangePasswordRequest>()

        // vérifier l'ancien password
        if (!BCrypt.checkpw(body.currentPassword, user.pwdHash)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid current password"))
            return@patch
        }

        // valider le nouveau password
        if (body.newPassword.length < PASSWORD_MIN_LENGTH || body.newPassword.length > PASSWORD_MAX_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Password must be between $PASSWORD_MIN_LENGTH and $PASSWORD_MAX_LENGTH characters"
            ))
            return@patch
        }

        val newHash = BCrypt.hashpw(body.newPassword, BCrypt.gensalt())
        userRepository.updatePassword(userId, newHash)

        call.respond(HttpStatusCode.OK, mapOf("message" to "Password updated"))
    }
}

// ============================================================
// 📊 ADMIN STATS — admin only
// ============================================================
fun Route.adminStatsRoute(
    userRepository: UserRepository,
    projectRepository: ProjectRepository,
    deviceRepository: DeviceRepository
) {
    get("/api/admin/stats") {
        val userId = call.principal<JWTPrincipal>()?.subject
            ?: return@get call.respond(HttpStatusCode.Unauthorized)

        val user = userRepository.findById(userId)
        if (user == null || user.role != "admin") {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Admin only"))
            return@get
        }

        call.respond(AdminStatsResponse(
            users                = userRepository.count(),
            projects             = projectRepository.count(),
            devicesTotal         = deviceRepository.count(),
            devicesOnline        = SessionRegistry.deviceSessions.size,
            appSessionsActive    = SessionRegistry.appSessions.size,
            deviceSessionsActive = SessionRegistry.deviceSessions.size
        ))
    }
}

// ============================================================
// 📋 ADMIN DEVICES — admin only — tous les devices de tous les users
// ============================================================
fun Route.adminDevicesRoute(
    userRepository: UserRepository,
    deviceRepository: DeviceRepository
) {
    get("/api/admin/devices") {
        val userId = call.principal<JWTPrincipal>()?.subject
            ?: return@get call.respond(HttpStatusCode.Unauthorized)

        val user = userRepository.findById(userId)
        if (user == null || user.role != "admin") {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Admin only"))
            return@get
        }

        // retourner TOUS les devices — pas filtré par owner
        val devices = deviceRepository.findAll().map {
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
        val userId = call.principal<JWTPrincipal>()?.subject
            ?: return@get call.respond(HttpStatusCode.Unauthorized)

        val user = userRepository.findById(userId)
        if (user == null || user.role != "admin") {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Admin only"))
            return@get
        }

        call.respond(ServerInfoResponse(
            version     = ServerConfig.version,
            httpPort    = ServerConfig.httpPort,
            tcpPort     = ServerConfig.tcpPort,
            uptimeMs    = ServerConfig.uptimeMs,
            dbSizeBytes = ServerConfig.dbSizeBytes,
            javaVersion = System.getProperty("java.version") ?: "unknown",
            osName      = System.getProperty("os.name") ?: "unknown",
            localIp     = ServerConfig.localIp
        ))
    }
}

// ============================================================
// ⚙️ ADMIN CONFIG — admin only — modifier les ports
// Nécessite un redémarrage du serveur pour appliquer
// ============================================================
fun Route.adminConfigRoute(userRepository: UserRepository) {
    patch("/api/admin/config") {
        val userId = call.principal<JWTPrincipal>()?.subject
            ?: return@patch call.respond(HttpStatusCode.Unauthorized)

        val user = userRepository.findById(userId)
        if (user == null || user.role != "admin") {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Admin only"))
            return@patch
        }

        val body = call.receive<UpdateConfigRequest>()

        // validation des ports
        val httpPort = body.httpPort
        val tcpPort = body.tcpPort

        if (httpPort != null && (httpPort < 1 || httpPort > 65535)) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "HTTP port must be between 1 and 65535"))
            return@patch
        }
        if (tcpPort != null && (tcpPort < 1 || tcpPort > 65535)) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "TCP port must be between 1 and 65535"))
            return@patch
        }
        if (httpPort != null && tcpPort != null && httpPort == tcpPort) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "HTTP and TCP ports must be different"))
            return@patch
        }

        // vérifier que les ports sont disponibles (pas utilisés par un autre service)
        // on skip le check pour les ports actuellement utilisés par CE serveur
        if (httpPort != null && httpPort != ServerConfig.runningHttpPort && httpPort != ServerConfig.runningTcpPort && !isPortAvailable(httpPort)) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "HTTP port $httpPort is already in use"))
            return@patch
        }
        if (tcpPort != null && tcpPort != ServerConfig.runningHttpPort && tcpPort != ServerConfig.runningTcpPort && !isPortAvailable(tcpPort)) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "TCP port $tcpPort is already in use"))
            return@patch
        }

        ServerConfig.save(newHttpPort = httpPort, newTcpPort = tcpPort)

        call.respond(HttpStatusCode.OK, UpdateConfigResponse(
            message  = "Configuration saved — restart server to apply",
            httpPort = ServerConfig.httpPort,
            tcpPort  = ServerConfig.tcpPort
        ))
    }
}

// ============================================================
// 🗄️ ADMIN HISTORY CONFIG — admin only — paramètres rétention / downsample
// Pas de redémarrage requis : les valeurs sont relues à chaque cycle.
// ============================================================
fun Route.adminHistoryConfigRoute(userRepository: UserRepository) {

    // garde commune admin-only
    suspend fun checkAdmin(call: io.ktor.server.application.ApplicationCall): Boolean {
        val userId = call.principal<JWTPrincipal>()?.subject
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return false
        }
        val user = userRepository.findById(userId)
        if (user == null || user.role != "admin") {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Admin only"))
            return false
        }
        return true
    }

    get("/api/admin/history-config") {
        if (!checkAdmin(call)) return@get
        call.respond(HttpStatusCode.OK, HistoryConfigResponse(
            retentionRawDays          = ServerConfig.historyRetentionRawDays,
            retentionOpaqueDays       = ServerConfig.historyRetentionOpaqueDays,
            throttleRawIntervalSeconds = ServerConfig.historyThrottleRawIntervalMs / 1000L,
            retentionMinDays          = ServerConfig.historyRetentionMinDays,
            retentionHourDays         = ServerConfig.historyRetentionHourDays,
            retentionDayDays          = ServerConfig.historyRetentionDayDays,
            downsampleIntervalMinutes = ServerConfig.historyDownsampleIntervalMinutes
        ))
    }

    patch("/api/admin/history-config") {
        if (!checkAdmin(call)) return@patch

        val body = call.receive<UpdateHistoryConfigRequest>()

        // validations — bornes positives (sauf day qui accepte -1 = infini)
        body.retentionRawDays?.let {
            if (it < 1) return@patch call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to "retentionRawDays must be >= 1"))
        }
        body.retentionOpaqueDays?.let {
            if (it < 1) return@patch call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to "retentionOpaqueDays must be >= 1"))
        }
        body.throttleRawIntervalSeconds?.let {
            if (it < 0) return@patch call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to "throttleRawIntervalSeconds must be >= 0"))
        }
        body.retentionMinDays?.let {
            if (it < 1) return@patch call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to "retentionMinDays must be >= 1"))
        }
        body.retentionHourDays?.let {
            if (it < 1) return@patch call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to "retentionHourDays must be >= 1"))
        }
        body.retentionDayDays?.let {
            // -1 = infini, sinon >= 1
            if (it != -1 && it < 1) return@patch call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to "retentionDayDays must be >= 1 or -1 for unlimited"))
        }
        body.downsampleIntervalMinutes?.let {
            if (it < 1) return@patch call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to "downsampleIntervalMinutes must be >= 1"))
        }

        ServerConfig.saveHistoryConfig(
            retentionRawDays          = body.retentionRawDays,
            retentionOpaqueDays       = body.retentionOpaqueDays,
            throttleRawIntervalSeconds = body.throttleRawIntervalSeconds,
            retentionMinDays          = body.retentionMinDays,
            retentionHourDays         = body.retentionHourDays,
            retentionDayDays          = body.retentionDayDays,
            downsampleIntervalMinutes = body.downsampleIntervalMinutes
        )

        call.respond(HttpStatusCode.OK, HistoryConfigResponse(
            retentionRawDays          = ServerConfig.historyRetentionRawDays,
            retentionOpaqueDays       = ServerConfig.historyRetentionOpaqueDays,
            throttleRawIntervalSeconds = ServerConfig.historyThrottleRawIntervalMs / 1000L,
            retentionMinDays          = ServerConfig.historyRetentionMinDays,
            retentionHourDays         = ServerConfig.historyRetentionHourDays,
            retentionDayDays          = ServerConfig.historyRetentionDayDays,
            downsampleIntervalMinutes = ServerConfig.historyDownsampleIntervalMinutes
        ))
    }
}

// ============================================================
// 🔄 ADMIN RESTART — admin only — arrêt propre du serveur
// Le process manager (systemd, jpackage, etc.) le redémarrera
// ============================================================
fun Route.adminRestartRoute(userRepository: UserRepository) {
    post("/api/admin/restart") {
        val userId = call.principal<JWTPrincipal>()?.subject
            ?: return@post call.respond(HttpStatusCode.Unauthorized)

        val user = userRepository.findById(userId)
        if (user == null || user.role != "admin") {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Admin only"))
            return@post
        }

        call.respond(HttpStatusCode.OK, mapOf("message" to "Server shutting down..."))

        // arrêt propre après avoir envoyé la réponse
        // le process manager (systemd, etc.) redémarrera le serveur
        Thread {
            Thread.sleep(500)
            Runtime.getRuntime().exit(0)
        }.start()
    }
}

// ============================================================
// 🔑 ADMIN LICENCE — activer une licence JWT signée
// Pas besoin d'être authentifié — la licence est nécessaire AVANT le login
// ============================================================
fun Route.licenceRoute() {
    // GET — état de la licence courante
    get("/api/licence") {
        val info = LicenceValidator.getLicenceInfo()
        if (info != null && LicenceValidator.isActivated()) {
            call.respond(HttpStatusCode.OK, LicenceResponse(
                id        = info.id,
                plan      = info.plan,
                expiresAt = info.expiresAt
            ))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "No valid licence"))
        }
    }

    // POST — activer une licence
    post("/api/licence") {
        val body = call.receive<LicenceRequest>()

        val info = LicenceValidator.activate(body.key)
        if (info != null) {
            call.respond(HttpStatusCode.OK, LicenceResponse(
                id        = info.id,
                plan      = info.plan,
                expiresAt = info.expiresAt
            ))
        } else {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid licence key"))
        }
    }
}

// ============================================================
// 🔐 AUTH ROUTES COMPLÈTES — login + register
// Rate limited par IP — 10 requêtes / minute
// ============================================================
fun Route.authRoutes(
    userRepository: UserRepository,
    projectRepository: ProjectRepository,
    deviceRepository: DeviceRepository
) {
    licenceRoute()

    rateLimit(RateLimitName("auth")) {
        loginRoute(userRepository)
        registerRoute(userRepository)
    }

    authenticate("jwt") {
        changePasswordRoute(userRepository)
        adminStatsRoute(userRepository, projectRepository, deviceRepository)
        adminDevicesRoute(userRepository, deviceRepository)
        adminServerInfoRoute(userRepository)
        adminConfigRoute(userRepository)
        adminHistoryConfigRoute(userRepository)
        adminRestartRoute(userRepository)
    }
}
