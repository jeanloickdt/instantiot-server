package com.jeanloickdt.relay

import com.jeanloickdt.project.domain.ProjectRepository
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("AppRelay")

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
                    // écouter les trames binaires — seulement des trames iWidgets v1 après le handshake
                    for (incomingFrame in incoming) {

                        if (incomingFrame !is Frame.Binary) continue

                        val frameBytes = incomingFrame.data

                        if (!FrameParser.isValid(frameBytes)) {
                            logger.warn("Invalid frame received from app userId=$userId — ignored")
                            continue
                        }

                        // relay vers les devices dans une coroutine IO — non-bloquant
                        launch(Dispatchers.IO) {
                            relayFrameToDevices(this@webSocket, userId, frameBytes)
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
 * Relay une trame binaire de l'app vers les devices ciblés.
 *
 * Flow :
 *   1. Extraire les device UUIDs + SEQ de la trame
 *   2. Pour chaque UUID → trouver la session TCP dans SessionRegistry
 *      - Si absente/fermée → envoyer command_failed (reason=device_offline) a l'app
 *   3. Vérifier que le user est propriétaire du device (in-memory, pas de DB)
 *      - Si non-owner → envoyer command_failed (reason=forbidden) a l'app
 *   4. Trim le header DEV de la trame
 *   5. Envoyer la trame trimée au device via TCP
 *      - Si exception → envoyer command_failed (reason=relay_error) a l'app
 */
private suspend fun relayFrameToDevices(
    session: io.ktor.server.websocket.DefaultWebSocketServerSession,
    userId: String,
    frameBytes: ByteArray
) {
    val targetDeviceIds = FrameParser.extractDeviceIds(frameBytes)
    if (targetDeviceIds.isEmpty()) return

    val seq = FrameParser.extractSeq(frameBytes)

    val trimmedFrame = FrameParser.trimDeviceHeader(frameBytes) ?: return

    targetDeviceIds.forEach { targetDeviceId ->
        val deviceSession = SessionRegistry.deviceSessions[targetDeviceId]

        // device offline (session absente ou socket ferme)
        if (deviceSession == null || deviceSession.socket.isClosed) {
            logger.info("Command to offline device — userId=$userId deviceId=$targetDeviceId seq=$seq")
            ControlEventBroadcaster.commandFailed(
                session  = session,
                deviceId = targetDeviceId,
                reason   = CommandFailedReason.DEVICE_OFFLINE,
                seq      = seq
            )
            return@forEach
        }

        // ownership check — device appartient a un autre user
        if (deviceSession.device.ownerId != userId) {
            logger.warn("Ownership violation — userId=$userId tried to relay to device=$targetDeviceId owned by ${deviceSession.device.ownerId}")
            ControlEventBroadcaster.commandFailed(
                session  = session,
                deviceId = targetDeviceId,
                reason   = CommandFailedReason.FORBIDDEN,
                seq      = seq
            )
            return@forEach
        }

        // relay TCP vers le device
        try {
            deviceSession.socket.getOutputStream().apply {
                write(trimmedFrame)
                flush()
            }
        } catch (e: Exception) {
            logger.warn("Failed to relay to device=$targetDeviceId — removing session")
            SessionRegistry.unregisterDevice(targetDeviceId)
            ControlEventBroadcaster.commandFailed(
                session  = session,
                deviceId = targetDeviceId,
                reason   = CommandFailedReason.RELAY_ERROR,
                seq      = seq
            )
        }
    }
}