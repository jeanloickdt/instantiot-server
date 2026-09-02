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
import com.jeanloickdt.signal.signalRoutes
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
import com.jeanloickdt.device.data.ExposedDeviceRepository
import com.jeanloickdt.device.deviceRoutes
import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.project.data.ExposedProjectRepository
import com.jeanloickdt.project.domain.ProjectRepository
import com.jeanloickdt.project.projectRoutes
import com.jeanloickdt.relay.configureAppRelay
import com.jeanloickdt.relay.startDeviceRelay
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
val projectRepository: ProjectRepository         = ExposedProjectRepository()

// Une instance, deux vues. Les routes voient le depot cadre par compte ; le
// relais ne voit que l'ecriture de presence, qui ne l'est pas — et ce
// decoupage rend l'absence de proprietaire visible a la declaration.
private val deviceStore = ExposedDeviceRepository()
val deviceRepository: DeviceRepository = deviceStore
val devicePresence: com.jeanloickdt.device.domain.DevicePresenceWriter = deviceStore

/**
 * The signal store, with the database taken off the relay's hottest line.
 *
 * Kept as the concrete cached type rather than the interface because the flush
 * loop below has to reach [CachedSignalRepository.flushPendingValues] — the
 * batch write is the whole point of the buffer, and hiding it behind the
 * interface would only mean casting it back.
 */
val signalRepository: com.jeanloickdt.signal.data.CachedSignalRepository =
    com.jeanloickdt.signal.data.CachedSignalRepository(
        com.jeanloickdt.signal.data.ExposedSignalRepository()
    )

val signalHistoryRepository = com.jeanloickdt.signal.data.ExposedSignalHistoryRepository()

// ── Rule events (socle automatisation) ──────────────────────
// The relay produces, nobody consumes yet: the values channel keeps its
// freshest 1024 and the discrete one its first 4096 — bounded either way.
// The engine will drain them; until then this is inert.
val eventSinks = com.jeanloickdt.event.EventSinks()

/**
 * Quels signaux une regle surveille. Le cache des regles en est
 * proprietaire ; sans regle, personne ne surveille et le producteur
 * `SignalValue` ne publie rien — un appel de predicat par trame, rien de
 * plus.
 */
@Volatile
var watchedSignals: (com.jeanloickdt.relay.SignalRef) -> Boolean = { false }

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
        *com.jeanloickdt.signal.data.SignalTables.ALL,
        *com.jeanloickdt.automation.data.AutomationTables.ALL,
        dbFile = dbFile
    )

    // Reset stale online state: if the server was killed abruptly
    // (Ctrl+C that skips the `finally` of `handleDevice`), the DB may
    // keep `isOnline=true` for devices that no longer have an active
    // TCP session. At startup, no session exists → everything must
    // be offline, devices will go online as soon as they
    // reconnect and send their handshake.
    devicePresence.markAllOffline()

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
    val presence: com.jeanloickdt.relay.PresenceStore = com.jeanloickdt.relay.DbBackedPresenceStore(devicePresence)
    val controlEvents = com.jeanloickdt.relay.ControlEventBroadcaster(connections)

    // Il y avait ici un depot de widgets « conscient du cache » et un
    // amorcage de `knownWidgetIds` depuis la table : le modele strict
    // exigeait de connaitre les widgets declares avant la premiere trame.
    //
    // Une adresse se declare desormais dans la table des signaux, et
    // l'ingestion l'y lit — il n'y a plus de cache a tenir en phase, donc
    // plus d'occasion de le laisser deriver.

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
            // Meme promesse que les tampons ci-dessus : zero perte sur un
            // arret maitrise. Un arret brutal coute au pire une periode de
            // vidage de dernieres valeurs — jamais une consigne, qui
            // n'attend jamais ici.
            signalRepository.flushPendingValues()
            (presence as? com.jeanloickdt.relay.DbBackedPresenceStore)?.flushPending()
            signalHistoryRepository.insertMinuteBatch(
                com.jeanloickdt.signal.data.SignalAggregators.minute.extractAllBuckets()
            )
            signalHistoryRepository.insertRawBatch(buffers.signalRawBuffer.drain())
        }
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
        watchedSignals = { ref -> watchedSignals(ref) },
        usage       = messageUsage,
        signals     = signalRepository,
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
    // Flush history buffer → un lot ecrit toutes les 5 s
    //
    // Un seul travail de 5 s vide les deux sources du modèle signal :
    //   - signalRawBuffer                 → signal_raw (brut, sur option)
    //   - SignalAggregators.minute fermée → signal_min
    //
    // L'heure et le jour ne sont plus accumulés en RAM : ils se dérivent de la
    // minute, ce qui évite qu'un redémarrage perde trois paliers au lieu d'un.
    //
    // The DB is NEVER on the critical path of the device relay.
    // ============================================================
    // Shared logger for the background maintenance loops below. Each loop
    // body is wrapped in try/catch so a transient failure (a lost connection,
    // a lock timeout) logs and retries on the next tick
    // instead of killing the coroutine permanently — a dead flush loop would
    // silently stop persisting and let the RAM buffers grow until OOM.
    val bgLog = LoggerFactory.getLogger("InstantIoT.maintenance")

    // La cadence vient de la configuration — voir [ServerConfig.historyFlushPeriodMs]
    // pour ce que le nombre achete dans les deux sens. Lue UNE FOIS ici : la
    // changer a chaud ferait varier le seuil d'alerte au milieu d'une mesure,
    // et un redemarrage est le prix normal d'un reglage de cadence.
    val FLUSH_PERIOD_MS = com.jeanloickdt.common.ServerConfig.historyFlushPeriodMs
    // Un tour qui coute le cinquieme de sa periode merite deja l'attention :
    // il reste peu de marge avant que la boucle commence a s'etirer. Derive
    // de la periode plutot que fixe a 1 000 ms — sinon un operateur qui
    // allonge la cadence a trente secondes ne verrait plus jamais l'alerte.
    val FLUSH_SLOW_MS = FLUSH_PERIOD_MS / 5
    // ~5 min, quelle que soit la cadence. Un serveur en bonne sante doit le
    // dire periodiquement : sans ligne de reference, un mauvais jour ne se
    // compare a rien.
    val FLUSH_HEARTBEAT_ROUNDS = (5 * 60_000L / FLUSH_PERIOD_MS).coerceAtLeast(1L)

    launch(com.jeanloickdt.common.ServerDispatchers.storage) {
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
        // Only the DELTA is worth a line: a queue that refused once and
        // recovered must not warn on every round for the rest of the day.
        var lastRefusedTotal = 0L
        while (true) {
            delay(FLUSH_PERIOD_MS)
            val startedAt = System.nanoTime()
            var signalRows = 0
            var rawRows = 0
            try {

                // messages.perMonth ledger — a handful of rows per cycle, one
                // per owner that emitted since the last flush.
                run {
                    val now = System.currentTimeMillis()
                    val period = com.jeanloickdt.automation.MessageUsageRepository.periodOf(now)
                    messageUsage.drain().forEach { (owner, delta) ->
                        messageUsageRepo.add(owner, period, delta)
                    }
                }

                // The signals' last values — one transaction for the whole
                // round instead of one per frame. This is what takes the write
                // lock off the relay's hot path; a setpoint does NOT come
                // through here, it is written through at the moment it is set.
                signalRows = signalRepository.flushPendingValues()

                // Presence, batched for the same reason as everything else in
                // this loop: a carrier hiccup that reconnects three thousand
                // boards must cost one round of writes, not six thousand.
                (presence as? com.jeanloickdt.relay.DbBackedPresenceStore)?.flushPending()


                // Le modèle signal — en parallèle, pas à la place. Seul le
                // palier minute s'écrit ici ; heure et jour sont dérivées
                // séparément, sur leur propre boucle plus lente — voir plus
                // bas.
                //
                // Pas de diffusion `bucket_updated` ici : l'app s'y abonne
                // toujours (`subscribe_history`), mais l'émission est partie
                // avec les agrégateurs widget. La remettre demande de la
                // reformuler sur les signaux — une décision, pas un oubli.
                signalHistoryRepository.insertMinuteBatch(
                    com.jeanloickdt.signal.data.SignalAggregators.minute
                        .extractClosedBuckets(System.currentTimeMillis())
                )
                rawRows = buffers.signalRawBuffer.drain()
                    .also { signalHistoryRepository.insertRawBatch(it) }.size
            } catch (e: Exception) {
                bgLog.error("History flush round failed — retrying in 5s", e)
            }
            val tookMs = (System.nanoTime() - startedAt) / 1_000_000
            round++
            val summary = "flush took ${tookMs}ms — signals=$signalRows raw=$rawRows"

            // A saturation nobody reports is worse than a low ceiling. The
            // queues refuse silently by design — this is the one place that
            // says it out loud, and it says it once per round rather than once
            // per refused frame.
            val refused = buffers.refusedTotal()
            if (refused > lastRefusedTotal) {
                bgLog.warn(
                    "Ingest queues FULL — ${refused - lastRefusedTotal} frame(s) refused this round " +
                        "(${refused} since boot) · ${buffers.pressure()}. The writer cannot keep up: " +
                        "check the flush duration above, the disk, and the frame rate of the boards."
                )
                lastRefusedTotal = refused
            }

            // La contre-pression se decide ICI, sur la duree du tour, et pas
            // sur une file pleine : quand le brut deborde, le mal est deja
            // fait — le meme tour peinait deja a ecrire les seaux minute.
            // Voir [IngestBackPressure] pour l'hysteresis.
            if (buffers.backPressure.record(tookMs, FLUSH_PERIOD_MS)) {
                if (buffers.backPressure.isRawSuspended) {
                    bgLog.warn(
                        "Contre-pression ENGAGEE — le palier brut est lache pour laisser " +
                            "passer les agregats. Les courbes restent completes ; le zoom fin " +
                            "des prochaines minutes sera vide. Cause : plusieurs tours de vidage " +
                            "d'affilee au-dela de leur periode de ${FLUSH_PERIOD_MS}ms."
                    )
                } else {
                    bgLog.info(
                        "Contre-pression relachee — le brut reprend " +
                            "(${buffers.backPressure.droppedRaw} echantillons ecartes depuis le boot)."
                    )
                }
            }

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
    // Le modèle signal — heure et jour, dérivées périodiquement
    //
    // Étape 3 du passage au modèle signal. Un seul job, identique sur les
    // deux moteurs : relit `signal_min`, regroupe par heure, réécrit
    // `signal_hour` — puis relit `signal_hour`, regroupe par jour, réécrit
    // `signal_day`. En CASCADE (heure → jour, jamais minute → jour
    // directement) : le jour n'a que 24 lignes à lire au lieu de 1440, et
    // c'est sûr parce que l'heure survit plus longtemps que la minute — elle
    // est encore là quand le jour se calcule.
    //
    // ⚠️ Pour qui câble la purge du palier minute (étape 6) : ce job doit
    // tourner AVANT elle. Une ligne minute supprimée avant d'avoir été
    // dérivée fait un trou définitif dans `signal_hour` — et comme la
    // minute sera plafonnée bien plus court que l'heure, le cas se
    // présenterait chaque jour, pas en cas rare. Le plus simple, quand la
    // purge signal existera : l'appeler juste après ce bloc, dans la même
    // boucle.
    //
    // Cinq minutes : correct à relancer (voir ExposedSignalHistoryRepository
    // .deriveTier — remplacement, pas fusion, donc une relance ne double
    // jamais un échantillon), pas encore réglé sur un vrai volume. Le
    // chiffre est une question ouverte du brief (§8), au même titre que
    // l'intervalle du flush d'ingestion.
    // ============================================================
    val SIGNAL_ROLLUP_PERIOD_MS = com.jeanloickdt.common.ServerConfig.historyRollupPeriodMs
    launch(com.jeanloickdt.common.ServerDispatchers.storage) {
        while (true) {
            delay(SIGNAL_ROLLUP_PERIOD_MS)
            try {
                val hourRows = signalHistoryRepository.deriveHour()
                val dayRows  = signalHistoryRepository.deriveDay()
                if (hourRows > 0 || dayRows > 0) {
                    bgLog.info("signal rollup — hour=$hourRows day=$dayRows bucket(s) written")
                }
            } catch (e: Exception) {
                bgLog.error("Signal rollup round failed — retrying in ${SIGNAL_ROLLUP_PERIOD_MS}ms", e)
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
    launch(com.jeanloickdt.common.ServerDispatchers.storage) {
        while (true) {
            delay(60.minutes)
            try {
                // La derivation ne reprend qu'une fenetre de seaux clos. Apres
                // une interruption plus longue que la fenetre, les minutes
                // d'avant ne seraient jamais derivees — et seraient supprimees
                // quelques lignes plus bas, laissant un trou definitif dans la
                // courbe heure. Vingt-quatre heures couvrent une nuit
                // d'astreinte.
                signalHistoryRepository.deriveHour(windowBuckets = 24)
                signalHistoryRepository.deriveDay(windowBuckets = 7)

                // La retention se lit dans `server.properties`, et nulle part
                // ailleurs : ce serveur ne vend pas de profondeur, il a un
                // disque. Le nuage passe ici un plan par compte ; le balayage
                // accepte la meme forme, avec zero exception.
                val now = System.currentTimeMillis()
                val jour = 24L * 60 * 60 * 1000
                fun coupe(jours: Int) = com.jeanloickdt.retention.RetentionSweep(
                    defaultCutoffMs = now - jours.toLong() * jour,
                    overrides = emptyList()
                )

                signalHistoryRepository.sweepRaw(coupe(com.jeanloickdt.common.ServerConfig.historyRetentionRawDays))
                signalHistoryRepository.sweepMinute(coupe(com.jeanloickdt.common.ServerConfig.historyRetentionMinDays))
                signalHistoryRepository.sweepHour(coupe(com.jeanloickdt.common.ServerConfig.historyRetentionHourDays))
                // `-1` vaut « illimite » : on ne balaie pas.
                if (com.jeanloickdt.common.ServerConfig.historyRetentionDayDays > 0) {
                    signalHistoryRepository.sweepDay(coupe(com.jeanloickdt.common.ServerConfig.historyRetentionDayDays))
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
    watchedSignals = { ref -> ruleCache.watches(ref) }
    val automationEngine = com.jeanloickdt.automation.AutomationEngine(
        eventSinks, ruleCache, pendingActions,
        com.jeanloickdt.automation.ExposedAutomationStateStore(), deviceRepository
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
    val staleSweeper = com.jeanloickdt.event.SignalStaleSweeper(lastValues, eventSinks)
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
        ownsDevice = { ownerId, deviceId -> deviceRepository.findById(ownerId, deviceId) != null },
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
    configureAppRelay(projectRepository, connections, controlEvents, signalRepository)

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
            userRepository, projectRepository, deviceRepository,
            signalRepository, signalHistoryRepository,
            connections, controlEvents
        )
        authRoutes(userRepository, projectRepository, deviceRepository, connections, tokenService, accountPurge)
        projectRoutes(
            projectRepository, deviceRepository,
            signalRepository, signalHistoryRepository,
            connections, controlEvents
        )
        deviceRoutes(deviceRepository, projectRepository, connections, controlEvents)
        emailConfigRoutes(userRepository, emailSender)
        // No policies: a self-hosted node's limit is its own disk, so the
        // default gate — which always allows — is the honest one. The cloud
        // edition passes a SignalPolicies with its quota there.
        signalRoutes(
            signals = signalRepository,
            devices = deviceRepository,
            sendToDevice = { deviceId, frame ->
                connections.deviceOutboxes[deviceId]?.send(frame, isStreaming = false) ?: false
            },
            broadcastToApps = { projectId, frame ->
                com.jeanloickdt.relay.broadcastToApps(connections, projectId, frame)
            },
            // Les fenetres : tout est servi, rien n'est borne. Ce serveur ne
            // vend pas de profondeur — voir [HistoryWindows.unlimited].
            historyWindows = { _, _ -> com.jeanloickdt.signal.HistoryWindows.unlimited() },
            readHistory = { signalId, ownerId, fromMs, toMs, resolution ->
                signalHistoryRepository.readForApi(signalId, ownerId, fromMs, toMs, resolution)
            }
        )
        automationHealthRoutes(userRepository, pendingActions, eventSinks, automationEngine)
        ruleRoutes(
            ruleCache, signalRepository, deviceRepository,
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
        // Les routes des widgets sont parties avec la table. Une adresse se
        // declare desormais dans `signalRoutes`, cable plus haut.

        // TODO: add the routes of new modules here
    }
}

// ============================================================
// Ce qui vivait ici
//
// Six fonctions de vidage : le tampon opaque, le tampon numerique, les
// dernieres valeurs, et les trois paliers d'agregats widget. Elles
// ecrivaient dans `widget_history` et ses quatre tables derivees.
//
// Le modele signal fait le meme travail depuis `signal/data` : le tampon
// brut, l'agregateur minute, et la derivation heure et jour. Les boucles
// de fond appellent directement le depot d'historique des signaux, sans
// intermediaire a maintenir ici.
// ============================================================
