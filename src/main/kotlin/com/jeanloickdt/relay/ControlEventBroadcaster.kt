// relay/ControlEventBroadcaster.kt
package com.jeanloickdt.relay

import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Broadcaster de control events vers les apps connectees via WebSocket.
 *
 * Les events sont serialises en JSON et envoyes en Frame.Text (pour ne pas
 * entrer en conflit avec les Frame.Binary des trames iWidgets v1).
 *
 * Trois types d'events :
 *   - deviceOnline(projectId, deviceId, deviceName)
 *       → broadcast a toutes les apps qui regardent le projet
 *   - deviceOffline(projectId, deviceId, reason)
 *       → broadcast a toutes les apps qui regardent le projet
 *   - commandFailed(session, deviceId, reason, seq)
 *       → envoye a la session emettrice uniquement (pas un broadcast)
 */
object ControlEventBroadcaster {

    private val logger = LoggerFactory.getLogger("ControlEventBroadcaster")
    private val json = Json { encodeDefaults = false }

    /**
     * Device ESP vient de se connecter en TCP.
     * Broadcast a toutes les apps du projet.
     */
    suspend fun deviceOnline(projectId: String, deviceId: String, deviceName: String) {
        val event = ControlEvent(
            type       = ControlEventType.DEVICE_ONLINE,
            deviceId   = deviceId,
            deviceName = deviceName
        )
        broadcastToProject(projectId, event)
    }

    /**
     * Device ESP est deconnecte.
     * reason : DISCONNECTED / TOKEN_RENEWED / DELETED
     */
    suspend fun deviceOffline(projectId: String, deviceId: String, reason: String) {
        val event = ControlEvent(
            type     = ControlEventType.DEVICE_OFFLINE,
            deviceId = deviceId,
            reason   = reason
        )
        broadcastToProject(projectId, event)
    }

    /**
     * Commande App->Device a echoue.
     * Envoye uniquement a la session emettrice (correlation via seq).
     * reason : DEVICE_OFFLINE / FORBIDDEN / RELAY_ERROR
     */
    suspend fun commandFailed(
        session: WebSocketSession,
        deviceId: String,
        reason: String,
        seq: Int?
    ) {
        val event = ControlEvent(
            type     = ControlEventType.COMMAND_FAILED,
            deviceId = deviceId,
            reason   = reason,
            seq      = seq
        )
        sendEventToSession(session, event)
    }

    // ════════════════════════════════════════════════════════════════
    // Realtime sync broadcasts — feature `realtime_sync`
    // ════════════════════════════════════════════════════════════════

    /**
     * Layout d'un projet mis a jour par un client. Broadcast a toutes
     * les apps qui regardent ce projet (sauf l'emetteur, qui se filtrera
     * lui-meme via `sourceSessionId`).
     */
    suspend fun projectLayoutUpdated(
        projectId: String,
        layoutJson: String,
        updatedAt: Long,
        sourceSessionId: String?
    ) {
        val event = ControlEvent(
            type = ControlEventType.PROJECT_LAYOUT_UPDATED,
            projectId = projectId,
            layoutJson = layoutJson,
            updatedAt = updatedAt,
            sourceSessionId = sourceSessionId
        )
        broadcastToProject(projectId, event)
    }

    /**
     * Un nouveau projet a ete cree. Broadcast aux autres appareils du
     * meme owner (pas broadcast par projectId — ils ne peuvent pas
     * encore y etre connectes).
     */
    suspend fun projectCreated(
        ownerId: String,
        projectId: String,
        name: String,
        createdAt: Long,
        sourceSessionId: String?
    ) {
        val event = ControlEvent(
            type = ControlEventType.PROJECT_CREATED,
            projectId = projectId,
            name = name,
            createdAt = createdAt,
            sourceSessionId = sourceSessionId
        )
        broadcastToUser(ownerId, event)
    }

    /** Un projet a ete renomme. */
    suspend fun projectRenamed(
        ownerId: String,
        projectId: String,
        name: String,
        updatedAt: Long,
        sourceSessionId: String?
    ) {
        val event = ControlEvent(
            type = ControlEventType.PROJECT_RENAMED,
            projectId = projectId,
            name = name,
            updatedAt = updatedAt,
            sourceSessionId = sourceSessionId
        )
        broadcastToUser(ownerId, event)
    }

    /** Un projet a ete supprime. */
    suspend fun projectDeleted(
        ownerId: String,
        projectId: String,
        sourceSessionId: String?
    ) {
        val event = ControlEvent(
            type = ControlEventType.PROJECT_DELETED,
            projectId = projectId,
            sourceSessionId = sourceSessionId
        )
        broadcastToUser(ownerId, event)
    }

    /** Un device a ete enregistre (created). */
    suspend fun deviceRegistered(
        projectId: String,
        deviceId: String,
        deviceName: String,
        sourceSessionId: String?
    ) {
        val event = ControlEvent(
            type = ControlEventType.DEVICE_REGISTERED,
            projectId = projectId,
            deviceId = deviceId,
            deviceName = deviceName,
            sourceSessionId = sourceSessionId
        )
        broadcastToProject(projectId, event)
    }

    /** Un device a ete renomme. */
    suspend fun deviceRenamed(
        projectId: String,
        deviceId: String,
        deviceName: String,
        sourceSessionId: String?
    ) {
        val event = ControlEvent(
            type = ControlEventType.DEVICE_RENAMED,
            projectId = projectId,
            deviceId = deviceId,
            deviceName = deviceName,
            sourceSessionId = sourceSessionId
        )
        broadcastToProject(projectId, event)
    }

    /** Un device a ete supprime (distinct de DeviceOffline). */
    suspend fun deviceDeleted(
        projectId: String,
        deviceId: String,
        sourceSessionId: String?
    ) {
        val event = ControlEvent(
            type = ControlEventType.DEVICE_DELETED,
            projectId = projectId,
            deviceId = deviceId,
            sourceSessionId = sourceSessionId
        )
        broadcastToProject(projectId, event)
    }

    // ────────────────────────────────────────────────────────────
    // Helpers internes
    // ────────────────────────────────────────────────────────────

    private suspend fun broadcastToProject(projectId: String, event: ControlEvent) {
        val jsonText = json.encodeToString(event)
        val appSessions = SessionRegistry.getAppSessionsForProject(projectId)

        appSessions.forEach { appSession ->
            try {
                appSession.session.send(Frame.Text(jsonText))
            } catch (e: Exception) {
                logger.warn("Failed to send event to userId=${appSession.userId} — removing session")
                SessionRegistry.unregisterApp(appSession.userId, appSession.session)
            }
        }
    }

    /**
     * Broadcast a TOUTES les sessions WS d'un user (tous appareils
     * connectes, peu importe le projet actuellement ouvert). Utilise
     * pour les changements de liste de projets (creation, suppression)
     * qui doivent etre vus meme depuis la liste Maker Pro.
     */
    private suspend fun broadcastToUser(userId: String, event: ControlEvent) {
        val jsonText = json.encodeToString(event)
        val sessions = SessionRegistry.appSessions[userId] ?: return
        sessions.forEach { appSession ->
            try {
                appSession.session.send(Frame.Text(jsonText))
            } catch (e: Exception) {
                logger.warn("Failed to send user-event to userId=$userId — removing session")
                SessionRegistry.unregisterApp(appSession.userId, appSession.session)
            }
        }
    }

    private suspend fun sendEventToSession(session: WebSocketSession, event: ControlEvent) {
        val jsonText = json.encodeToString(event)
        try {
            session.send(Frame.Text(jsonText))
        } catch (e: Exception) {
            logger.warn("Failed to send command_failed event — ${e.message}")
        }
    }
}
