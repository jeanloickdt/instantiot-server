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
 * Scope de diffusion :
 *  - project : toutes les WS qui ont handshake sur le meme projectId
 *  - user    : toutes les WS d'un user (peu importe le projet courant)
 *
 * Chaque methode accepte un `sourceSessionId` (optional) qui est juste
 * relaye dans l'event — l'appareil emetteur se filtrera lui-meme.
 */
object ControlEventBroadcaster {

    private val logger = LoggerFactory.getLogger("ControlEventBroadcaster")
    private val json = Json { encodeDefaults = false }

    // ════════════════════════════════════════════════════════════════
    // DEVICE TCP (events historiques — device_online / offline / command_failed)
    // ════════════════════════════════════════════════════════════════

    /** Device ESP vient de se connecter en TCP. Broadcast projet. */
    suspend fun deviceOnline(projectId: String, deviceId: String, deviceName: String) {
        val event = ControlEvent(
            type       = ControlEventType.DEVICE_ONLINE,
            deviceId   = deviceId,
            deviceName = deviceName,
            projectId  = projectId,
            at         = System.currentTimeMillis()
        )
        broadcastToProject(projectId, event)
    }

    /** Device ESP deconnecte. reason : DISCONNECTED / TOKEN_RENEWED / DELETED. */
    suspend fun deviceOffline(projectId: String, deviceId: String, reason: String) {
        val event = ControlEvent(
            type      = ControlEventType.DEVICE_OFFLINE,
            deviceId  = deviceId,
            reason    = reason,
            projectId = projectId,
            at        = System.currentTimeMillis()
        )
        broadcastToProject(projectId, event)
    }

    /**
     * Commande App->Device a echoue. Envoye uniquement a la session
     * emettrice (correlation via seq).
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
            seq      = seq,
            at       = System.currentTimeMillis()
        )
        sendEventToSession(session, event)
    }

    // ════════════════════════════════════════════════════════════════
    // REALTIME SYNC — project_updated / created / deleted
    // ════════════════════════════════════════════════════════════════

    /**
     * Layout d'un projet a ete modifie. Broadcast projet-scope.
     * Les appareils qui regardent ce projet appliqueront le nouveau
     * layoutJson (apres filtrage anti-echo via sourceSessionId).
     */
    suspend fun projectLayoutUpdated(
        projectId: String,
        layoutJson: String,
        sourceSessionId: String?
    ) {
        val event = ControlEvent(
            type            = ControlEventType.PROJECT_UPDATED,
            projectId       = projectId,
            field           = ProjectUpdatedField.LAYOUT,
            layoutJson      = layoutJson,
            at              = System.currentTimeMillis(),
            sourceSessionId = sourceSessionId
        )
        broadcastToProject(projectId, event)
    }

    /** Renommage d'un projet. Broadcast user-scope + project-scope. */
    suspend fun projectRenamed(
        ownerId: String,
        projectId: String,
        newName: String,
        sourceSessionId: String?
    ) {
        val event = ControlEvent(
            type            = ControlEventType.PROJECT_UPDATED,
            projectId       = projectId,
            field           = ProjectUpdatedField.NAME,
            value           = newName,
            at              = System.currentTimeMillis(),
            sourceSessionId = sourceSessionId
        )
        // user-scope : tout appareil du user doit voir le nouveau nom dans sa liste
        broadcastToUser(ownerId, event)
    }

    /** Creation d'un projet. User-scope. */
    suspend fun projectCreated(
        ownerId: String,
        projectId: String,
        name: String,
        sourceSessionId: String?
    ) {
        val event = ControlEvent(
            type            = ControlEventType.PROJECT_CREATED,
            projectId       = projectId,
            name            = name,
            at              = System.currentTimeMillis(),
            sourceSessionId = sourceSessionId
        )
        broadcastToUser(ownerId, event)
    }

    /** Suppression d'un projet. User-scope (les autres phones doivent refresh leur liste). */
    suspend fun projectDeleted(
        ownerId: String,
        projectId: String,
        sourceSessionId: String?
    ) {
        val event = ControlEvent(
            type            = ControlEventType.PROJECT_DELETED,
            projectId       = projectId,
            at              = System.currentTimeMillis(),
            sourceSessionId = sourceSessionId
        )
        broadcastToUser(ownerId, event)
    }

    // ════════════════════════════════════════════════════════════════
    // REALTIME SYNC — device_created / updated / deleted
    // ════════════════════════════════════════════════════════════════

    /** Device enregistre — carry le device complet (sans token). Project-scope. */
    suspend fun deviceCreated(
        projectId: String,
        device: DevicePayload,
        sourceSessionId: String?
    ) {
        val event = ControlEvent(
            type            = ControlEventType.DEVICE_CREATED,
            projectId       = projectId,
            device          = device,
            at              = System.currentTimeMillis(),
            sourceSessionId = sourceSessionId
        )
        broadcastToProject(projectId, event)
    }

    /**
     * Device modifie — pour V1 utilise uniquement pour renew-token.
     * Le champ [field] discrimine le changement (DeviceUpdatedField).
     */
    suspend fun deviceUpdated(
        projectId: String,
        deviceId: String,
        field: String,
        sourceSessionId: String?
    ) {
        val event = ControlEvent(
            type            = ControlEventType.DEVICE_UPDATED,
            projectId       = projectId,
            deviceId        = deviceId,
            field           = field,
            at              = System.currentTimeMillis(),
            sourceSessionId = sourceSessionId
        )
        broadcastToProject(projectId, event)
    }

    /** Device supprime (distinct de device_offline reason=deleted). Project-scope. */
    suspend fun deviceDeleted(
        projectId: String,
        deviceId: String,
        sourceSessionId: String?
    ) {
        val event = ControlEvent(
            type            = ControlEventType.DEVICE_DELETED,
            projectId       = projectId,
            deviceId        = deviceId,
            at              = System.currentTimeMillis(),
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
     * Broadcast a TOUTES les sessions WS d'un user. Utilise pour les
     * events user-scope (project_created/renamed/deleted) qui doivent
     * reacher les autres appareils meme s'ils ne sont pas sur ce projet.
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
            logger.warn("Failed to send single-session event — ${e.message}")
        }
    }
}
