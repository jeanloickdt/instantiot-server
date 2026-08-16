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
    val widgets: List<HistorySubscriptionDto>? = null
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
    events: ControlEventBroadcaster
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

                // verify that the user actually owns the project
                val project = projectRepository.findById(projectId)
                if (project == null || project.ownerId != userId) {
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
                                handleAppTextMessage(appSession, incomingFrame.readText())
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
 * Today a single type : `subscribe_history` — updates the set of
 * widgets for which the app wants to receive the bucket_updated.
 * Replaces the whole set (no delta) on each message.
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

        // ownership check — device belongs to another user
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