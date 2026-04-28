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
import com.jeanloickdt.widget.data.HistoryAggregator
import com.jeanloickdt.widget.data.SqliteWidgetHistoryAggregateRepository
import com.jeanloickdt.widget.data.SqliteWidgetHistoryNumericRepository
import com.jeanloickdt.widget.data.SqliteWidgetHistoryRepository
import com.jeanloickdt.widget.data.SqliteWidgetRepository
import com.jeanloickdt.widget.data.WidgetHistoryDayTable
import com.jeanloickdt.widget.data.WidgetHistoryHourTable
import com.jeanloickdt.widget.data.WidgetHistoryMinTable
import com.jeanloickdt.widget.data.WidgetHistoryNumericTable
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import com.jeanloickdt.widget.domain.WidgetHistoryAggregateRepository
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

    // Auto-bind ports : si le port préféré est occupé (Tomcat,
    // Jenkins, autre InstantIoT en parallèle), on essaie +1, +2, ...
    // jusqu'à +5. Évite "ne marche pas, j'ai pas idée pourquoi" pour
    // le maker qui clique sur l'app fraîchement installée.
    val httpPort = try {
        com.jeanloickdt.common.PortFinder.findAvailable(
            com.jeanloickdt.common.ServerConfig.httpPort,
            label = "HTTP port"
        )
    } catch (e: IllegalStateException) {
        startupLogger.error(e.message)
        System.exit(1); return
    }
    val tcpPort = try {
        com.jeanloickdt.common.PortFinder.findAvailable(
            com.jeanloickdt.common.ServerConfig.tcpPort,
            label = "TCP port"
        )
    } catch (e: IllegalStateException) {
        startupLogger.error(e.message)
        System.exit(1); return
    }
    // Synchronise les running ports — mDNS, tray, /api/status liront
    // ces valeurs (pas la config désirée) pour annoncer la bonne URL.
    com.jeanloickdt.common.ServerConfig.markRunningPorts(http = httpPort, tcp = tcpPort)

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
        // Avec le PortFinder ci-dessus, on ne devrait plus voir ce cas
        // (sauf race rare). Garde le fallback log pour info.
        startupLogger.error("FAILED TO START", e)
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
val widgetHistoryMinRepository: WidgetHistoryAggregateRepository  = SqliteWidgetHistoryAggregateRepository(WidgetHistoryMinTable)
val widgetHistoryHourRepository: WidgetHistoryAggregateRepository = SqliteWidgetHistoryAggregateRepository(WidgetHistoryHourTable)
val widgetHistoryDayRepository: WidgetHistoryAggregateRepository  = SqliteWidgetHistoryAggregateRepository(WidgetHistoryDayTable)

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
        WidgetHistoryNumericTable,
        WidgetHistoryMinTable,
        WidgetHistoryHourTable,
        WidgetHistoryDayTable
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

    // mDNS / Bonjour — désinscrit le service avant que les sockets ferment
    // pour que les apps voient le serveur disparaître proprement.
    monitor.subscribe(ApplicationStopping) {
        com.jeanloickdt.discovery.MdnsPublisher.stop()
    }

    // ============================================================
    // Licence — charger depuis ~/.instantiot/licence.key
    // ============================================================
    LicenceValidator.load()

    // ============================================================
    // Bootstrap admin — V1 first-launch flow
    //
    // PLUS de création d'admin avec password random au boot.
    // Le bootstrap est maintenant déclenché par POST /api/licence
    // (cf. licenceRoute) avec password = licence.id, ce qui :
    //   - aligne les credentials par défaut sur la valeur que l'user
    //     reçoit dans son email d'achat (mémorisable, pas un secret
    //     random qui scrolle dans les logs)
    //   - évite d'avoir un admin "orphelin" sur un serveur sans
    //     licence (impossible de se connecter de toute façon : login
    //     sans licence dirige vers /setup côté front)
    //
    // SAUF cas recovery : licence.key restauré depuis backup mais DB
    // vide (perte SQLite, restore partiel). Là on recrée silencieusement
    // l'admin avec licence.id pour que l'user puisse re-login. Sans
    // ça il serait coincé sur /login sans pouvoir s'authentifier (et
    // le setup screen ne s'afficherait pas puisque licence active).
    //
    // generateAdminPassword() reste défini plus bas mais inutilisé —
    // gardé temporairement comme référence, à supprimer dans un
    // commit ultérieur quand le flow V1 sera complètement validé.
    // ============================================================
    val licenceInfo = LicenceValidator.getLicenceInfo()
    if (licenceInfo != null && LicenceValidator.isActivated() &&
        userRepository.findByUsername("admin") == null) {
        val pwdHash = BCrypt.hashpw(licenceInfo.id, BCrypt.gensalt())
        userRepository.create("admin", pwdHash, role = "admin")
        LoggerFactory.getLogger("InstantIoT").warn(
            "Recovery bootstrap: licence valid but admin missing — " +
                "re-created admin from licence (id prefix={})",
            licenceInfo.id.take(8)
        )
    }

    // ============================================================
    // Setup state — log au boot pour visibilité opérationnelle
    // (V1 first-launch flow). Le service combine licence + DB +
    // marker file et auto-heal si marker absent mais admin existe.
    // Sera utilisé par GET /api/status pour rediriger le browser
    // vers /setup, /welcome ou /login selon l'état.
    // ============================================================
    // SetupStateStore partagé entre le service (lecture) et le route
    // /api/setup/welcome (écriture via markComplete). Une seule instance
    // = pas de race / divergence d'état.
    val setupStateStore = com.jeanloickdt.auth.SetupStateStore()
    val setupStateService = com.jeanloickdt.auth.SetupStateService(
        userRepository  = userRepository,
        setupStateStore = setupStateStore
    )
    LoggerFactory.getLogger("InstantIoT").info(
        "Setup state at boot: ${setupStateService.compute()}"
    )

    // ============================================================
    // Authentification JWT — doit être configuré avant les routes
    // ============================================================
    configureAuth(userRepository)

    // ============================================================
    // Relay devices — TCP port 9001
    // Chaque connexion ESP dans sa propre coroutine IO
    // ============================================================
    startDeviceRelay(deviceRepository, widgetRepository, tcpPort = com.jeanloickdt.common.ServerConfig.runningTcpPort)

    // ============================================================
    // mDNS / Bonjour — annonce du service _instantiot._tcp
    // À ce stade les ports HTTP + TCP sont bindés, on peut publier.
    // Échec non-fatal : si JmDNS ne se lance pas, l'app peut toujours
    // ajouter le serveur manuellement.
    // ============================================================
    val displayName = (System.getenv("HOSTNAME")
        ?: System.getenv("COMPUTERNAME")
        ?: "InstantIoT Server")
    com.jeanloickdt.discovery.MdnsPublisher.start(displayName = displayName)

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

    // downsample raw → min → hour → day + cleanup tous les tiers
    launch(Dispatchers.IO) {
        while (true) {
            delay(com.jeanloickdt.common.ServerConfig.historyDownsampleIntervalMinutes.toLong().minutes)
            val now = System.currentTimeMillis()

            // Étape 1 — agrégation (Phase 2)
            HistoryAggregator.runAll(now)

            // Étape 2 — cleanup par tier
            val dayMs = 24L * 3600_000L

            // opaque (widget_history) — événements non-numériques
            val opaqueCutoff = now - com.jeanloickdt.common.ServerConfig.historyRetentionOpaqueDays.toLong() * dayMs
            widgetHistoryRepository.deleteOlderThan(opaqueCutoff)

            // raw numérique (widget_history_numeric)
            val rawCutoff = now - com.jeanloickdt.common.ServerConfig.historyRetentionRawDays.toLong() * dayMs
            widgetHistoryNumericRepository.deleteOlderThan(rawCutoff)

            // Buckets 1 min
            val minCutoff = now - com.jeanloickdt.common.ServerConfig.historyRetentionMinDays.toLong() * dayMs
            widgetHistoryMinRepository.deleteOlderThan(minCutoff)

            // Buckets 1 h
            val hourCutoff = now - com.jeanloickdt.common.ServerConfig.historyRetentionHourDays.toLong() * dayMs
            widgetHistoryHourRepository.deleteOlderThan(hourCutoff)

            // Buckets 1 jour — purge SI retention > 0, sinon garder indéfiniment
            val dayRetention = com.jeanloickdt.common.ServerConfig.historyRetentionDayDays
            if (dayRetention > 0) {
                val dayCutoff = now - dayRetention.toLong() * dayMs
                widgetHistoryDayRepository.deleteOlderThan(dayCutoff)
            }
        }
    }

    // ============================================================
    // Backup automatique SQLite (V1 Phase 4)
    // Snapshot via VACUUM INTO toutes les N heures + purge selon
    // rétention. Hot-reload : si l'admin change l'interval/retention
    // dans le panel, la prochaine itération utilise les nouveaux
    // params (lecture de ServerConfig à chaque tour).
    // ============================================================
    launch(Dispatchers.IO) {
        // Petit délai initial pour ne pas snapshot pendant le boot
        // (laisse le serveur s'installer / faire son init de DB)
        delay(60_000)
        while (true) {
            if (com.jeanloickdt.common.ServerConfig.backupEnabled) {
                com.jeanloickdt.backup.BackupManager.snapshotNow()
                com.jeanloickdt.backup.BackupManager.cleanup()
            }
            // Re-lecture de l'interval à chaque iter — hot-reload friendly
            val intervalMs = com.jeanloickdt.common.ServerConfig.backupIntervalHours
                .toLong() * 3600_000L
            delay(intervalMs)
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
        // V1 : enrichi avec setup_state + licence summary pour que
        // le frontend route vers /setup, /welcome ou /login selon
        // l'état du first-launch flow.
        get("/api/status") {
            val state = setupStateService.compute()
            val info  = LicenceValidator.getLicenceInfo()
            call.respond(StatusResponse(
                status           = "ok",
                setupState       = when (state) {
                    com.jeanloickdt.auth.SetupState.NeedsLicence -> "needs_licence"
                    com.jeanloickdt.auth.SetupState.NeedsWelcome -> "needs_welcome"
                    com.jeanloickdt.auth.SetupState.Ready        -> "ready"
                },
                licence          = if (info != null && LicenceValidator.isActivated()) {
                    com.jeanloickdt.common.LicenceSummary(
                        id        = info.id,
                        expiresAt = info.expiresAt
                    )
                } else null,
                // legacy fields — derived from same source for compat
                setup_required   = userRepository.count() == 0L,
                licence_required = !LicenceValidator.isActivated()
            ))
        }

        authRoutes(userRepository, projectRepository, deviceRepository, setupStateStore)
        projectRoutes(
            projectRepository, deviceRepository, widgetRepository,
            widgetHistoryRepository, widgetHistoryNumericRepository,
            widgetHistoryMinRepository, widgetHistoryHourRepository, widgetHistoryDayRepository
        )
        deviceRoutes(deviceRepository)
        widgetRoutes(
            widgetRepository, widgetHistoryRepository, widgetHistoryNumericRepository,
            widgetHistoryMinRepository, widgetHistoryHourRepository, widgetHistoryDayRepository
        )

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