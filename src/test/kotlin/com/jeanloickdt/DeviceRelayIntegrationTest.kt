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

import com.jeanloickdt.auth.configureAuth
import com.jeanloickdt.device.domain.DeviceConnectivity
import com.jeanloickdt.device.domain.DeviceType
import com.jeanloickdt.relay.FrameParser
import com.jeanloickdt.relay.configureAppRelay
import com.jeanloickdt.relay.startDeviceRelay
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.sql.selectAll
import org.mindrot.jbcrypt.BCrypt
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end integration test of the device TCP relay AS IT IS TODAY (blocking
 * java.net sockets). This is the REFERENCE NET for the non-blocking ktor-network
 * rewrite: it must pass on the current code, then pass UNCHANGED on the rewritten
 * code — proving identical externally-observable behaviour.
 *
 * Each test wires the real relay (real ServerSocket on a free port) + the real
 * app WebSocket relay, against an isolated temp DB. A fake ESP (raw TCP socket)
 * connects and a fake app (Ktor test WS client) observes the broadcast.
 *
 * Cases (the three that break silently if the rewrite is wrong):
 *  1. nominal      — connect → handshake → valid frame → app receives device_online + the frame
 *  2. fragmented   — the frame split across two TCP writes → still reassembled & broadcast
 *  3. timeout      — connect → handshake → send nothing → device_offline is broadcast
 */
class DeviceRelayIntegrationTest {

    /**
     * The presence store the last wireRelay built.
     *
     * Presence became lazy: RAM is authoritative, the table is its durable
     * mirror written by the flush loop. A test that asks the table therefore
     * asks the wrong witness — it would be measuring flush timing, not
     * presence.
     */
    private var lastPresence: com.jeanloickdt.relay.DbBackedPresenceStore? = null
    private var lastConnections: com.jeanloickdt.relay.ConnectionRegistry? = null

    private val deviceToken = "esp-token-abcdef-123456"
    private lateinit var jwt: String
    private lateinit var ownerId: String
    private lateinit var projectId: String
    private lateinit var deviceId: String
    /**
     * L'adresse du signal declare dans [setup]. Le modele widget adressait par
     * un nom de croquis (`"w1"`) ; le modele signal adresse par un entier, et
     * la cle que voient les regles et le cache est `"deviceId:adresse"` —
     * construite apres coup, une fois le device cree.
     */
    private val address = 0
    private lateinit var signalKey: String

    /**
     * Le chemin app→carte est inchange : le relais y decoupe un en-tete et ne
     * lit jamais le nom de la commande. Cet identifiant reste donc une chaine
     * opaque, sans rapport avec l'adressage des signaux.
     */
    private val controlId = "w1"

    /**
     * Combien de temps on attend un message qui DOIT arriver.
     *
     * Généreux, et c'est le point. Ces attentes ne mesurent pas une latence :
     * elles attendent une correction. Cinq secondes encodaient une hypothèse
     * sur la vitesse de la machine — vraie ici, fausse sur un exécuteur de CI
     * chargé, où « app to device » est tombé sur un `device_online` qui
     * arrivait juste après.
     *
     * Un test instable dans une barrière est pire que pas de test : on apprend
     * à relancer, et le jour où il attrape quelque chose, personne ne le croit.
     * Avec cette borne, seul un vrai defaut la franchit — il met simplement
     * plus longtemps à le dire.
     *
     * Les attentes qui mesurent VRAIMENT un délai — la détection d'une carte
     * muette — gardent leur propre valeur, courte et justifiée sur place.
     */
    private val ARRIVES: Long = 30_000

    private var drainedSamples = 0L

    /**
     * Combien d'échantillons ont atteint l'agrégat minute depuis le début du
     * test. Le palier opaque `widget_history` que ces tests comptaient avant a
     * disparu avec son modèle ; l'agrégat minute est ce qui le remplace.
     *
     * Le vidage est destructif — un appel emporte les seaux — donc on cumule
     * plutôt que de relire, ce qui permet aussi de sonder en boucle pendant
     * qu'une rafale est encore en cours d'absorption.
     */
    private fun mergedSamples(): Long {
        drainedSamples += com.jeanloickdt.signal.data.SignalAggregators.minute
            .extractAllBuckets().sumOf { it.sampleCount.toLong() }
        return drainedSamples
    }

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.fresh()
        val userId = userRepository.create("alice", BCrypt.hashpw("pw", BCrypt.gensalt()))
        jwt = com.jeanloickdt.auth.LocalTestAuth.token(userId, tokenVersion = 0)
        projectId = projectRepository.create(userId, "P").id
        deviceId = deviceRepository.create(
            name         = "esp1",
            projectId = projectId,
            ownerId = userId,
            tokenHash = FrameParser.hashDeviceToken(deviceToken),
            deviceType = DeviceType.ESP32,
            connectivity = DeviceConnectivity.WIFI
        ).id
        // Le relais ne sert que des SIGNAUX déclarés : une adresse inconnue
        // est refusée, avec un diagnostic. C'est la même règle stricte que
        // pour les widgets d'avant, portée par le modèle qui reste.
        signalRepository.create(userId, deviceId, address, "s0", "float", nowMs = 0L)
        signalKey = com.jeanloickdt.signal.signalKey(deviceId, address)
        ownerId = userId
        // L'agrégateur minute est un singleton du process de test, pas une
        // instance par classe : sans ce vidage, un test hérite des seaux du
        // précédent.
        com.jeanloickdt.signal.data.SignalAggregators.minute.extractAllBuckets()
        drainedSamples = 0L
    }

    @Test
    fun `nominal — handshake then a valid frame is broadcast to the app`() = testApplication {
        val tcpPort = reserveFreePort()
        wireRelay(tcpPort)
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/ws/app", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
            send(Frame.Text(projectId))
            send(Frame.Text("install-1"))
            awaitSubscribed(projectId)

            val frame = signalFrame(23.5f)
            Socket("localhost", awaitBoundPort(tcpPort)).use { esp ->
                esp.getOutputStream().apply {
                    write(handshake(deviceToken))
                    write(frame)
                    flush()
                }
                val (texts, binary) = collectUntilOnlineAndBinary()
                assertTrue(texts.any { it.contains("device_online") }, "app must receive device_online")
                assertTrue(binary != null, "app must receive the relayed binary frame")
                // Pas la trame recue : celle que le relais reconstruit avec la
                // carte dedans. Une carte n'envoie pas son identite — elle sait
                // qui elle est — mais une app en regarde plusieurs, et les
                // adresses sont par carte : sans cet ajout, le I0 de deux
                // cartes atterrirait sur le meme widget.
                assertContentEquals(
                    com.jeanloickdt.signal.SignalFrame.forApps(frame, deviceId)!!, binary!!
                )
            }
        }
    }

    // ── les producteurs d'événements, bout en bout ────────────────────────

    @Test
    fun `presence and watched values reach the rule sinks`() = testApplication {
        val tcpPort = reserveFreePort()
        val sinks = com.jeanloickdt.event.EventSinks()
        wireRelay(tcpPort, sinks = sinks, watched = { it.key == signalKey })

        val ws = createClient { install(WebSockets) }
        ws.webSocket("/ws/app", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
            send(Frame.Text(projectId))
            send(Frame.Text("install-1"))
            awaitSubscribed(projectId)
            Socket("localhost", awaitBoundPort(tcpPort)).use { esp ->
                esp.getOutputStream().apply {
                    write(handshake(deviceToken))
                    write(signalFrame(42.5f))
                    flush()
                }
                collectUntilOnlineAndBinary()
            }
        }
        // The socket closed with the use{} block — the offline path runs in the
        // connection's finally, so poll while accumulating everything drained.
        val discrete = mutableListOf<com.jeanloickdt.event.RelayEvent>()
        var tries = 0
        while (tries++ < 50 && discrete.none { it is com.jeanloickdt.event.RelayEvent.DeviceOffline }) {
            while (true) discrete.add(sinks.discrete.tryReceive().getOrNull() ?: break)
            if (discrete.none { it is com.jeanloickdt.event.RelayEvent.DeviceOffline }) kotlinx.coroutines.delay(100)
        }
        val online  = discrete.filterIsInstance<com.jeanloickdt.event.RelayEvent.DeviceOnline>().singleOrNull()
        val offline = discrete.filterIsInstance<com.jeanloickdt.event.RelayEvent.DeviceOffline>().singleOrNull()
        assertTrue(online != null, "DeviceOnline must reach the discrete sink")
        assertTrue(offline != null, "DeviceOffline must reach the discrete sink")
        assertEquals(ownerId, online.ownerId)
        assertEquals("disconnected", offline.reason)

        // And the WATCHED widget's value crossed the lossy sink.
        val value = sinks.values.tryReceive().getOrNull() as? com.jeanloickdt.event.RelayEvent.SignalValue
        assertTrue(value != null, "a watched widget's sample must be published")
        assertEquals(42.5, value.value, 0.001)
        assertEquals(ownerId, value.ownerId)
    }

    @Test
    fun `an unwatched widget publishes nothing — the producer-side gate`() = testApplication {
        val tcpPort = reserveFreePort()
        val sinks = com.jeanloickdt.event.EventSinks()
        wireRelay(tcpPort, sinks = sinks, watched = { false })

        val ws = createClient { install(WebSockets) }
        ws.webSocket("/ws/app", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
            send(Frame.Text(projectId))
            send(Frame.Text("install-1"))
            awaitSubscribed(projectId)
            Socket("localhost", awaitBoundPort(tcpPort)).use { esp ->
                esp.getOutputStream().apply {
                    write(handshake(deviceToken))
                    write(signalFrame(1f))
                    flush()
                }
                collectUntilOnlineAndBinary()
            }
        }

        assertTrue(sinks.values.tryReceive().getOrNull() == null,
            "with no rule watching, the hot path must publish no value at all")
    }

    // ── le moteur de règles, bout en bout ─────────────────────────────────

    @Test
    fun `a rule fires from a real TCP frame into pending_actions`() = testApplication {
        // The acceptance criterion of étape 5, mirror of étape 4's sqlite3
        // test: a rule INSERTED IN SQL (the REST API is étape 9), a frame over
        // the real socket, and a durable action row at the other end. The full
        // path: parse → strict guard → producer gate → sink → engine → enqueue.
        org.jetbrains.exposed.sql.transactions.transaction {
            exec("""INSERT INTO automation_rules
                (id, owner_id, name, enabled, trigger_kind, trigger_signal_key, definition, created_at, updated_at)
                VALUES ('r-e2e', '$ownerId', 'e2e', true, 'value', '$signalKey',
                '{"when":{"kind":"value","above":20.0},"cooldownS":0,"actions":[{"type":"PUSH","title":"seuil","body":"{{value}}"}]}', 0, 0)""")
        }
        val cache = com.jeanloickdt.automation.RuleCache().apply { reload() }
        val sinks = com.jeanloickdt.event.EventSinks()
        val engine = com.jeanloickdt.automation.AutomationEngine(
            sinks, cache, com.jeanloickdt.automation.ExposedPendingActionRepository(),
            com.jeanloickdt.automation.ExposedAutomationStateStore(), deviceRepository
        )
        val tcpPort = reserveFreePort()
        wireRelay(tcpPort, sinks = sinks, watched = cache::watches)

        val ws = createClient { install(WebSockets) }
        ws.webSocket("/ws/app", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
            send(Frame.Text(projectId))
            send(Frame.Text("install-1"))
            awaitSubscribed(projectId)
            Socket("localhost", awaitBoundPort(tcpPort)).use { esp ->
                esp.getOutputStream().apply {
                    write(handshake(deviceToken))
                    write(signalFrame(42.5f))
                    flush()
                }
                collectUntilOnlineAndBinary()
            }
        }

        // Drain the sink into the engine deterministically (the loop coroutine
        // is production wiring; the test drives the same calls by hand).
        while (true) {
            val e = sinks.values.tryReceive().getOrNull() ?: break
            engine.handle(e)
        }

        val rows = org.jetbrains.exposed.sql.transactions.transaction {
            com.jeanloickdt.automation.data.PendingActionTable.selectAll()
                .map { it[com.jeanloickdt.automation.data.PendingActionTable.type] to
                       it[com.jeanloickdt.automation.data.PendingActionTable.payload] }
        }
        assertEquals(1, rows.size, "the frame crossed the threshold — one durable action expected")
        assertEquals("PUSH", rows.single().first)
        assertTrue("42.5" in rows.single().second, "the value must be rendered into the payload")
    }

    // ── la course du zombie — nettoyage périmé sur deviceSessions ─────────    // ── la course du zombie — nettoyage périmé sur deviceSessions ─────────

    @Test
    fun `a supplanted connection cleans nothing — commands reach the new socket`() = testApplication {
        // The production race, made deterministic: registering B closes A's
        // socket, so A's cleanup runs NOW instead of at a 90 s timeout — and
        // must fail the ownership check. Before the fix this test fails
        // systematically: A's finally removed B's session, telemetry kept
        // flowing, and every command died with DEVICE_OFFLINE for hours.
        val tcpPort = reserveFreePort()
        val sinks = com.jeanloickdt.event.EventSinks()
        wireRelay(tcpPort, sinks = sinks)
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/ws/app", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
            send(Frame.Text(projectId))
            send(Frame.Text("install-1"))
            awaitSubscribed(projectId)

            // ── Connexion A ──
            val espA = Socket("localhost", awaitBoundPort(tcpPort))
            espA.getOutputStream().apply { write(handshake(deviceToken)); flush() }
            val seen = mutableListOf<String>()
            withTimeoutOrNull(ARRIVES) {
                while (seen.none { it.contains("device_online") }) {
                    (incoming.receive() as? Frame.Text)?.let { seen += it.readText() }
                }
            }
            assertTrue(seen.any { it.contains("device_online") }, "A must come online")

            // ── Connexion B, MÊME token → A est supplantée et sa socket fermée ──
            Socket("localhost", awaitBoundPort(tcpPort)).use { espB ->
                espB.soTimeout = ARRIVES.toInt()
                espB.getOutputStream().apply { write(handshake(deviceToken)); flush() }

                // Wait until A's reader has died and run its (stale) cleanup —
                // its socket was closed by B's registration.
                withTimeoutOrNull(ARRIVES) {
                    while (espA.inputStream.read() != -1) { /* drain until EOF */ }
                }

                // The app must have seen NO device_offline: A's cleanup owns
                // nothing anymore. (Drain whatever arrived without blocking.)
                while (true) {
                    val f = withTimeoutOrNull(300) { incoming.receive() } ?: break
                    (f as? Frame.Text)?.let { seen += it.readText() }
                }
                assertTrue(seen.none { it.contains("device_offline") },
                    "the stale cleanup must not broadcast a red dot for a live board")

                // No false DeviceOffline into the RULE feed either — once rules
                // exist, that lie would become a push notification.
                val ruleEvents = buildList {
                    while (true) add(sinks.discrete.tryReceive().getOrNull() ?: break)
                }
                assertTrue(ruleEvents.none { it is com.jeanloickdt.event.RelayEvent.DeviceOffline },
                    "the stale cleanup must not feed a false DeviceOffline to the engine")

                // Presence still says online — the admin panel shows green.
                // Asked of the RAM store, which is authoritative since presence
                // stopped writing on every transition; the table catches up on
                // the next flush and would only measure that timing here.
                assertTrue(lastPresence!!.isOnline(deviceId),
                    "presence must still be online after the stale cleanup")

                // And THE symptom: a command from the app reaches B's socket.
                val payload = floatLE(0.5f)
                send(Frame.Binary(true, appCommandFrame(listOf(deviceId), controlId, TYPE_HSLIDER, EV_SETVALUE, payload)))
                val expected = deviceFrame(controlId, TYPE_HSLIDER, EV_SETVALUE, payload)
                val received = readExactly(espB.getInputStream(), expected.size)
                assertContentEquals(expected, received)
            }
            runCatching { espA.close() }
        }
    }

    @Test
    fun `une ecriture de signal emprunte le socket deja ouvert`() = testApplication {
        // L'app tient un WebSocket ouvert et authentifie vers le relais, et il
        // sait deja relayer des trames vers les cartes. L'ecriture d'un signal
        // etait pourtant la seule commande a partir en PUT HTTPS — avec, avant
        // chaque geste, une lecture en base locale et une verification de
        // jeton. Vingt fois par seconde quand on fait glisser un curseur.
        //
        // Sur le socket, il reste ce qui compte : une trame.
        val tcpPort = reserveFreePort()
        wireRelay(tcpPort)
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/ws/app", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
            send(Frame.Text(projectId))
            send(Frame.Text("install-1"))
            awaitSubscribed(projectId)

            Socket("localhost", awaitBoundPort(tcpPort)).use { esp ->
                esp.soTimeout = ARRIVES.toInt()
                esp.getOutputStream().apply { write(handshake(deviceToken)); flush() }
                withTimeoutOrNull(ARRIVES) {
                    var vu = false
                    while (!vu) {
                        (incoming.receive() as? Frame.Text)?.let { vu = it.readText().contains("device_online") }
                    }
                }

                send(Frame.Text(
                    """{"type":"write_signal","deviceId":"$deviceId","address":$address,"value":21.5}"""
                ))

                val attendu = signalFrame(21.5f)
                val recu = readExactly(esp.getInputStream(), attendu.size)
                assertContentEquals(attendu, recu)
            }
        }
    }

    // ── le fusible de débit, bout en bout ─────────────────────────────────

    @Test
    fun `a flooding board is throttled at the burst, not buffered without bound`() = testApplication {
        val tcpPort = reserveFreePort()
        val buffers = com.jeanloickdt.relay.HistoryBuffers()
        wireRelay(tcpPort, buffers = buffers)

        val ws = createClient { install(WebSockets) }
        ws.webSocket("/ws/app", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
            send(Frame.Text(projectId))
            send(Frame.Text("install-1"))
            awaitSubscribed(projectId)
            Socket("localhost", awaitBoundPort(tcpPort)).use { esp ->
                val out = esp.getOutputStream()
                out.write(handshake(deviceToken))
                out.flush()
                // A loop() without delay(): 300 valid frames as fast as the
                // socket accepts them. The fuse is 10/s with a burst of 20
                // since the fair-use review — a runaway sketch is caught just
                // as surely, and legitimate traffic no longer brushes it.
                repeat(300) {
                    out.write(signalFrame(it.toFloat()))
                }
                out.flush()
                collectUntilOnlineAndBinary()
                // The server is still draining the flood from its receive
                // buffer — closing now would measure a race, not the fuse.
                // Wait until the count stops moving.
                var previous = -1L
                while (mergedSamples() != previous) {
                    previous = mergedSamples()
                    kotlinx.coroutines.delay(200)
                }
            }
        }

        val stored = mergedSamples()
        // Les bornes se derivent du fusible, jamais d'un nombre ecrit ici :
        // ce serveur laisse passer 50 trames/s et une rafale du double, la ou
        // le nuage serre a 10 par sa grille de prix. Un test qui codait 20 en
        // dur mesurait l'autre edition.
        val rafale = com.jeanloickdt.relay.FrameRateLimiter.DEFAULT_RATE_PER_SECOND * 2
        assertTrue(stored >= rafale / 2, "la rafale elle-meme doit passer — seulement $stored stockees")
        assertTrue(
            stored <= rafale + com.jeanloickdt.relay.FrameRateLimiter.DEFAULT_RATE_PER_SECOND,
            "le flot doit etre coupe pres de la rafale — $stored stockees sur 300"
        )
    }

    // ── Ce qui vivait ici ─────────────────────────────────────────────────
    //
    // Deux tests du palier brut vendu : « a paid plan stores the raw sample »
    // et « the free plan relays and aggregates, but stores no raw row », plus
    // la grille de prix qu'ils montaient. Ils eprouvaient une DECISION DE
    // VENTE — qui paie garde la seconde par seconde — et ce serveur ne vend
    // rien : le palier brut y est l'interrupteur de l'exploitant,
    // `history.raw.enabled`, et personne d'autre ne le refuse.

    @Test
    fun `strict model — a frame for an UNDECLARED widget is dropped, not broadcast`() = testApplication {
        val tcpPort = reserveFreePort()
        wireRelay(tcpPort)
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/ws/app", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
            send(Frame.Text(projectId))
            send(Frame.Text("install-1"))
            awaitSubscribed(projectId)

            // a frame addressed to a widget the app never declared (not in the DB,
            // not in knownWidgetIds) — noise the strict model must drop.
            val ghostFrame = deviceFrame("ghost-undeclared", TYPE_GAUGE, EV_SETVALUE, floatLE(7.0f))
            Socket("localhost", awaitBoundPort(tcpPort)).use { esp ->
                esp.getOutputStream().apply {
                    write(handshake(deviceToken))
                    write(ghostFrame)
                    flush()
                }
                // Bounded collection: device_online still arrives (the device
                // connected fine), but the undeclared frame must NOT be relayed.
                val texts = mutableListOf<String>()
                var binary: ByteArray? = null
                withTimeoutOrNull(ARRIVES) {
                    while (true) {
                        when (val f = incoming.receive()) {
                            is Frame.Text -> texts += f.readText()
                            is Frame.Binary -> binary = f.readBytes()
                            else -> {}
                        }
                    }
                }
                assertTrue(texts.any { it.contains("device_online") }, "the device still connects")
                assertTrue(binary == null, "a frame for an undeclared widget must be dropped, never broadcast")
            }
        }
    }

    @Test
    fun `fragmented — a frame split across two TCP writes is reassembled and broadcast`() = testApplication {
        val tcpPort = reserveFreePort()
        wireRelay(tcpPort)
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/ws/app", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
            send(Frame.Text(projectId))
            send(Frame.Text("install-1"))
            awaitSubscribed(projectId)

            val frame = signalFrame(42.0f)
            Socket("localhost", awaitBoundPort(tcpPort)).use { esp ->
                val out = esp.getOutputStream()
                out.write(handshake(deviceToken)); out.flush()
                // split the frame: 4-byte header first, pause, then body+CRC
                out.write(frame, 0, 4); out.flush()
                Thread.sleep(150)
                out.write(frame, 4, frame.size - 4); out.flush()

                val (texts, binary) = collectUntilOnlineAndBinary()
                assertTrue(texts.any { it.contains("device_online") })
                assertContentEquals(
                    com.jeanloickdt.signal.SignalFrame.forApps(frame, deviceId)!!, binary!!
                )
            }
        }
    }

    @Test
    fun `timeout — a silent device after handshake is detected offline`() = testApplication {
        val tcpPort = reserveFreePort()
        wireRelay(tcpPort)
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/ws/app", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
            send(Frame.Text(projectId))
            send(Frame.Text("install-1"))
            awaitSubscribed(projectId)

            // heartbeat=800ms → soTimeout = 800*2.5 = 2000ms (the min clamp). Then send NOTHING.
            Socket("localhost", awaitBoundPort(tcpPort)).use { esp ->
                esp.getOutputStream().apply { write(handshake("$deviceToken:800")); flush() }

                val texts = mutableListOf<String>()
                withTimeoutOrNull(8000) {
                    while (texts.none { it.contains("device_offline") }) {
                        when (val f = incoming.receive()) {
                            is Frame.Text -> texts += f.readText()
                            else -> {}
                        }
                    }
                }
                assertTrue(texts.any { it.contains("device_online") }, "should have come online first")
                assertTrue(texts.any { it.contains("device_offline") }, "silent device must be detected offline")
            }
        }
    }

    @Test
    fun `app to device — a binary command is trimmed and delivered to the device socket`() = testApplication {
        val tcpPort = reserveFreePort()
        wireRelay(tcpPort)
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/ws/app", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
            send(Frame.Text(projectId))
            send(Frame.Text("install-1"))
            awaitSubscribed(projectId)

            Socket("localhost", awaitBoundPort(tcpPort)).use { esp ->
                esp.soTimeout = ARRIVES.toInt()
                // ESP authenticates → registered + outbox created (online)
                esp.getOutputStream().apply { write(handshake(deviceToken)); flush() }
                // wait for device_online so we know the device is registered before we command it
                val online = mutableListOf<String>()
                withTimeoutOrNull(ARRIVES) {
                    while (online.none { it.contains("device_online") }) {
                        (incoming.receive() as? Frame.Text)?.let { online += it.readText() }
                    }
                }
                assertTrue(online.any { it.contains("device_online") }, "device must come online first")

                // the app sends a DISCRETE command (HSlider SetValue) targeting the device UUID.
                // DEV_COUNT=1 with the device id; never dropped (non-streaming → suspending send).
                val payload = floatLE(0.5f)
                val appFrame = appCommandFrame(listOf(deviceId), controlId, TYPE_HSLIDER, EV_SETVALUE, payload)
                send(Frame.Binary(true, appFrame))

                // the ESP must receive the SAME frame trimmed to DEV_COUNT=0 (LEN+CRC recomputed),
                // which is byte-identical to the device-direction frame for the same widget/payload.
                val expected = deviceFrame(controlId, TYPE_HSLIDER, EV_SETVALUE, payload)
                val received = readExactly(esp.getInputStream(), expected.size)
                assertEquals(0, received[4].toInt(), "DEV_COUNT byte must be 0 (header trimmed)")
                assertContentEquals(expected, received)
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────

    /** Reads exactly [n] bytes from a blocking InputStream (or fails on timeout/EOF). */
    private fun readExactly(input: java.io.InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(out, read, n - read)
            if (r == -1) error("device socket closed after $read/$n bytes")
            read += r
        }
        return out
    }

    /** App→device command frame: AA|VER|LEN|DEV_COUNT(N)|[DEV_LEN|DEV_ID]xN|WID_LEN|WID|TYPE|EVENT|PAYLOAD|CRC8 */
    private fun appCommandFrame(deviceIds: List<String>, widgetId: String, type: Int, event: Int, payload: ByteArray): ByteArray {
        var dev = byteArrayOf(deviceIds.size.toByte())
        for (d in deviceIds) {
            val db = d.toByteArray()
            dev = dev + byteArrayOf(db.size.toByte()) + db
        }
        val wid = widgetId.toByteArray()
        val body = dev +
            byteArrayOf(wid.size.toByte()) + wid +
            byteArrayOf(type.toByte(), event.toByte()) + payload
        val len = body.size
        return byteArrayOf(0xAA.toByte(), 0x01, (len and 0xFF).toByte(), ((len ushr 8) and 0xFF).toByte()) +
            body + byteArrayOf(crc8(body))
    }

    /**
     * The ONLY place relay wiring lives. The 3 test assertions are the
     * behavioural contract and never change — only this wiring evolves.
     * With DI, every test builds its OWN SessionRegistry + broadcaster:
     * no shared global state, so no cross-test cleanup is needed.
     */
    private fun io.ktor.server.testing.ApplicationTestBuilder.wireRelay(
        tcpPort: Int,
        buffers: com.jeanloickdt.relay.HistoryBuffers = com.jeanloickdt.relay.HistoryBuffers(),
        sinks: com.jeanloickdt.event.EventSinks? = null,
        watched: (com.jeanloickdt.relay.SignalRef) -> Boolean = { false }
    ) {
        val connections = com.jeanloickdt.relay.ConnectionRegistry()
        val lastValues  = com.jeanloickdt.relay.InMemoryLastValueCache()
        val presence    = com.jeanloickdt.relay.DbBackedPresenceStore(deviceRepository as com.jeanloickdt.device.domain.DevicePresenceWriter)
        lastPresence = presence
        lastConnections = connections
        val events      = com.jeanloickdt.relay.ControlEventBroadcaster(connections)
        application {
            configureAuth(userRepository, com.jeanloickdt.auth.LocalTestAuth.service)
            // Le meme cablage qu'en production : sans le depot des signaux,
            // le relais laisse passer sans controle et l'ecriture par le
            // socket n'a rien a resoudre.
            configureAppRelay(projectRepository, connections, events, signalRepository)
            startDeviceRelay(
                deviceRepository,
                connections, buffers, lastValues, presence, events,
                sinks = sinks,
                watchedSignals = watched,
                signals = signalRepository,
                tcpPort = tcpPort
            )
        }
    }


    /**
     * Attend que l'abonnement de l'app soit REELLEMENT enregistre.
     *
     * `send(Frame.Text(projectId))` ne fait que mettre le message en file : il
     * rend la main avant que le serveur l'ait traite. Brancher la carte tout
     * de suite diffuse alors `device_online` a zero session, et le test attend
     * un evenement qui ne viendra jamais.
     *
     * Sur cette machine l'ordre tombait juste. Sur un executeur de CI charge,
     * non — trois tests sont tombes la-dessus, en accusant un delai trop court
     * alors que rien n'aurait suffi.
     *
     * On attend donc l'ETAT, pas une duree.
     */
    private suspend fun awaitSubscribed(projectId: String) {
        withTimeoutOrNull(ARRIVES) {
            while (lastConnections?.getAppSessionsForProject(projectId).isNullOrEmpty()) {
                kotlinx.coroutines.delay(10)
            }
        } ?: error("l'app ne s'est jamais abonnee au projet $projectId")
    }

    /** Collects WS frames until both a device_online text and a binary frame are seen (or timeout). */
    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.collectUntilOnlineAndBinary(
        timeoutMs: Long = 30_000
    ): Pair<List<String>, ByteArray?> {
        val texts = mutableListOf<String>()
        var binary: ByteArray? = null
        withTimeoutOrNull(timeoutMs) {
            while (binary == null || texts.none { it.contains("device_online") }) {
                when (val f = incoming.receive()) {
                    is Frame.Text -> texts += f.readText()
                    is Frame.Binary -> binary = f.readBytes()
                    else -> {}
                }
            }
        }
        return texts to binary
    }

    /** Handshake payload framed as [1-byte length][UTF-8 payload]. */
    private fun handshake(payload: String): ByteArray {
        val bytes = payload.toByteArray()
        return byteArrayOf(bytes.size.toByte()) + bytes
    }

    /** Polls until the relay's ServerSocket is bound (it binds asynchronously after app start). */
    private fun awaitBoundPort(port: Int, timeoutMs: Long = 4000): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try { Socket("localhost", port).close(); return port } catch (_: Exception) { Thread.sleep(50) }
        }
        error("device relay never bound on port $port")
    }

    private fun reserveFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) =
        assertTrue(expected.contentEquals(actual), "broadcast frame differs from the sent frame")

    // ── iWidgets v1 frame builders (mirror of FrameParser's wire format) ──
    private val TYPE_GAUGE = 0x03
    private val TYPE_HSLIDER = 0x0A
    private val EV_SETVALUE = 0x01

    private fun crc8(data: ByteArray): Byte {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return (crc and 0xFF).toByte()
    }

    private fun floatLE(f: Float): ByteArray {
        val bits = f.toRawBits()
        return byteArrayOf(
            (bits and 0xFF).toByte(), ((bits ushr 8) and 0xFF).toByte(),
            ((bits ushr 16) and 0xFF).toByte(), ((bits ushr 24) and 0xFF).toByte()
        )
    }

    /** Device→server frame: AA|VER|LEN(LE)| DEV_COUNT(0)|WID_LEN|WID|TYPE|EVENT|PAYLOAD |CRC8 */
    /** Une trame SIGNAL flottante sur [address], telle qu'une carte l'emet. */
    private fun signalFrame(v: Float): ByteArray = com.jeanloickdt.signal.SignalFrame.build(
        address, com.jeanloickdt.signal.SignalFrame.TAG_FLOAT,
        com.jeanloickdt.signal.SignalFrame.floatBytes(v)
    )

    private fun deviceFrame(widgetId: String, type: Int, event: Int, payload: ByteArray): ByteArray {
        val wid = widgetId.toByteArray()
        val body = byteArrayOf(0x00) +
            byteArrayOf(wid.size.toByte()) + wid +
            byteArrayOf(type.toByte(), event.toByte()) + payload
        val len = body.size
        return byteArrayOf(0xAA.toByte(), 0x01, (len and 0xFF).toByte(), ((len ushr 8) and 0xFF).toByte()) +
            body + byteArrayOf(crc8(body))
    }
}
