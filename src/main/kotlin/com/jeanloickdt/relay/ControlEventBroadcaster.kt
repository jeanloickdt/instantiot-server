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

// relay/ControlEventBroadcaster.kt
package com.jeanloickdt.relay

import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Broadcaster of control events to the apps connected via WebSocket.
 *
 * The events are serialized as JSON and sent as Frame.Text (so as not to
 * conflict with the Frame.Binary of the iWidgets v1 frames).
 *
 * Three types of events :
 *   - deviceOnline(projectId, deviceId, deviceName)
 *       → broadcast to all apps watching the project
 *   - deviceOffline(projectId, deviceId, reason)
 *       → broadcast to all apps watching the project
 *   - commandFailed(session, deviceId, reason)
 *       → sent to the emitting session only (not a broadcast)
 */
class ControlEventBroadcaster(
    private val registry: SessionRegistry
) {

    private val logger = LoggerFactory.getLogger("ControlEventBroadcaster")
    private val json = Json { encodeDefaults = false }

    /**
     * An ESP device has just connected over TCP.
     * Broadcast to all apps of the project.
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
     * An ESP device has disconnected.
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
     * An App->Device command has failed.
     * Sent to the emitting session only.
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
     * An aggregation bucket has just closed on the server side (RAM aggregator
     * → DB). Broadcast to the apps that have **explicitly subscribed** to the
     * widget via the inbound "subscribe_history" message.
     *
     * Filtering at 2 levels :
     *  1. Project match : the session must have activeProjectId == projectId.
     *  2. Subscription match : the session must have (widgetId → granularity)
     *     in its `historySubs` set.
     *
     * → An app that has no active chart for this widget does not receive
     *   this message. Zero bandwidth when useless.
     *
     * Nominal volume (10 subscribed widgets, 3 tiers) :
     *  - ~10 msg/min on the minute side
     *  - ~10 msg/h on the hour side
     *  - ~10 msg/day on the day side
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
        val targetSessions = registry.getAppSessionsForProject(projectId)
            .filter { it.historySubs[widgetId] == granularity }
        if (targetSessions.isEmpty()) return

        targetSessions.forEach { appSession ->
            try {
                appSession.session.send(Frame.Text(jsonText))
            } catch (e: Exception) {
                logger.warn("Failed to send bucket_updated to userId=${appSession.userId} — removing session")
                registry.unregisterApp(appSession.userId, appSession.session)
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // Internal helpers
    // ────────────────────────────────────────────────────────────

    private suspend fun broadcastToProject(projectId: String, event: ControlEvent) {
        val jsonText = json.encodeToString(event)
        val appSessions = registry.getAppSessionsForProject(projectId)

        appSessions.forEach { appSession ->
            try {
                appSession.session.send(Frame.Text(jsonText))
            } catch (e: Exception) {
                logger.warn("Failed to send event to userId=${appSession.userId} — removing session")
                registry.unregisterApp(appSession.userId, appSession.session)
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