package com.jeanloickdt

import com.jeanloickdt.auth.LicenceValidator
import com.jeanloickdt.auth.authRoutes
import com.jeanloickdt.auth.configureAuth
import com.jeanloickdt.auth.data.SqliteUserRepository
import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.auth.domain.UserRepository
import com.jeanloickdt.common.StatusResponse
import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.device.data.SqliteDeviceRepository
import com.jeanloickdt.device.deviceRoutes
import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.project.data.SqliteProjectRepository
import com.jeanloickdt.project.domain.ProjectRepository
import com.jeanloickdt.project.projectRoutes
import com.jeanloickdt.relay.HistoryEntry
import com.jeanloickdt.relay.NumericHistoryEntry
import com.jeanloickdt.relay.SessionRegistry
import com.jeanloickdt.relay.configureAppRelay
import com.jeanloickdt.relay.startDeviceRelay
import com.jeanloickdt.widget.data.SqliteWidgetHistoryNumericRepository
import com.jeanloickdt.widget.data.SqliteWidgetHistoryRepository
import com.jeanloickdt.widget.data.SqliteWidgetRepository
import com.jeanloickdt.widget.data.WidgetHistoryNumericTable
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import com.jeanloickdt.widget.domain.WidgetHistoryNumericRepository
import com.jeanloickdt.widget.domain.WidgetHistoryNumericRow
import com.jeanloickdt.widget.domain.WidgetHistoryRepository
import com.jeanloickdt.widget.domain.WidgetHistoryRow
import com.jeanloickdt.widget.domain.WidgetRepository
import com.jeanloickdt.widget.widgetRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

fun main(args: Array<String>) {
    val startupLogger = LoggerFactory.getLogger("InstantIoT")

    // charger la config AVANT de démarrer le serveur
    com.jeanloickdt.common.ServerConfig.load()

    val httpPort = com.jeanloickdt.common.ServerConfig.httpPort
    val tcpPort = com.jeanloickdt.common.ServerConfig.tcpPort

    startupLogger.info("Starting InstantIoT Server v${com.jeanloickdt.common.ServerConfig.version}")
    startupLogger.info("HTTP port: $httpPort | TCP port: $tcpPort")

    try {
        // system tray — icone dans la barre des taches (desktop)
        // si pas supporte (serveur headless) → mode console silencieux
        val trayActive = com.jeanloickdt.common.SystemTrayManager.init(httpPort)
        if (!trayActive) {
            startupLogger.info("Open http://localhost:$httpPort in your browser")
        }

        embeddedServer(
            Netty,
            port = httpPort,
            module = Application::module
        ).start(wait = true)
    } catch (e: Exception) {
        when {
            e.message?.contains("Address already in use") == true ||
            e.cause?.message?.contains("Address already in use") == true -> {
                startupLogger.error("========================================")
                startupLogger.error("FAILED TO START — Port $httpPort is already in use")
                startupLogger.error("")
                startupLogger.error("To fix, edit the port in:")
                startupLogger.error("  ~/.instantiot/server.properties")
                startupLogger.error("")
                startupLogger.error("  http.port=8081    (or any free port)")
                startupLogger.error("  tcp.port=$tcpPort")
                startupLogger.error("")
                startupLogger.error("Then restart the server.")
                startupLogger.error("========================================")
            }
            else -> {
                startupLogger.error("FAILED TO START", e)
            }
        }
        System.exit(1)
    }
}

// ============================================================
// Dépendances globales — instanciées une seule fois au démarrage
// ============================================================
val userRepository: UserRepository               = SqliteUserRepository()
val projectRepository: ProjectRepository         = SqliteProjectRepository()
val deviceRepository: DeviceRepository           = SqliteDeviceRepository()
val widgetRepository: WidgetRepository           = SqliteWidgetRepository()
val widgetHistoryRepository: WidgetHistoryRepository = SqliteWidgetHistoryRepository()
val widgetHistoryNumericRepository: WidgetHistoryNumericRepository = SqliteWidgetHistoryNumericRepository()

private val logger = LoggerFactory.getLogger("Application")

fun Application.module() {

    // ============================================================
    // Plugins globaux
    // ============================================================
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception on ${call.request.local.uri}", cause)
            call.respondText("500: Internal Server Error", status = HttpStatusCode.InternalServerError)
        }
    }

    install(CORS) {
        anyHost()  // permissif pour la beta — le client restreint via reverse proxy
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
    }

    install(RateLimit) {
        register(RateLimitName("auth")) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.local.remoteAddress
            }
        }
    }

    // ============================================================
    // Base de données — init SQLite + WAL + création des tables
    // Ajouter les tables des nouveaux modules ici au fur et à mesure
    // ============================================================
    DatabaseFactory.init(
        UserTable,
        ProjectTable,
        DeviceTable,
        WidgetTable,
        WidgetHistoryTable,
        WidgetHistoryNumericTable
    )

    // Reset stale online state : si le server a été kill abruptement
    // (Ctrl+C qui skip le `finally` de `handleDevice`), la DB peut
    // garder `isOnline=true` pour des devices qui n'ont plus de session
    // TCP active. Au démarrage, aucune session n'existe → tout doit
    // être offline, les devices passeront online dès qu'ils se
    // reconnecteront et enverront leur handshake.
    deviceRepository.markAllOffline()

    // flush final au shutdown — aucune donnée du buffer perdue
    monitor.subscribe(ApplicationStopping) {
        kotlinx.coroutines.runBlocking {
            flushHistoryBuffer(widgetHistoryRepository)
            flushNumericHistoryBuffer(widgetHistoryNumericRepository)
        }
    }

    // ============================================================
    // Licence — charger depuis ~/.instantiot/licence.key
    // ============================================================
    LicenceValidator.load()

    // ============================================================
    // Premier lancement — création compte admin automatique
    // Password affiché dans les logs une seule fois
    // ============================================================
    if (userRepository.count() == 0L) {
        val adminPassword = generateAdminPassword()
        val adminPasswordHash = BCrypt.hashpw(adminPassword, BCrypt.gensalt())
        userRepository.create("admin", adminPasswordHash, role = "admin")

        val logger = LoggerFactory.getLogger("InstantIoT")
        logger.info("========================================")
        logger.info("InstantIoT Server — First Launch")
        logger.info("Admin account created")
        logger.info("Username : admin")
        logger.info("Password : $adminPassword")
        logger.info("SAVE THIS — will not be shown again")
        logger.info("========================================")
    }

    // ============================================================
    // Authentification JWT — doit être configuré avant les routes
    // ============================================================
    configureAuth(userRepository)

    // ============================================================
    // Relay devices — TCP port 9001
    // Chaque connexion ESP dans sa propre coroutine IO
    // ============================================================
    startDeviceRelay(deviceRepository, widgetRepository, tcpPort = com.jeanloickdt.common.ServerConfig.tcpPort)

    // ============================================================
    // Flush history buffer → SQLite WAL batch toutes les 5s
    // La DB n'est jamais dans le chemin critique du relay
    // ============================================================
    launch(Dispatchers.IO) {
        while (true) {
            delay(5_000)
            flushHistoryBuffer(widgetHistoryRepository)
            flushNumericHistoryBuffer(widgetHistoryNumericRepository)
        }
    }

    // cleanup history — toutes les heures
    launch(Dispatchers.IO) {
        while (true) {
            delay(1.hours)
            val now = System.currentTimeMillis()

            // opaque (widget_history) — fenêtre courte, streams + discrete events
            val opaqueCutoff = now - com.jeanloickdt.common.ServerConfig.historyRetentionOpaqueDays.toLong() * 24L * 3600_000L
            widgetHistoryRepository.deleteOlderThan(opaqueCutoff)

            // numérique (widget_history_numeric) — fenêtre raw Phase 1
            val numericCutoff = now - com.jeanloickdt.common.ServerConfig.historyRetentionRawDays.toLong() * 24L * 3600_000L
            widgetHistoryNumericRepository.deleteOlderThan(numericCutoff)
        }
    }

    // ============================================================
    // Relay app — WebSocket /ws/app
    // ============================================================
    configureAppRelay(projectRepository)

    // ============================================================
    // Routes REST
    // ============================================================
    routing {
        staticResources("/", "static")

        // GET /api/status — état du server — toujours accessible
        get("/api/status") {
            call.respond(StatusResponse(
                status           = "ok",
                setup_required   = userRepository.count() == 0L,
                licence_required = !LicenceValidator.isActivated()
            ))
        }

        authRoutes(userRepository, projectRepository, deviceRepository)
        projectRoutes(projectRepository, deviceRepository, widgetRepository, widgetHistoryRepository, widgetHistoryNumericRepository)
        deviceRoutes(deviceRepository)
        widgetRoutes(widgetRepository, widgetHistoryRepository, widgetHistoryNumericRepository)

        // TODO: ajouter les routes des nouveaux modules ici
    }
}

// ============================================================
// Génération password admin — 16 caractères alphanumériques
// SecureRandom pour génération cryptographiquement sûre
// ============================================================
private fun generateAdminPassword(): String {
    val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val random = java.security.SecureRandom()
    return (1..16).map { chars[random.nextInt(chars.length)] }.joinToString("")
}

// ============================================================
// Flush history buffer → SQLite WAL batch insert
// Appelé toutes les 5s + au shutdown
// ============================================================
private suspend fun flushHistoryBuffer(widgetHistoryRepository: WidgetHistoryRepository) {
    val batch = mutableListOf<HistoryEntry>()
    while (SessionRegistry.historyBuffer.isNotEmpty()) {
        batch.add(SessionRegistry.historyBuffer.poll() ?: break)
    }

    if (batch.isEmpty()) return

    val historyRows = batch.map { entry ->
        WidgetHistoryRow(
            id         = 0,              // auto-increment — ignoré à l'insert
            widgetId   = entry.widgetId,
            projectId  = entry.projectId,
            ownerId    = entry.ownerId,
            payload    = entry.payload,
            recordedAt = entry.recordedAt
        )
    }

    widgetHistoryRepository.insertBatch(historyRows)
}

// ============================================================
// Flush numeric history buffer → SQLite WAL batch insert
// ============================================================
private suspend fun flushNumericHistoryBuffer(repo: WidgetHistoryNumericRepository) {
    val batch = mutableListOf<NumericHistoryEntry>()
    while (SessionRegistry.numericHistoryBuffer.isNotEmpty()) {
        batch.add(SessionRegistry.numericHistoryBuffer.poll() ?: break)
    }

    if (batch.isEmpty()) return

    val rows = batch.map { entry ->
        WidgetHistoryNumericRow(
            id         = 0,
            widgetId   = entry.widgetId,
            projectId  = entry.projectId,
            ownerId    = entry.ownerId,
            seriesId   = entry.seriesId,
            value      = entry.value,
            recordedAt = entry.recordedAt
        )
    }

    repo.insertBatch(rows)
}