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
    private val connections: ConnectionRegistry
) {

    private val logger = LoggerFactory.getLogger("ControlEventBroadcaster")
    private val json = Json { encodeDefaults = false }

    /**
     * An ESP device has just connected over TCP.
     * Broadcast to all apps of the project.
     */
    fun deviceOnline(projectId: String, deviceId: String, deviceName: String) {
        val event = ControlEvent(
            type       = ControlEventType.DEVICE_ONLINE,
            deviceId   = deviceId,
            deviceName = deviceName
        )
        broadcastToProject(projectId, event)
    }

    /**
     * The dashboard was edited — by another phone, or by this one.
     *
     * Only the version travels. Pushing the whole layout on every debounced
     * save would send a blob to every session several times a minute, for
     * something most of them do not need: an app that is merely watching its
     * gauges has no use for the new geometry until someone looks at it.
     *
     * And the version is what makes this self-sorting: the app that just saved
     * already holds it and does nothing, an app that is behind knows to refetch.
     * Nobody has to track who originated the change.
     */
    fun layoutChanged(projectId: String, version: Int) {
        broadcastToProject(projectId, ControlEvent(
            type    = ControlEventType.LAYOUT_CHANGED,
            version = version
        ))
    }

    /**
     * An ESP device has disconnected.
     * reason : DISCONNECTED / TOKEN_RENEWED / DELETED
     */
    fun deviceOffline(projectId: String, deviceId: String, reason: String) {
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
    fun commandFailed(
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
    fun bucketClosed(
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
        val targetSessions = connections.getAppSessionsForProject(projectId)
            .filter { it.historySubs[widgetId] == granularity }
        if (targetSessions.isEmpty()) return

        targetSessions.forEach { appSession ->
            if (!appSession.outbox.trySendControl(jsonText)) {
                connections.unregisterApp(appSession.userId, appSession.session)
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // Internal helpers
    // ────────────────────────────────────────────────────────────

    private fun broadcastToProject(projectId: String, event: ControlEvent) {
        val jsonText = json.encodeToString(event)
        val appSessions = connections.getAppSessionsForProject(projectId)

        appSessions.forEach { appSession ->
            // The outbox never throws and never suspends. A `false` means the
            // session could not even absorb a discrete control event — it has
            // been closed, so drop it from the registry.
            if (!appSession.outbox.trySendControl(jsonText)) {
                connections.unregisterApp(appSession.userId, appSession.session)
            }
        }
    }

    private fun sendEventToSession(session: WebSocketSession, event: ControlEvent) {
        val jsonText = json.encodeToString(event)
        val appSession = connections.findAppSession(session)
        if (appSession == null) {
            logger.warn("command_failed for an unregistered session — dropped")
            return
        }
        if (!appSession.outbox.trySendControl(jsonText)) {
            connections.unregisterApp(appSession.userId, appSession.session)
        }
    }
}