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
 *   - commandFailed(session, deviceId, reason)
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
     * Envoye uniquement a la session emettrice.
     * reason : DEVICE_OFFLINE / FORBIDDEN / RELAY_ERROR
     */
    suspend fun commandFailed(
        session: WebSocketSession,
        deviceId: String,
        reason: String
    ) {
        val event = ControlEvent(
            type     = ControlEventType.COMMAND_FAILED,
            deviceId = deviceId,
            reason   = reason
        )
        sendEventToSession(session, event)
    }

    /**
     * Un bucket d'agregation vient de fermer cote serveur (RAM aggregator
     * → DB). Broadcast aux apps qui ont **explicitement souscrit** au
     * widget via le message inbound "subscribe_history".
     *
     * Filtrage en 2 niveaux :
     *  1. Project match : la session doit avoir activeProjectId == projectId.
     *  2. Subscription match : la session doit avoir (widgetId → granularity)
     *     dans son set `historySubs`.
     *
     * → Une app qui n'a aucun chart actif pour ce widget ne recoit pas
     *   ce message. Bande passante zero quand inutile.
     *
     * Volume nominal (10 widgets souscrits, 3 tiers) :
     *  - ~10 msg/min cote minute
     *  - ~10 msg/h cote hour
     *  - ~10 msg/jour cote day
     */
    suspend fun bucketClosed(
        projectId: String,
        widgetId: String,
        seriesId: String?,
        bucketAt: Long,
        avg: Double,
        min: Double,
        max: Double,
        count: Int,
        granularity: String
    ) {
        val event = ControlEvent(
            type        = ControlEventType.BUCKET_UPDATED,
            widgetId    = widgetId,
            seriesId    = seriesId,
            bucketAt    = bucketAt,
            avg         = avg,
            min         = min,
            max         = max,
            count       = count,
            granularity = granularity
        )
        val jsonText = json.encodeToString(event)
        val targetSessions = SessionRegistry.getAppSessionsForProject(projectId)
            .filter { it.historySubs[widgetId] == granularity }
        if (targetSessions.isEmpty()) return

        targetSessions.forEach { appSession ->
            try {
                appSession.session.send(Frame.Text(jsonText))
            } catch (e: Exception) {
                logger.warn("Failed to send bucket_updated to userId=${appSession.userId} — removing session")
                SessionRegistry.unregisterApp(appSession.userId, appSession.session)
            }
        }
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
