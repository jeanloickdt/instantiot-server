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

    private suspend fun sendEventToSession(session: WebSocketSession, event: ControlEvent) {
        val jsonText = json.encodeToString(event)
        try {
            session.send(Frame.Text(jsonText))
        } catch (e: Exception) {
            logger.warn("Failed to send command_failed event — ${e.message}")
        }
    }
}
