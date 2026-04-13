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
import com.jeanloickdt.relay.SessionRegistry
import com.jeanloickdt.relay.configureAppRelay
import com.jeanloickdt.relay.startDeviceRelay
import com.jeanloickdt.widget.data.SqliteWidgetHistoryRepository
import com.jeanloickdt.widget.data.SqliteWidgetRepository
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import com.jeanloickdt.widget.domain.WidgetHistoryRepository
import com.jeanloickdt.widget.domain.WidgetHistoryRow
import com.jeanloickdt.widget.domain.WidgetRepository
import com.jeanloickdt.widget.widgetRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory

fun main(args: Array<String>) = EngineMain.main(args)

// ============================================================
// Dépendances globales — instanciées une seule fois au démarrage
// ============================================================
val userRepository: UserRepository               = SqliteUserRepository()
val projectRepository: ProjectRepository         = SqliteProjectRepository()
val deviceRepository: DeviceRepository           = SqliteDeviceRepository()
val widgetRepository: WidgetRepository           = SqliteWidgetRepository()
val widgetHistoryRepository: WidgetHistoryRepository = SqliteWidgetHistoryRepository()

fun Application.module() {

    // ============================================================
    // Plugins globaux
    // ============================================================
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText("500: $cause", status = HttpStatusCode.InternalServerError)
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
        WidgetHistoryTable
    )

    // flush final au shutdown — aucune donnée du buffer perdue
    monitor.subscribe(ApplicationStopping) {
        kotlinx.coroutines.runBlocking {
            flushHistoryBuffer(widgetHistoryRepository)
        }
    }

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
    startDeviceRelay(deviceRepository, widgetRepository, tcpPort = 9001)

    // ============================================================
    // Flush history buffer → SQLite WAL batch toutes les 5s
    // La DB n'est jamais dans le chemin critique du relay
    // ============================================================
    launch(Dispatchers.IO) {
        while (true) {
            delay(5_000)
            flushHistoryBuffer(widgetHistoryRepository)
        }
    }

    // ============================================================
    // Relay app — WebSocket /ws/app
    // ============================================================
    configureAppRelay()

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

        authRoutes(userRepository)
        projectRoutes(projectRepository)
        deviceRoutes(deviceRepository)
        widgetRoutes(widgetRepository, widgetHistoryRepository)

        // TODO: ajouter les routes des nouveaux modules ici
    }
}

// ============================================================
// Génération password admin — 12 caractères alphanumériques
// ============================================================
private fun generateAdminPassword(): String {
    val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return (1..12).map { chars.random() }.joinToString("")
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