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
 * Message inbound app → server pour s'abonner aux bucket_updated d'un set
 * de widgets. L'app envoie le set COMPLET à chaque changement (mount/dispose
 * de chart, ouverture/fermeture de bottom sheet, toggle "Enable history"). Pas
 * de delta — le server replace le set à chaque message reçu.
 *
 * Exemple :
 * ```json
 * {"type":"subscribe_history","widgets":[
 *   {"widgetId":"gauge1","granularity":"minute"},
 *   {"widgetId":"level1","granularity":"minute"}
 * ]}
 * ```
 *
 * Empty array = unsubscribe de tout (= server n'émet plus de bucket_updated
 * vers cette session).
 */
@Serializable
private data class AppInboundMessage(
    val type: String,
    val widgets: List<HistorySubscriptionDto>? = null
)

@Serializable
private data class HistorySubscriptionDto(
    val widgetId: String,
    val granularity: String   // "minute" | "hour" | "day"
)

private val appInboundJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * WebSocket relay — connexions app Android.
 *
 * Protocol :
 *   1. App se connecte → JWT vérifié
 *   2. Premier message texte = projectId → activeProjectId enregistré
 *   3. Tous les messages suivants = trames binaires iWidgets v1
 *   4. App se déconnecte → session retirée
 *
 * Chaque connexion app tourne dans sa propre coroutine Ktor — non-bloquant.
 * 50 apps connectées = 50 coroutines légères.
 */
fun Application.configureAppRelay(projectRepository: ProjectRepository) {

    install(WebSockets) {
        pingPeriod = 15.seconds  // Ktor gère ping/pong automatiquement
        timeout    = 30.seconds  // ferme si pas de réponse après 30s
        maxFrameSize = 8192  // 8 KB — largement suffisant pour les trames iWidgets v1
    }

    routing {
        authenticate("jwt") {

            webSocket("/ws/app") {

                // récupérer le userId depuis le JWT
                val userId = call.principal<JWTPrincipal>()?.subject
                if (userId == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                    return@webSocket
                }

                // handshake — premier message = projectId
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

                // vérifier que le user est bien propriétaire du projet
                val project = projectRepository.findById(projectId)
                if (project == null || project.ownerId != userId) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Project not found"))
                    return@webSocket
                }

                // enregistrer la session app avec le projet actif
                val appSession = SessionRegistry.registerApp(userId, this)
                SessionRegistry.setActiveProject(appSession, projectId)
                logger.info("App connected — userId=$userId projectId=$projectId")

                try {
                    // Le dispatch est **séquentiel** (pas de `launch` par frame) :
                    // chaque device a son `DeviceOutbox` qui serialize les writes
                    // TCP + applique la backpressure. Lancer une coroutine par
                    // frame casserait cette backpressure.
                    //
                    // Deux types de frames acceptés après le handshake :
                    //   - Frame.Binary : trames iWidgets v1 (commandes app → device)
                    //   - Frame.Text   : control messages (subscribe_history, ...)
                    for (incomingFrame in incoming) {

                        when (incomingFrame) {
                            is Frame.Binary -> {
                                val frameBytes = incomingFrame.data
                                if (!FrameParser.isValid(frameBytes)) {
                                    logger.warn("Invalid frame received from app userId=$userId — ignored")
                                    continue
                                }
                                relayFrameToDevices(this@webSocket, userId, frameBytes)
                            }
                            is Frame.Text -> {
                                handleAppTextMessage(appSession, incomingFrame.readText())
                            }
                            else -> { /* ping/pong/close handled by Ktor */ }
                        }
                    }
                } finally {
                    // déconnexion — retirer cette session spécifique
                    SessionRegistry.unregisterApp(userId, this@webSocket)
                    logger.info("App disconnected — userId=$userId")
                }
            }
        }
    }
}

/**
 * Parse et applique un message inbound texte de l'app.
 *
 * Aujourd'hui un seul type : `subscribe_history` — met à jour le set
 * de widgets dont l'app veut recevoir les bucket_updated. Replace
 * l'ensemble (pas de delta) à chaque message.
 */
private fun handleAppTextMessage(appSession: AppSession, text: String) {
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
        else -> logger.warn("Unknown inbound text type from app userId=${appSession.userId}: ${msg.type}")
    }
}

/**
 * Relay une trame binaire de l'app vers les devices ciblés.
 *
 * Flow :
 *   1. Extraire les device UUIDs de la trame
 *   2. Classifier la trame (streaming ou discrète) pour la backpressure outbox
 *   3. Pour chaque UUID → trouver la session TCP dans SessionRegistry
 *      - Si absente/fermée → envoyer command_failed (reason=device_offline) a l'app
 *   4. Vérifier que le user est propriétaire du device (in-memory, pas de DB)
 *      - Si non-owner → envoyer command_failed (reason=forbidden) a l'app
 *   5. Trim le header DEV de la trame
 *   6. Envoyer la trame trimée au device via l'**outbox** du device
 *      - L'outbox sérialise les writes TCP (1 coroutine consommatrice par
 *        device) et applique la backpressure (drop streaming si plein)
 *      - Si l'outbox est fermée (device déconnecté entre-temps) → command_failed
 *      - Si trame discrète et outbox plein → `send` suspend brièvement —
 *        la réception WS de l'app est ainsi backpressured proprement
 */
private suspend fun relayFrameToDevices(
    session: io.ktor.server.websocket.DefaultWebSocketServerSession,
    userId: String,
    frameBytes: ByteArray
) {
    val targetDeviceIds = FrameParser.extractDeviceIds(frameBytes)
    if (targetDeviceIds.isEmpty()) return

    val isStreaming = FrameParser.isStreamingCommand(frameBytes)

    val trimmedFrame = FrameParser.trimDeviceHeader(frameBytes) ?: return

    targetDeviceIds.forEach { targetDeviceId ->
        val deviceSession = SessionRegistry.deviceSessions[targetDeviceId]

        // device offline (session absente ou socket ferme)
        if (deviceSession == null || deviceSession.socket.isClosed) {
            logger.info("Command to offline device — userId=$userId deviceId=$targetDeviceId")
            ControlEventBroadcaster.commandFailed(
                session  = session,
                deviceId = targetDeviceId,
                reason   = CommandFailedReason.DEVICE_OFFLINE
            )
            return@forEach
        }

        // ownership check — device appartient a un autre user
        if (deviceSession.device.ownerId != userId) {
            logger.warn("Ownership violation — userId=$userId tried to relay to device=$targetDeviceId owned by ${deviceSession.device.ownerId}")
            ControlEventBroadcaster.commandFailed(
                session  = session,
                deviceId = targetDeviceId,
                reason   = CommandFailedReason.FORBIDDEN
            )
            return@forEach
        }

        // relay TCP via l'outbox (serialise writes + drop streaming si plein)
        val outbox = SessionRegistry.deviceOutboxes[targetDeviceId]
        if (outbox == null) {
            // registre incohérent — session présente mais pas d'outbox.
            // Ne devrait pas arriver après registerDevice. Tolérance :
            // on notifie l'app plutôt que silent drop.
            logger.warn("Missing outbox for device=$targetDeviceId (session exists) — treating as relay error")
            ControlEventBroadcaster.commandFailed(
                session  = session,
                deviceId = targetDeviceId,
                reason   = CommandFailedReason.RELAY_ERROR
            )
            return@forEach
        }

        val enqueued = outbox.send(trimmedFrame, isStreaming)
        if (!enqueued) {
            // Outbox fermée = socket mort, déjà détecté par la coroutine
            // consommatrice. On clean la session au passage et on notifie
            // l'app pour qu'elle puisse surfacer l'erreur.
            logger.warn("Outbox closed for device=$targetDeviceId — removing session")
            SessionRegistry.unregisterDevice(targetDeviceId)
            ControlEventBroadcaster.commandFailed(
                session  = session,
                deviceId = targetDeviceId,
                reason   = CommandFailedReason.RELAY_ERROR
            )
        }
    }
}