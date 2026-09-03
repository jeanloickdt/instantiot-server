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

package com.jeanloickdt.relay

import com.jeanloickdt.project.domain.ProjectRepository
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("AppRelay")

/**
 * Inbound app → server message to subscribe to the bucket_updated of a set
 * of widgets. The app sends the COMPLETE set on each change (chart
 * mount/dispose, bottom sheet open/close, "Enable history" toggle). No
 * delta — the server replaces the set on each message received.
 *
 * Example :
 * ```json
 * {"type":"subscribe_history","widgets":[
 *   {"widgetId":"gauge1","granularity":"minute"},
 *   {"widgetId":"level1","granularity":"minute"}
 * ]}
 * ```
 *
 * Empty array = unsubscribe from everything (= the server no longer emits
 * bucket_updated to this session).
 */
@Serializable
private data class AppInboundMessage(
    val type: String,
    val widgets: List<HistorySubscriptionDto>? = null,
    // ── write_signal ──────────────────────────────────────────────────
    val deviceId: String? = null,
    val address: Int? = null,
    val value: Double? = null,
    val text: String? = null
)

@Serializable
private data class HistorySubscriptionDto(
    val widgetId: String,
    val granularity: String   // "minute" | "hour" | "day"
)

private val appInboundJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * WebSocket relay — Android app connections.
 *
 * Protocol :
 *   1. App connects → JWT verified
 *   2. First text message = projectId → activeProjectId registered
 *   3. All subsequent messages = iWidgets v1 binary frames
 *   4. App disconnects → session removed
 *
 * Each app connection runs in its own Ktor coroutine — non-blocking.
 * 50 connected apps = 50 lightweight coroutines.
 */
fun Application.configureAppRelay(
    projectRepository: ProjectRepository,
    connections: ConnectionRegistry,
    events: ControlEventBroadcaster,
    /**
     * Le dépôt des adresses déclarées. `null` laisse passer sans contrôle —
     * un nœud sans dépôt de signaux n'a pas à devenir muet pour autant.
     */
    signals: com.jeanloickdt.signal.domain.SignalRepository? = null
) {

    install(WebSockets) {
        pingPeriod = 15.seconds  // Ktor handles ping/pong automatically
        timeout    = 30.seconds  // closes if no response after 30s
        maxFrameSize = 8192  // 8 KB — largely sufficient for iWidgets v1 frames
    }

    routing {
        authenticate("jwt") {

            webSocket("/ws/app") {

                // retrieve the userId from the JWT
                val userId = call.principal<JWTPrincipal>()?.subject
                if (userId == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                    return@webSocket
                }

                // handshake — first message = projectId
                val handshakeFrame = incoming.receive()
                if (handshakeFrame !is Frame.Text) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Expected projectId as first message"))
                    return@webSocket
                }

                val projectId = handshakeFrame.readText()
                if (projectId.isBlank()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "projectId cannot be blank"))
                    return@webSocket
                }

                // 2nd message = connectionInstanceId (UUID v4 per install).
                // Enables fine-grained dedup on (userId, projectId, instanceId)
                // instead of (userId, projectId) — several devices of the same
                // user can watch the same project in parallel, only the same
                // install reconnecting kicks its own zombie.
                val instanceFrame = incoming.receive()
                if (instanceFrame !is Frame.Text) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Expected connectionInstanceId as second message"))
                    return@webSocket
                }
                val connectionInstanceId = instanceFrame.readText()
                if (connectionInstanceId.isBlank()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "connectionInstanceId cannot be blank"))
                    return@webSocket
                }

                // L'appartenance est dans la signature : `findById` ne peut
                // resoudre que ce qui est deja au bon compte.
                if (projectRepository.findById(userId, projectId) == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Project not found"))
                    return@webSocket
                }

                // Dedup by (userId, projectId, connectionInstanceId) : we
                // only kick the previous sessions of the **same install**.
                // Different devices (phone A + phone B of the same user)
                // coexist on the same project.
                //
                // Typical kick case : the app was in the background, the OS
                // froze the TCP socket ; when it returns to the foreground
                // it opens a new WS with the same instanceId → the old
                // ghost is closed immediately (instead of waiting ~25s for
                // the ping timeout).
                val priorSessions = connections.appSessions[userId]
                    ?.filter {
                        it.activeProjectId == projectId &&
                        it.connectionInstanceId == connectionInstanceId
                    }
                    .orEmpty()
                priorSessions.forEach { prior ->
                    try {
                        prior.session.close(
                            CloseReason(CloseReason.Codes.NORMAL, "Superseded by new session")
                        )
                    } catch (_: Exception) { /* already closed — does not matter */ }
                    connections.unregisterApp(userId, prior.session)
                }
                if (priorSessions.isNotEmpty()) {
                    logger.info("Closed ${priorSessions.size} prior session(s) — userId=$userId projectId=$projectId instanceId=${connectionInstanceId.take(8)}…")
                }

                // register the app session with the active project
                val appSession = connections.registerApp(userId, this, connectionInstanceId)
                connections.setActiveProject(appSession, projectId)
                logger.info("App connected — userId=$userId projectId=$projectId instanceId=${connectionInstanceId.take(8)}…")

                // Le relais DIT ce qu'il sait faire — et repond aussi quand
                // on le lui demande, parce que cette annonce-ci peut se
                // perdre : elle part a l'instant de l'enregistrement, et le
                // flux des messages entrants cote app n'a aucun replay. Un
                // collecteur qui s'attache une milliseconde trop tard ne la
                // verra jamais. C'est `hello` qui rattrape.
                //
                // Sans ca, une app plus recente enverrait `write_signal` a un
                // relais qui l'ignore — et croirait avoir envoye, puisque les
                // octets sont bien partis. Chaque geste disparaitrait en
                // silence, sur les seuls relais pas encore mis a jour.
                //
                // Le decalage est l'etat NORMAL du systeme : l'app se met a
                // jour quand son porteur le decide, le relais quand on le
                // deploie. Un relais muet est donc un relais ancien, et l'app
                // garde son chemin HTTP — la regle est lisible des deux cotes.
                send(Frame.Text("""{"type":"capabilities","accepts":["write_signal"]}"""))

                try {
                    // The dispatch is **sequential** (no `launch` per frame) :
                    // each device has its `DeviceOutbox` which serializes the
                    // TCP writes + applies backpressure. Launching a coroutine
                    // per frame would break that backpressure.
                    //
                    // Two types of frames accepted after the handshake :
                    //   - Frame.Binary : iWidgets v1 frames (app → device commands)
                    //   - Frame.Text   : control messages (subscribe_history, ...)
                    for (incomingFrame in incoming) {

                        when (incomingFrame) {
                            is Frame.Binary -> {
                                val frameBytes = incomingFrame.data
                                if (!FrameParser.isValid(frameBytes)) {
                                    logger.warn("Invalid frame received from app userId=$userId — ignored")
                                    continue
                                }
                                relayFrameToDevices(this@webSocket, userId, frameBytes, connections, events)
                            }
                            is Frame.Text -> {
                                handleAppTextMessage(
                                    appSession, incomingFrame.readText(),
                                    userId, connections, events, signals
                                )
                            }
                            else -> { /* ping/pong/close handled by Ktor */ }
                        }
                    }
                } finally {
                    // disconnection — remove this specific session
                    connections.unregisterApp(userId, this@webSocket)
                    logger.info("App disconnected — userId=$userId")
                }
            }
        }
    }
}

/**
 * Parse and apply an inbound text message from the app.
 *
 * Two types : `subscribe_history`, et `write_signal`.
 *
 * ## Pourquoi l'ecriture est passee par ici
 *
 * Elle partait en PUT HTTPS. Avant chaque geste : une lecture en base locale
 * cote app, une verification de jeton, puis un aller-retour complet vers le
 * relais — vingt fois par seconde quand on fait glisser un curseur. Pendant
 * ce temps, ce socket-ci etait deja ouvert, deja authentifie, et relayait
 * deja des trames vers les cartes.
 *
 * Ce qui ne change pas : c'est le meme [SignalSetpoint.write] qui traite le
 * message. Rangement avant envoi, diffusion aux autres app, rejeu a la
 * reconnexion — un chemin plus court, pas des regles plus laches.
 */
private suspend fun handleAppTextMessage(
    appSession: AppSession,
    text: String,
    userId: String,
    connections: ConnectionRegistry,
    events: ControlEventBroadcaster,
    signals: com.jeanloickdt.signal.domain.SignalRepository?
) {
    val msg = try {
        appInboundJson.decodeFromString<AppInboundMessage>(text)
    } catch (e: SerializationException) {
        logger.warn("Invalid inbound text from app userId=${appSession.userId}: ${e.message}")
        return
    }

    when (msg.type) {
        "subscribe_history" -> {
            val newSubs = msg.widgets.orEmpty()
                .associate { it.widgetId to it.granularity }
            appSession.historySubs.clear()
            appSession.historySubs.putAll(newSubs)
            logger.info("History subscriptions updated — userId=${appSession.userId} count=${newSubs.size}")
        }
        "hello" -> {
            // L'app demande, le relais repond. C'est le seul ordre sans
            // course : elle envoie quand elle ecoute deja.
            appSession.session.send(
                Frame.Text("""{"type":"capabilities","accepts":["write_signal"]}""")
            )
        }
        "write_signal" -> {
            val deviceId = msg.deviceId
            val address = msg.address
            if (deviceId == null || address == null || signals == null) {
                logger.warn("write_signal incomplet — userId=$userId deviceId=$deviceId address=$address")
                return
            }

            // La propriete est prouvee par la RECHERCHE elle-meme.
            //
            // `signals.find(ownerId, deviceId, address)` est cadre par le
            // proprietaire : une adresse qui n'est pas a nous ne se trouve
            // pas. Il n'y a donc rien a verifier en plus, et surtout rien a
            // relire en base sur un chemin parcouru vingt fois par seconde.
            //
            // Et la carte n'a PAS besoin d'etre en ligne. Une premiere
            // version repondait `device_offline` et s'arretait la — elle
            // cassait la promesse du chemin qu'elle remplacait : range
            // d'abord, envoye ensuite, pour qu'une carte endormie le retrouve
            // en se reconnectant. `SignalSetpoint.write` s'en charge : si
            // l'envoi echoue, la consigne est deja ecrite.
            val projectId = connections.deviceSessions[deviceId]?.device?.projectId
            val issue = com.jeanloickdt.signal.SignalSetpoint.write(
                signals, userId, deviceId, address, msg.value, msg.text,
                System.currentTimeMillis(),
                send = { target, frame ->
                    connections.deviceOutboxes[target]?.send(frame, isStreaming = true) ?: false
                },
                broadcast = { frame -> projectId?.let { broadcastToApps(connections, it, frame) } }
            )
            when (issue) {
                is com.jeanloickdt.signal.SignalSetpoint.Outcome.Refused ->
                    logger.info("write_signal refuse — userId=$userId device=$deviceId I$address : ${issue.reason}")
                // Rangee, pas livree : la carte dort. Ce n'est pas un echec,
                // et l'app doit pouvoir le dire autrement qu'en criant.
                is com.jeanloickdt.signal.SignalSetpoint.Outcome.Stored ->
                    events.commandFailed(
                        session = appSession.session, deviceId = deviceId,
                        reason = CommandFailedReason.DEVICE_OFFLINE
                    )
                else -> Unit
            }
        }
        else -> logger.warn("Unknown inbound text type from app userId=${appSession.userId}: ${msg.type}")
    }
}

/**
 * Relay a binary frame from the app to the targeted devices.
 *
 * Flow :
 *   1. Extract the device UUIDs from the frame
 *   2. Classify the frame (streaming or discrete) for the outbox backpressure
 *   3. For each UUID → find the TCP session in ConnectionRegistry
 *      - If absent/closed → send command_failed (reason=device_offline) to the app
 *   4. Verify that the user owns the device (in-memory, no DB)
 *      - If non-owner → send command_failed (reason=forbidden) to the app
 *   5. Trim the DEV header from the frame
 *   6. Send the trimmed frame to the device via the device's **outbox**
 *      - The outbox serializes the TCP writes (1 consumer coroutine per
 *        device) and applies backpressure (drops streaming if full)
 *      - If the outbox is closed (device disconnected meanwhile) → command_failed
 *      - If discrete frame and outbox full → `send` suspends briefly —
 *        the app's WS reception is thus backpressured cleanly
 */
private suspend fun relayFrameToDevices(
    session: io.ktor.server.websocket.DefaultWebSocketServerSession,
    userId: String,
    frameBytes: ByteArray,
    connections: ConnectionRegistry,
    events: ControlEventBroadcaster
) {
    val targetDeviceIds = FrameParser.extractDeviceIds(frameBytes)
    if (targetDeviceIds.isEmpty()) return

    val isStreaming = FrameParser.isStreamingCommand(frameBytes)

    val trimmedFrame = FrameParser.trimDeviceHeader(frameBytes) ?: return

    targetDeviceIds.forEach { targetDeviceId ->
        val deviceSession = connections.deviceSessions[targetDeviceId]

        // device offline (session absent or socket no longer active).
        // ktor Socket has no `isClosed`; liveness is its socketContext job.
        if (deviceSession == null || !deviceSession.socket.socketContext.isActive) {
            logger.info("Command to offline device — userId=$userId deviceId=$targetDeviceId")
            events.commandFailed(
                session  = session,
                deviceId = targetDeviceId,
                reason   = CommandFailedReason.DEVICE_OFFLINE
            )
            return@forEach
        }

        // La SEULE verification d'appartenance ecrite a la main qui reste, et
        // elle n'est pas du meme genre que les autres.
        //
        // Les huit qu'on a retirees comparaient une ligne RELUE par
        // identifiant : la signature du depot pouvait les absorber, parce que
        // c'est la lecture elle-meme qui devait etre cadree. Ici il n'y a pas
        // de lecture — `deviceSession` vient du registre des sessions TCP
        // vivantes, indexe par carte, et sa ligne a ete etablie a la poignee
        // de main par `findByTokenHash`.
        //
        // Il n'existe donc aucune signature ou pousser ce test : c'est une
        // comparaison entre deux identites deja etablies, pas une resolution.
        if (deviceSession.device.ownerId != userId) {
            logger.warn("Ownership violation — userId=$userId tried to relay to device=$targetDeviceId owned by ${deviceSession.device.ownerId}")
            events.commandFailed(
                session  = session,
                deviceId = targetDeviceId,
                reason   = CommandFailedReason.FORBIDDEN
            )
            return@forEach
        }

        // TCP relay via the outbox (serializes writes + drops streaming if full)
        val outbox = connections.deviceOutboxes[targetDeviceId]
        if (outbox == null) {
            // inconsistent registry — session present but no outbox.
            // Should not happen after registerDevice. Tolerance :
            // we notify the app rather than silently dropping.
            logger.warn("Missing outbox for device=$targetDeviceId (session exists) — treating as relay error")
            events.commandFailed(
                session  = session,
                deviceId = targetDeviceId,
                reason   = CommandFailedReason.RELAY_ERROR
            )
            return@forEach
        }

        val enqueued = outbox.send(trimmedFrame, isStreaming)
        if (!enqueued) {
            // Closed outbox = dead socket, already detected by the consumer
            // coroutine. We clean up the session along the way and notify
            // the app so it can surface the error.
            logger.warn("Outbox closed for device=$targetDeviceId — removing session")
            // Pass OUR view of the session: a dead outbox must never evict a
            // session newer than itself — triggered by a button press landing
            // exactly during a reconnect.
            connections.unregisterDevice(targetDeviceId, deviceSession.socket)
            events.commandFailed(
                session  = session,
                deviceId = targetDeviceId,
                reason   = CommandFailedReason.RELAY_ERROR
            )
        }
    }
}