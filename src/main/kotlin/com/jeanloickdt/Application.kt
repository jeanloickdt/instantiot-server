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

package com.jeanloickdt

import com.jeanloickdt.auth.authRoutes
import com.jeanloickdt.automation.automationHealthRoutes
import com.jeanloickdt.automation.emailConfigRoutes
import com.jeanloickdt.automation.ruleRoutes
import com.jeanloickdt.auth.configureAuth
import com.jeanloickdt.auth.defaultTokenService
import com.jeanloickdt.auth.data.SqliteUserRepository
import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.auth.domain.UserRepository
import com.jeanloickdt.common.StatusResponse
import com.jeanloickdt.common.systemRoutes
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
import com.jeanloickdt.relay.configureAppRelay
import com.jeanloickdt.relay.startDeviceRelay
import com.jeanloickdt.widget.data.HistoryAggregators
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
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

fun main(args: Array<String>) {
    val startupLogger = LoggerFactory.getLogger("InstantIoT")

    // load the config BEFORE starting the server
    com.jeanloickdt.common.ServerConfig.load()

    // Auto-bind ports: if the preferred port is taken (Tomcat,
    // Jenkins, another InstantIoT running in parallel), we try +1, +2, ...
    // up to +5. Avoids "doesn't work, no idea why" for the
    // maker who clicks on the freshly installed app.
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
    // Synchronize the running ports — mDNS, tray, /api/status will read
    // these values (not the desired config) to announce the correct URL.
    com.jeanloickdt.common.ServerConfig.markRunningPorts(http = httpPort, tcp = tcpPort)

    startupLogger.info("Starting InstantIoT Server v${com.jeanloickdt.common.ServerConfig.version}")
    startupLogger.info("HTTP port: $httpPort | TCP port: $tcpPort")

    try {
        // system tray — icon in the taskbar (desktop)
        // if not supported (headless server) → silent console mode
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
        // With the PortFinder above, this case should no longer occur
        // (except for a rare race). Keep the fallback log for info.
        startupLogger.error("FAILED TO START", e)
        System.exit(1)
    }
}

// ============================================================
// Global dependencies — instantiated once at startup
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

// ── Rule events (socle automatisation) ──────────────────────
// The relay produces, nobody consumes yet: the values channel keeps its
// freshest 1024 and the discrete one its first 4096 — bounded either way.
// The engine will drain them; until then this is inert.
val eventSinks = com.jeanloickdt.event.EventSinks()

/**
 * Which widgets a rule watches. The rule cache will own this; until it
 * exists, nobody watches and the WidgetValue producer publishes nothing —
 * one predicate call per frame, nothing else.
 */
@Volatile
var watchedWidgets: (com.jeanloickdt.relay.WidgetKey) -> Boolean = { false }

// Message ledger — RAM on the hot path, drained by the 5 s flush. A plain
// usage statistic here; the cloud edition reads it for its monthly quota.
val messageUsage     = com.jeanloickdt.automation.MessageUsageCounter()
val messageUsageRepo: com.jeanloickdt.automation.MessageUsageRepository =
    com.jeanloickdt.automation.SqliteMessageUsageRepository()

// The durability frontier. The sender registry is EMPTY until the delivery
// channels exist (EMAIL via the operator's SMTP key, COMMAND via the
// DeviceOutbox) — the worker leases nothing from an empty table and costs
// one indexed SELECT per second.
val pendingActions: com.jeanloickdt.automation.PendingActionRepository =
    com.jeanloickdt.automation.SqlitePendingActionRepository()

// The rules, in RAM — reloaded in module() once the DB is up, and after every
// rule mutation (the CRUD's single coupling point).
val ruleCache = com.jeanloickdt.automation.RuleCache()

private val logger = LoggerFactory.getLogger("Application")

// dbFile is injectable so tests boot the REAL module against a throwaway
// database instead of the production ~/.instantiot/instantiot.db. Production
// (main → Application::module) uses the default. Without this a test booting
// module() would migrate / mark-offline / write the user's real DB.
fun Application.module(dbFile: File = com.jeanloickdt.common.ServerConfig.dbFile) {

    // ============================================================
    // Global plugins
    // ============================================================
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception on ${call.request.local.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                com.jeanloickdt.common.ApiError("Internal Server Error")
            )
        }
    }

    install(CORS) {
        anyHost()  // permissive for the beta — the client restricts via reverse proxy
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
    // Pending restore — applied HERE, before the pool opens.
    //
    // A restore staged from the admin panel (BackupManager.stageRestore) is
    // applied at boot rather than swapped under the live pool: with no
    // connection open yet there is no split-brain. This snapshots the current
    // DB as a WAL-complete safety net, then atomically swaps in the backup.
    // No-op when nothing is pending.
    // ============================================================
    com.jeanloickdt.backup.BackupManager.applyPendingRestore(dbFile)

    // ============================================================
    // Database — init SQLite + WAL + table creation
    // Add the tables of new modules here as they are introduced
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
        WidgetHistoryDayTable,
        *com.jeanloickdt.automation.data.AutomationTables.ALL,
        dbFile = dbFile
    )

    // Reset stale online state: if the server was killed abruptly
    // (Ctrl+C that skips the `finally` of `handleDevice`), the DB may
    // keep `isOnline=true` for devices that no longer have an active
    // TCP session. At startup, no session exists → everything must
    // be offline, devices will go online as soon as they
    // reconnect and send their handshake.
    deviceRepository.markAllOffline()

    // ============================================================
    // Relay state (DI) — the node-local seams, injected by parameter
    // everywhere. No global singleton: tests build their own instances.
    //   connections : live sockets/WS sessions (local by nature)
    //   buffers     : RAM staging of the ingest pipeline (5s flush)
    //   lastValues  : real-time last value per widget (DB coalesced)
    //   presence    : device online/offline (DB-backed mono-node impl;
    //                 multi-node = swap the impl, never the call sites)
    // ============================================================
    val connections = com.jeanloickdt.relay.ConnectionRegistry()
    val buffers = com.jeanloickdt.relay.HistoryBuffers()
    val lastValues: com.jeanloickdt.relay.LastValueCache = com.jeanloickdt.relay.InMemoryLastValueCache()
    val presence: com.jeanloickdt.relay.PresenceStore = com.jeanloickdt.relay.DbBackedPresenceStore(deviceRepository)
    val controlEvents = com.jeanloickdt.relay.ControlEventBroadcaster(connections)

    // Cache-aware widget repository (composition root): wraps the pure SQLite
    // repo so every widgets-table write keeps knownWidgetIds + lastValues in
    // sync — including the project cascade, which used to bypass cache sync. All
    // widget-mutating callers (routes, relay, cascade) go through this.
    val cacheAwareWidgets: WidgetRepository =
        com.jeanloickdt.relay.CacheAwareWidgetRepository(widgetRepository, buffers.knownWidgetIds, lastValues)

    // Seed the declared-widgets cache from the table at boot, so it reflects
    // what is persisted (today auto-register would re-fill it lazily; this is
    // the prerequisite for the strict model, where an unseeded cache would drop
    // the first frame of every already-declared widget).
    val seededWidgets = widgetRepository.findAll()
    seededWidgets.forEach { buffers.knownWidgetIds.add(com.jeanloickdt.relay.WidgetKey(it.ownerId, it.id)) }
    logger.info("Seeded ${seededWidgets.size} declared widget(s) into the cache")

    // final flush at shutdown — no buffer data lost
    // iWidgets rework: we ALSO flush all RAM buckets (including
    // in-progress buckets not yet closed) → zero loss on a controlled
    // restart. On a hard crash, we lose at worst 1 min/h/24h per tier.
    //
    // Registered on BOTH paths because they don't overlap:
    //   - Ktor ApplicationStopping → fires on a graceful engine stop (SIGTERM,
    //     systemd, embeddedServer's own shutdown hook).
    //   - JVM shutdown hook → fires on System.exit(0), which the system-tray
    //     "Quit"/"Restart" actions call directly. Without this, the tray path
    //     could skip the flush and lose up to 24h of the in-progress day
    //     bucket — breaking the "zero loss on controlled restart" guarantee.
    // The flush is idempotent (drains queues/buckets), so running it twice on
    // a path where both fire is harmless — the second pass finds nothing.
    val finalFlush: () -> Unit = {
        kotlinx.coroutines.runBlocking {
            flushHistoryBuffer(buffers, widgetHistoryRepository)
            flushNumericHistoryBuffer(buffers, widgetHistoryNumericRepository)
            flushLastValues(lastValues, cacheAwareWidgets)
            flushAllAggregatorBuckets(
                minRepo  = widgetHistoryMinRepository,
                hourRepo = widgetHistoryHourRepository,
                dayRepo  = widgetHistoryDayRepository,
                events   = controlEvents
            )
        }
    }
    monitor.subscribe(ApplicationStopping) { finalFlush() }
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { finalFlush() }
            .onFailure { LoggerFactory.getLogger("InstantIoT").error("Shutdown-hook flush failed", it) }
    })

    // mDNS / Bonjour — unregisters the service before the sockets close
    // so that apps see the server disappear cleanly.
    monitor.subscribe(ApplicationStopping) {
        com.jeanloickdt.discovery.MdnsPublisher.stop()
    }

    // ============================================================
    // Bootstrap / admin recovery — default admin account
    //
    // V1.3: no more licensing system. The server always starts
    // ready with an admin account.
    //
    //   • First startup (no admin in DB) → creates admin/admin.
    //   • Recovery: if the file ~/.instantiot/reset-admin exists,
    //     we reset the admin's password to "admin" then
    //     delete the file. This is the recovery mechanism
    //     when the admin has forgotten their password — gated by
    //     filesystem access to the machine (only the owner can create
    //     this file), so no network attack surface.
    //
    // The admin is prompted to change this default password after
    // their first login (POST /api/users/me/password).
    // ============================================================
    run {
        val bootLog = LoggerFactory.getLogger("InstantIoT")
        val resetMarker = File("${System.getProperty("user.home")}/.instantiot/reset-admin")
        val existingAdmin = userRepository.findByUsername("admin")

        when {
            existingAdmin == null -> {
                val pwdHash = BCrypt.hashpw("admin", BCrypt.gensalt())
                // passwordChanged = false → login returns passwordChanged=false,
                // forcing the admin off the default admin/admin credentials.
                userRepository.create("admin", pwdHash, role = "admin", passwordChanged = false)
                bootLog.warn(
                    "Bootstrap: admin user created with default credentials " +
                        "admin/admin — change the password after first login"
                )
            }
            resetMarker.exists() -> {
                val pwdHash = BCrypt.hashpw("admin", BCrypt.gensalt())
                // reset back to the default → must be changed again at next login
                userRepository.updatePassword(existingAdmin.id, pwdHash, passwordChanged = false)
                bootLog.warn(
                    "Admin password reset to default 'admin' (reset-admin marker found)"
                )
            }
        }

        // Always clean up the marker if it exists — whether it was
        // used or not (case: marker present on the very first boot, the
        // admin had just been created with admin/admin anyway).
        if (resetMarker.exists() && resetMarker.delete()) {
            bootLog.info("reset-admin marker consumed and deleted")
        }
    }

    // ============================================================
    // JWT authentication — must be configured before the routes.
    // TokenService is injected (no global singleton): HS256 today + the
    // token_version revocation claim; a future cloud impl plugs in here.
    // ============================================================
    val tokenService = defaultTokenService()
    configureAuth(userRepository, tokenService)

    // ============================================================
    // Device relay — TCP port 9001
    // Each ESP connection in its own IO coroutine
    // ============================================================
    startDeviceRelay(
        deviceRepository,
        connections = connections,
        buffers     = buffers,
        lastValues  = lastValues,
        presence    = presence,
        events      = controlEvents,
        sinks       = eventSinks,
        watchedWidgets = { key -> watchedWidgets(key) },
        usage       = messageUsage,
        tcpPort     = com.jeanloickdt.common.ServerConfig.runningTcpPort
    )

    // ============================================================
    // mDNS / Bonjour — announces the _instantiot._tcp service
    // At this point the HTTP + TCP ports are bound, we can publish.
    // Non-fatal failure: if JmDNS does not start, the app can still
    // add the server manually.
    // ============================================================
    // V1: name configurable via admin panel (otherwise fallback HOSTNAME).
    // Lets a user with 2+ servers on the same LAN distinguish them
    // in the mDNS Discovery app (living-room Pi, etc.).
    val displayName = com.jeanloickdt.common.ServerConfig.serverDisplayName
        .takeIf { it.isNotBlank() }
        ?: System.getenv("HOSTNAME")
        ?: System.getenv("COMPUTERNAME")
        ?: "InstantIoT Server"
    com.jeanloickdt.discovery.MdnsPublisher.start(displayName = displayName)

    // ============================================================
    // Flush history buffer → SQLite WAL batch every 5s
    //
    // iWidgets rework (tiered-aggregation architecture): a single 5s job
    // drains the 5 sources and persists in batch:
    //   - historyBuffer        → widget_history (opaque events)
    //   - numericHistoryBuffer → widget_history_numeric (raw, opt-in)
    //   - HistoryAggregators.minute closed → widget_history_min
    //   - HistoryAggregators.hour  closed → widget_history_hour
    //   - HistoryAggregators.day   closed → widget_history_day
    //
    // The DB is NEVER on the critical path of the device relay.
    // ============================================================
    // Shared logger for the background maintenance loops below. Each loop
    // body is wrapped in try/catch so a transient failure (e.g. a SQLITE_BUSY
    // that slipped past the busy timeout) logs and retries on the next tick
    // instead of killing the coroutine permanently — a dead flush loop would
    // silently stop persisting and let the RAM buffers grow until OOM.
    val bgLog = LoggerFactory.getLogger("InstantIoT.maintenance")

    // Flush cadence and its alert thresholds — see the loop below.
    val FLUSH_PERIOD_MS = 5_000L
    // A round costing a fifth of its period already deserves attention: it
    // leaves little headroom before the loop starts stretching.
    val FLUSH_SLOW_MS = 1_000L
    // ~5 min at a 5 s period. A healthy server must still say so periodically,
    // otherwise there is no baseline to compare a bad day against.
    val FLUSH_HEARTBEAT_ROUNDS = 60L

    launch(Dispatchers.IO) {
        // Timing the flush round is the leading indicator of saturation.
        //
        // This loop is `delay(period)` THEN work, not a fixed-rate scheduler:
        // the effective period is `period + flush duration`. A slow flush
        // therefore does not merely arrive late, it *stretches the interval* —
        // the RAM buffers take in more, the next round has more to write, and
        // the drift compounds. Degradation is gradual, which is exactly why it
        // has to be measured rather than waited for.
        //
        // Row counts come back from the flush functions: the batch is already
        // materialised there, whereas `.size` on a ConcurrentLinkedQueue is
        // O(n) and would cost more than the flush it measures.
        var round = 0L
        while (true) {
            delay(FLUSH_PERIOD_MS)
            val startedAt = System.nanoTime()
            var opaqueRows = 0
            var numericRows = 0
            try {
                opaqueRows  = flushHistoryBuffer(buffers, widgetHistoryRepository)

                // message ledger — a handful of rows per cycle, one per owner
                // that emitted since the last flush.
                run {
                    val period = com.jeanloickdt.automation.MessageUsageRepository.periodOf(System.currentTimeMillis())
                    messageUsage.drain().forEach { (owner, delta) ->
                        messageUsageRepo.add(owner, period, delta)
                    }
                }
                numericRows = flushNumericHistoryBuffer(buffers, widgetHistoryNumericRepository)
                flushLastValues(lastValues, cacheAwareWidgets)
                flushClosedAggregatorBuckets(
                    minRepo  = widgetHistoryMinRepository,
                    hourRepo = widgetHistoryHourRepository,
                    dayRepo  = widgetHistoryDayRepository,
                    events   = controlEvents
                )
            } catch (e: Exception) {
                bgLog.error("History flush round failed — retrying in 5s", e)
            }
            val tookMs = (System.nanoTime() - startedAt) / 1_000_000
            round++
            val summary = "flush took ${tookMs}ms — opaque=$opaqueRows numeric=$numericRows"
            when {
                // The round now costs more than its own period: the loop is
                // behind and the buffers are growing between rounds.
                tookMs >= FLUSH_PERIOD_MS ->
                    bgLog.warn("$summary — EXCEEDS the ${FLUSH_PERIOD_MS}ms period, the loop is falling behind")
                tookMs >= FLUSH_SLOW_MS ->
                    bgLog.info("$summary — slow")
                // Periodic baseline: without it, a healthy server logs nothing
                // and there is no reference to compare a bad day against.
                round % FLUSH_HEARTBEAT_ROUNDS == 0L ->
                    bgLog.info("$summary — round $round")
            }
        }
    }

    // ============================================================
    // Periodic cleanup per tier (configurable retention)
    //
    // No more deferred SQL downsampling — the RAM aggregators
    // feed the aggregated tables in real time. All that remains is
    // cleaning up old buckets according to each tier's retention.
    //
    // Runs once per hour (retention is in days, no need for
    // minute-by-minute precision to purge).
    // ============================================================
    launch(Dispatchers.IO) {
        while (true) {
            delay(60.minutes)
            try {
                val now = System.currentTimeMillis()
                val dayMs = 24L * 3600_000L

                // opaque (widget_history) — non-numeric events
                val opaqueCutoff = now - com.jeanloickdt.common.ServerConfig.historyRetentionOpaqueDays.toLong() * dayMs
                widgetHistoryRepository.deleteOlderThan(opaqueCutoff)

                // raw numeric (widget_history_numeric) — opt-in
                val rawCutoff = now - com.jeanloickdt.common.ServerConfig.historyRetentionRawDays.toLong() * dayMs
                widgetHistoryNumericRepository.deleteOlderThan(rawCutoff)

                // 1 min buckets
                val minCutoff = now - com.jeanloickdt.common.ServerConfig.historyRetentionMinDays.toLong() * dayMs
                widgetHistoryMinRepository.deleteOlderThan(minCutoff)

                // 1 h buckets
                val hourCutoff = now - com.jeanloickdt.common.ServerConfig.historyRetentionHourDays.toLong() * dayMs
                widgetHistoryHourRepository.deleteOlderThan(hourCutoff)

                // 1 day buckets — purge IF retention > 0, otherwise keep indefinitely
                val dayRetention = com.jeanloickdt.common.ServerConfig.historyRetentionDayDays
                if (dayRetention > 0) {
                    val dayCutoff = now - dayRetention.toLong() * dayMs
                    widgetHistoryDayRepository.deleteOlderThan(dayCutoff)
                }
            } catch (e: Exception) {
                bgLog.error("History retention cleanup round failed — retrying next hour", e)
            }
        }
    }

    // ============================================================
    // Rules engine (étape 5) — the piece between the event sinks and
    // the durability frontier. Zero rules in the table = the cache is
    // empty, watches() is false everywhere, and NOTHING changes.
    // ============================================================
    ruleCache.reload()
    watchedWidgets = { key -> ruleCache.watches(key) }
    val automationEngine = com.jeanloickdt.automation.AutomationEngine(
        eventSinks, ruleCache, pendingActions,
        com.jeanloickdt.automation.SqliteAutomationStateStore(), deviceRepository
    )
    launch(Dispatchers.Default) { automationEngine.run() }

    // The health watch (étape 8) — the server is the only party that can see
    // a silent delivery outage. 30 s cadence, warnings past the thresholds.
    val healthWatch = com.jeanloickdt.automation.AutomationHealthWatch()
    launch(Dispatchers.IO) {
        while (true) {
            delay(30_000)
            try {
                healthWatch.logAll(healthWatch.check(
                    com.jeanloickdt.automation.snapshot(pendingActions, eventSinks, automationEngine, System.currentTimeMillis())
                ))
            } catch (e: Exception) {
                bgLog.error("Health watch failed — retrying next round", e)
            }
        }
    }


    // The tick: offline confirmations every 10 s (the "afterS" debounce runs
    // HERE, never as a delay in the engine — one offline rule must not freeze
    // every rule for thirty seconds), stale sweep every 60 s.
    val staleSweeper = com.jeanloickdt.event.WidgetStaleSweeper(lastValues, eventSinks)
    val schedulerWorker = com.jeanloickdt.automation.SchedulerWorker(eventSinks)
    launch(Dispatchers.Default) {
        var i = 0
        while (true) {
            delay(10_000)
            try {
                automationEngine.tick(System.currentTimeMillis())
                schedulerWorker.pollOnce()
                if (++i % 6 == 0) {
                    staleSweeper.sweep(ruleCache.watchedStaleKeys(), System.currentTimeMillis())
                }
            } catch (e: Exception) {
                bgLog.error("Automation tick failed — retrying next tick", e)
            }
        }
    }

    // ============================================================
    // Delivery loop — drains pending_actions every second. The lease inside
    // the repo is the whole crash story: a pass that dies mid-batch leaves
    // its rows leased, and any later pass picks them up when the lease
    // expires. No recovery code, just an expiry.
    // ============================================================
    // ── Étape 6 : les expéditeurs EMAIL et COMMAND ──
    // EMAIL lit sa config à CHAQUE envoi : une clé collée dans le panneau agit
    // à la livraison suivante, sans redémarrage. Le destinataire : 'to' de la
    // règle → l'email du compte (en cloud, le username iia EST l'email ; en
    // self-host il ne l'est pas et ce maillon rend null) → l'adresse d'alerte
    // du panneau. COMMAND passe par la même outbox que les commandes de
    // l'app. PUSH attend le projet Firebase (le worker marque DEAD, motif
    // clair, et l'API refuse déjà les règles PUSH là où il n'existera pas).
    val emailSender = com.jeanloickdt.automation.EmailActionSender(
        config = {
            com.jeanloickdt.automation.EmailConfig(
                apiKey    = com.jeanloickdt.common.ServerConfig.emailBrevoApiKey,
                fromEmail = com.jeanloickdt.common.ServerConfig.emailFrom,
                fromName  = com.jeanloickdt.common.ServerConfig.emailFromName,
                defaultTo = com.jeanloickdt.common.ServerConfig.emailAlertTo
            )
        },
        accountEmail = { ownerId ->
            userRepository.findById(ownerId)?.username?.takeIf { "@" in it }
        }
    )
    val commandSender = com.jeanloickdt.automation.CommandActionSender(
        deviceOwner = { deviceId -> deviceRepository.findById(deviceId)?.ownerId },
        sendToDevice = { deviceId, frame ->
            // discret, jamais streaming : une commande de règle ne se jette pas
            connections.deviceOutboxes[deviceId]?.send(frame, isStreaming = false) ?: false
        }
    )
    val deliveryWorker = com.jeanloickdt.automation.DeliveryWorker(
        pendingActions,
        senders = mapOf(
            com.jeanloickdt.automation.DeliveryWorker.TYPE_EMAIL to emailSender,
            com.jeanloickdt.automation.DeliveryWorker.TYPE_COMMAND to commandSender
        )
    )

    launch(Dispatchers.IO) {
        while (true) {
            delay(1_000)
            try {
                deliveryWorker.runOnce()
            } catch (e: Exception) {
                bgLog.error("Delivery pass failed — retrying next second", e)
            }
        }
    }

    // ============================================================
    // Automatic SQLite backup (V1 Phase 4)
    // Snapshot via VACUUM INTO every N hours + purge according to
    // retention. Hot-reload: if the admin changes the interval/retention
    // in the panel, the next iteration uses the new
    // params (reads ServerConfig on each round).
    // ============================================================
    launch(Dispatchers.IO) {
        // Small initial delay so we don't snapshot during boot
        // (lets the server settle / do its DB init)
        delay(60_000)
        while (true) {
            try {
                if (com.jeanloickdt.common.ServerConfig.backupEnabled) {
                    com.jeanloickdt.backup.BackupManager.snapshotNow()
                    com.jeanloickdt.backup.BackupManager.cleanup()
                }
            } catch (e: Exception) {
                bgLog.error("Backup snapshot round failed — retrying next interval", e)
            }
            // Re-read the interval on each iter — hot-reload friendly
            val intervalMs = com.jeanloickdt.common.ServerConfig.backupIntervalHours
                .toLong() * 3600_000L
            delay(intervalMs)
        }
    }

    // ============================================================
    // Weekly incremental vacuum — reclaim disk freed by retention DELETEs
    //
    // Without this the DB file only ever grows (SQLite keeps freed pages on the
    // freelist). The file is in auto_vacuum=INCREMENTAL (DatabaseFactory.init),
    // so this just trims the freelist back to the OS via PRAGMA
    // incremental_vacuum — no full-file rewrite, no multi-second exclusive lock
    // that a full VACUUM would take under load. Runs once a week after a 6h
    // initial delay so it never coincides with boot or the first backup.
    // ============================================================
    launch(Dispatchers.IO) {
        delay(6L * 3600_000L)
        while (true) {
            try {
                DatabaseFactory.incrementalVacuum(dbFile)
                bgLog.info("Weekly incremental vacuum completed — freelist pages returned to the OS")
            } catch (e: Exception) {
                bgLog.error("Weekly incremental vacuum failed — retrying next week", e)
            }
            delay(7L * 24 * 3600_000L)
        }
    }

    // ============================================================
    // App relay — WebSocket /ws/app
    // ============================================================
    configureAppRelay(projectRepository, connections, controlEvents)

    // ============================================================
    // REST routes
    // ============================================================
    routing {
        staticResources("/", "static")

        // Unauthenticated liveness + version (separate from /api/status)
        systemRoutes()

        // GET /api/status — server state — always accessible.
        // V1.3: no more license or first-launch flow → the server
        // always starts ready. setup_state is always "ready"
        // (field kept for compat with the admin panel).
        get("/api/status") {
            call.respond(StatusResponse(
                status         = "ok",
                setupState     = "ready",
                setup_required = userRepository.count() == 0L,
                tcpPort        = com.jeanloickdt.common.ServerConfig.runningTcpPort
            ))
        }

        val accountPurge = com.jeanloickdt.auth.AccountPurge(
            userRepository, projectRepository, deviceRepository, cacheAwareWidgets,
            widgetHistoryRepository, widgetHistoryNumericRepository,
            widgetHistoryMinRepository, widgetHistoryHourRepository, widgetHistoryDayRepository,
            connections, controlEvents
        )
        authRoutes(userRepository, projectRepository, deviceRepository, connections, tokenService, accountPurge)
        projectRoutes(
            projectRepository, deviceRepository, cacheAwareWidgets,
            widgetHistoryRepository, widgetHistoryNumericRepository,
            widgetHistoryMinRepository, widgetHistoryHourRepository, widgetHistoryDayRepository,
            connections, controlEvents
        )
        deviceRoutes(deviceRepository, projectRepository, connections, controlEvents)
        emailConfigRoutes(userRepository, emailSender)
        automationHealthRoutes(userRepository, pendingActions, eventSinks, automationEngine)
        ruleRoutes(
            ruleCache, cacheAwareWidgets, deviceRepository,
            com.jeanloickdt.automation.RulePolicies(
                // The OFFRE boundary: no Firebase credentials can ship in a
                // public repo, so PUSH rules are refused at creation with a
                // message that says why — not enqueued into DEAD rows.
                allowedActionTypes = setOf(
                    com.jeanloickdt.automation.RuleDefinition.TYPE_EMAIL,
                    com.jeanloickdt.automation.RuleDefinition.TYPE_COMMAND
                )
            )
        )
        widgetRoutes(
            cacheAwareWidgets, projectRepository, widgetHistoryRepository, widgetHistoryNumericRepository,
            widgetHistoryMinRepository, widgetHistoryHourRepository, widgetHistoryDayRepository,
            lastValues
        )

        // TODO: add the routes of new modules here
    }
}

// ============================================================
// Flush history buffer → SQLite WAL batch insert
// Called every 5s + at shutdown
// ============================================================
private suspend fun flushHistoryBuffer(buffers: com.jeanloickdt.relay.HistoryBuffers, widgetHistoryRepository: WidgetHistoryRepository): Int {
    val batch = mutableListOf<HistoryEntry>()
    while (buffers.historyBuffer.isNotEmpty()) {
        batch.add(buffers.historyBuffer.poll() ?: break)
    }

    if (batch.isEmpty()) return 0

    val historyRows = batch.map { entry ->
        WidgetHistoryRow(
            id         = 0,              // auto-increment — ignored on insert
            widgetId   = entry.widgetId,
            projectId  = entry.projectId,
            ownerId    = entry.ownerId,
            payload    = entry.payload,
            recordedAt = entry.recordedAt
        )
    }

    widgetHistoryRepository.insertBatch(historyRows)
    return historyRows.size
}

// ============================================================
// Flush numeric history buffer → SQLite WAL batch insert
// ============================================================
private suspend fun flushNumericHistoryBuffer(buffers: com.jeanloickdt.relay.HistoryBuffers, repo: WidgetHistoryNumericRepository): Int {
    val batch = mutableListOf<NumericHistoryEntry>()
    while (buffers.numericHistoryBuffer.isNotEmpty()) {
        batch.add(buffers.numericHistoryBuffer.poll() ?: break)
    }

    if (batch.isEmpty()) return 0

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
    return rows.size
}

// ============================================================
// Coalesced last_payload persistence (5s job + shutdown)
//
// The device read-loop only writes the LastValueCache in RAM. Here we drain
// the entries changed since the last cycle and upsert them in ONE transaction
// — at most one DB write per changed widget per 5s, never per frame. The DB
// column is a cold-start fallback (live value is the cache); a ≤5s lag (≤10s
// in the documented drain race) is acceptable by design.
// ============================================================
private suspend fun flushLastValues(
    lastValues: com.jeanloickdt.relay.LastValueCache,
    widgetRepository: WidgetRepository
) {
    val dirty = lastValues.drainDirty()
    if (dirty.isEmpty()) return
    widgetRepository.updateLastPayloadBatch(
        dirty.map { (key, v) ->
            com.jeanloickdt.widget.domain.LastPayloadUpdate(key.ownerId, key.widgetId, v.payload, v.at)
        }
    )
}

// ============================================================
// Flush the CLOSED buckets of the RAM aggregators (5s job)
//
// A bucket is "closed" when its window (bucketAt + bucketSizeMs)
// is ≤ now. The current bucket stays in RAM to keep
// accumulating the samples that arrive during its window.
// ============================================================
private suspend fun flushClosedAggregatorBuckets(
    minRepo: WidgetHistoryAggregateRepository,
    hourRepo: WidgetHistoryAggregateRepository,
    dayRepo: WidgetHistoryAggregateRepository,
    events: com.jeanloickdt.relay.ControlEventBroadcaster
) {
    val now = System.currentTimeMillis()
    flushAggregatorTier(minRepo,  HistoryAggregators.minute.extractClosedBuckets(now), com.jeanloickdt.relay.BucketGranularity.MINUTE, broadcast = true, events = events)
    flushAggregatorTier(hourRepo, HistoryAggregators.hour.extractClosedBuckets(now),   com.jeanloickdt.relay.BucketGranularity.HOUR,   broadcast = true, events = events)
    flushAggregatorTier(dayRepo,  HistoryAggregators.day.extractClosedBuckets(now),    com.jeanloickdt.relay.BucketGranularity.DAY,    broadcast = true, events = events)
}

// ============================================================
// Flush ALL the aggregator buckets (including in-progress ones)
//
// Called ONLY on a clean shutdown via ApplicationStopping
// so nothing is lost on a controlled restart. Once flushed,
// the aggregators are empty — samples arriving after
// this point are no longer collected (the server is stopping).
//
// No WS broadcast at shutdown: the apps are losing their
// connection anyway (and will re-fetch their full history
// on the next open).
// ============================================================
private suspend fun flushAllAggregatorBuckets(
    minRepo: WidgetHistoryAggregateRepository,
    hourRepo: WidgetHistoryAggregateRepository,
    dayRepo: WidgetHistoryAggregateRepository,
    events: com.jeanloickdt.relay.ControlEventBroadcaster
) {
    flushAggregatorTier(minRepo,  HistoryAggregators.minute.extractAllBuckets(), com.jeanloickdt.relay.BucketGranularity.MINUTE, broadcast = false, events = events)
    flushAggregatorTier(hourRepo, HistoryAggregators.hour.extractAllBuckets(),   com.jeanloickdt.relay.BucketGranularity.HOUR,   broadcast = false, events = events)
    flushAggregatorTier(dayRepo,  HistoryAggregators.day.extractAllBuckets(),    com.jeanloickdt.relay.BucketGranularity.DAY,    broadcast = false, events = events)
}

/**
 * Converts the snapshots into `AggregateInsertRow` + insertBatch.
 * If [broadcast] = true, emits a "bucket_updated" control event for
 * each snapshot after the successful DB insert. Lets app-side charts
 * in historical preset mode update their window without
 * re-fetching over HTTP.
 */
private suspend fun flushAggregatorTier(
    repo: WidgetHistoryAggregateRepository,
    snapshots: List<com.jeanloickdt.widget.data.BucketAccumulator.Snapshot>,
    granularity: String,
    broadcast: Boolean,
    events: com.jeanloickdt.relay.ControlEventBroadcaster
) {
    if (snapshots.isEmpty()) return
    val rows = snapshots.map { snap ->
        WidgetHistoryAggregateRepository.AggregateInsertRow(
            widgetId    = snap.widgetId,
            projectId   = snap.projectId,
            ownerId     = snap.ownerId,
            seriesId    = snap.seriesId,
            avgValue    = snap.avgValue,
            minValue    = snap.minValue,
            maxValue    = snap.maxValue,
            sampleCount = snap.sampleCount,
            bucketAt    = snap.bucketAt
        )
    }
    repo.insertBatch(rows)

    if (broadcast) {
        snapshots.forEach { snap ->
            events.bucketClosed(
                projectId   = snap.projectId,
                widgetId    = snap.widgetId,
                seriesId    = snap.seriesId,
                bucketAt    = snap.bucketAt,
                avg         = snap.avgValue,
                min         = snap.minValue,
                max         = snap.maxValue,
                count       = snap.sampleCount,
                granularity = granularity
            )
        }
    }
}