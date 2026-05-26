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

// relay/DeviceRelay.kt
package com.jeanloickdt.relay

import com.jeanloickdt.common.ServerConfig
import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.device.domain.DeviceRow
import com.jeanloickdt.widget.data.HistoryAggregators
import com.jeanloickdt.widget.domain.WidgetRepository
import io.ktor.server.application.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

private val logger = LoggerFactory.getLogger("DeviceRelay")

// Max body size of a TCP frame — protects against malicious frames
private const val MAX_FRAME_BODY_SIZE = 1024

// ════════════════════════════════════════════════════════════════════
// Session timeouts
// ════════════════════════════════════════════════════════════════════

// Provisional window to read the handshake (before knowing the
// heartbeat declared by the device). Prevents a silent client from
// blocking a thread indefinitely.
private const val HANDSHAKE_TIMEOUT_MS = 10_000

// Lower bound of the post-handshake soTimeout. Protects against a device
// that declares heartbeat=50ms → timeout 125ms that timeout-loops immediately.
private const val MIN_SESSION_TIMEOUT_MS = 2_000L

// Upper bound of the post-handshake soTimeout. Protects against a device
// that declares heartbeat=1h → needlessly slow offline detection.
private const val MAX_SESSION_TIMEOUT_MS = 120_000L

// Legacy fallback — device that does not send `:heartbeatMs` at the handshake.
// Historical value (90s) to keep compatibility.
private const val LEGACY_SESSION_TIMEOUT_MS = 90_000L

// ════════════════════════════════════════════════════════════════════
// iWidgets v1 protocol — type byte dedicated to the heartbeat
// ════════════════════════════════════════════════════════════════════

// Type = 0xFE : heartbeat frame emitted by the device every
// `heartbeatMs` (Arduino lib). The server validates it (CRC/sync) but
// **does not dispatch it** — no widget event, no persist. Its only
// function on the server side is to reset the soTimeout (which the OS does
// automatically as soon as a byte is received).
internal const val TYPE_HEARTBEAT: UByte = 0xFEu

/**
 * TCP relay — ESP32/ESP8266 device connections.
 *
 * Protocol :
 *   1. ESP opens a TCP connection → port 9001
 *   2. ESP sends handshake : [TOKEN_LEN(1B) | TOKEN_BYTES]
 *   3. Server verifies SHA-256(token) → DeviceTable
 *   4. Device authenticated → session registered in SessionRegistry
 *   5. ESP sends iWidgets v1 binary frames continuously
 *   6. Server extracts widgetId + payload → lastPayloads + historyBuffer
 *   7. Server broadcasts the frame to the apps watching this project
 *   8. ESP disconnects → device.isOnline = false → session removed
 *
 * Each ESP connection runs in its own IO coroutine — non-blocking.
 * 50 connected ESPs = 50 lightweight coroutines.
 */
fun Application.startDeviceRelay(
    deviceRepository: DeviceRepository,
    widgetRepository: WidgetRepository,
    tcpPort: Int = 9001
) {
    val applicationScope: CoroutineScope = this

    applicationScope.launch(Dispatchers.IO) {
        val serverSocket = ServerSocket(tcpPort)
        logger.info("Device TCP relay listening on port $tcpPort")

        // close the ServerSocket cleanly at shutdown
        monitor.subscribe(ApplicationStopping) {
            serverSocket.close()
        }

        while (!serverSocket.isClosed) {
            try {
                val clientSocket = serverSocket.accept()

                // each device in its own coroutine — total isolation
                applicationScope.launch(Dispatchers.IO) {
                    handleDeviceConnection(
                        clientSocket     = clientSocket,
                        deviceRepository = deviceRepository,
                        widgetRepository = widgetRepository,
                        applicationScope = applicationScope
                    )
                }
            } catch (e: Exception) {
                if (!serverSocket.isClosed) {
                    logger.error("Error accepting device connection — ${e.message}")
                }
            }
        }
    }
}

/**
 * Handles the full connection of an ESP device.
 * Runs in its own coroutine — total isolation between devices.
 */
private suspend fun handleDeviceConnection(
    clientSocket: Socket,
    deviceRepository: DeviceRepository,
    widgetRepository: WidgetRepository,
    applicationScope: CoroutineScope
) {
    val deviceAddress = clientSocket.inetAddress.hostAddress

    try {
        val inputStream = clientSocket.getInputStream()

        // TCP keepalive — OS detects if the device disappears without closing the socket
        clientSocket.keepAlive = true
        // provisional soTimeout before handshake (avoids blocking on a silent client)
        clientSocket.soTimeout = HANDSHAKE_TIMEOUT_MS

        // handshake — format : "token" (legacy, 90s timeout) or
        //                       "token:heartbeatMs" (new, adaptive soTimeout)
        val handshake = readDeviceHandshake(inputStream)
        if (handshake == null) {
            logger.warn("Invalid handshake from $deviceAddress — closing connection")
            clientSocket.close()
            return
        }

        // Post-handshake : adjust the soTimeout according to the announced heartbeat.
        // - If heartbeat declared : soTimeout = heartbeat × 2.5 (tolerates 2 misses + jitter)
        // - Otherwise (legacy) : 90s fallback
        val sessionTimeoutMs = handshake.heartbeatMs?.let { hb ->
            (hb * 25 / 10).coerceIn(MIN_SESSION_TIMEOUT_MS, MAX_SESSION_TIMEOUT_MS)
        } ?: LEGACY_SESSION_TIMEOUT_MS
        clientSocket.soTimeout = sessionTimeoutMs.toInt()
        logger.info("Handshake OK — token=${handshake.token.take(8)}… heartbeat=${handshake.heartbeatMs ?: "legacy"}ms timeout=${sessionTimeoutMs}ms")

        // verify the token — SHA-256 lookup in DeviceTable
        val tokenHash = FrameParser.hashDeviceToken(handshake.token)
        val device = withContext(Dispatchers.IO) {
            deviceRepository.findByTokenHash(tokenHash)
        }

        if (device == null) {
            logger.warn("Unknown device token from $deviceAddress — closing connection")
            clientSocket.close()
            return
        }

        // device authenticated — register the session + mark online.
        // We pass `applicationScope` to the outbox → its consumer
        // coroutine outlives the `handleDeviceConnection` coroutine
        // and stops cleanly via `unregisterDevice`.
        SessionRegistry.registerDevice(device.id, device, clientSocket, applicationScope)
        withContext(Dispatchers.IO) {
            deviceRepository.updateOnlineStatus(device.id, isOnline = true)
            deviceRepository.updateLastSeen(device.id, System.currentTimeMillis())
        }
        logger.info("Device connected — deviceId=${device.id} name=${device.name} address=$deviceAddress")

        // broadcast device_online to the apps of the project
        ControlEventBroadcaster.deviceOnline(
            projectId  = device.projectId,
            deviceId   = device.id,
            deviceName = device.name
        )

        // listen for binary frames continuously
        try {
            while (!clientSocket.isClosed) {
                val frameBytes = readFrame(inputStream) ?: break

                if (!FrameParser.isValid(frameBytes)) {
                    logger.warn("Invalid frame from device=${device.id} — ignored")
                    continue
                }

                // processing in Dispatchers.Default — non-blocking CPU parsing
                applicationScope.launch(Dispatchers.Default) {
                    handleDeviceFrame(
                        frameBytes       = frameBytes,
                        device           = device,
                        widgetRepository = widgetRepository,
                        applicationScope = applicationScope
                    )
                }
            }
        } finally {
            // disconnection — mark offline + remove session
            SessionRegistry.unregisterDevice(device.id)
            withContext(Dispatchers.IO) {
                deviceRepository.updateOnlineStatus(device.id, isOnline = false)
            }
            logger.info("Device disconnected — deviceId=${device.id}")
            clientSocket.close()

            // broadcast device_offline to the apps of the project
            // reason = DISCONNECTED (normal TCP disconnect or timeout)
            // If renew-token or delete already broadcast with a specific reason,
            // the app receives 2 events — acceptable, it deduplicates on deviceId offline.
            ControlEventBroadcaster.deviceOffline(
                projectId = device.projectId,
                deviceId  = device.id,
                reason    = DeviceOfflineReason.DISCONNECTED
            )
        }

    } catch (e: Exception) {
        logger.error("Error handling device from $deviceAddress — ${e.message}")
        clientSocket.close()
    }
}

/**
 * Processes a binary frame received from an ESP.
 *
 * Flow :
 *   1. Extract widgetId + payload
 *   2. Update lastPayloads in RAM — sub-millisecond
 *   3. Add to the history buffer — flushed every 5s to a SQLite WAL batch
 *   4. Update last_payload in DB — asynchronous non-blocking
 *   5. Broadcast the intact frame to the apps watching this project
 *
 * The DB is never in the critical path of the relay.
 */
private suspend fun handleDeviceFrame(
    frameBytes: ByteArray,
    device: DeviceRow,
    widgetRepository: WidgetRepository,
    applicationScope: CoroutineScope
) {
    // Heartbeat (TYPE = 0xFE) : receiving the byte automatically resets
    // the OS soTimeout — no DB or broadcast, we return early.
    // `last_seen` was already updated at connect and will be updated on the
    // next real widget frame. No need to flood the DB for each heartbeat.
    val type = FrameParser.extractType(frameBytes)
    if (type == TYPE_HEARTBEAT.toInt()) return

    val widgetId      = FrameParser.extractWidgetId(frameBytes) ?: return
    val payloadBytes  = FrameParser.extractPayload(frameBytes)  ?: return
    val payloadBase64 = FrameParser.encodePayloadToBase64(payloadBytes)
    val now           = System.currentTimeMillis()

    // ── Auto-register : if we have never seen this widgetId, INSERT OR IGNORE
    //    into the `widgets` table (+ add to the RAM Set). Lets the REST
    //    history lookups work without the app needing to POST
    //    explicitly. RAM cache → 0 DB hit when the widget is known.
    if (SessionRegistry.knownWidgetIds.add(widgetId)) {
        applicationScope.launch(Dispatchers.IO) {
            val created = widgetRepository.registerIfAbsent(
                id        = widgetId,
                projectId = device.projectId,
                ownerId   = device.ownerId,
                type      = "auto"
            )
            if (created) {
                logger.info("Auto-registered widget=$widgetId project=${device.projectId} (first frame from device=${device.id})")
            }
        }
    }

    // update lastPayloads in RAM — sub-millisecond access
    SessionRegistry.lastPayloads[widgetId] = payloadBase64

    // add to the history buffer — flushed every 5s to a SQLite WAL batch
    SessionRegistry.historyBuffer.add(
        HistoryEntry(
            widgetId   = widgetId,
            projectId  = device.projectId,
            ownerId    = device.ownerId,
            payload    = payloadBase64,
            recordedAt = now
        )
    )

    // NUMERIC history — decode the value if the widget is analog
    // (gauge/metric/level/slider/chart).
    //
    // Blynk-style architecture (iWidgets history rework) :
    //  - Raw tier (widget_history_numeric) : OPT-IN via admin. If enabled,
    //    each sample is buffered without throttling (perfect fidelity).
    //  - Tiers min/hour/day : ALWAYS fed in parallel via the
    //    RAM aggregators. No deferred SQL cascade, each tier
    //    consumes the raw samples directly.
    //
    // No more throttle : it is up to the device sketch not to spam in
    // a free loop (documented recommendation). If raw is enabled, we
    // write everything.
    FrameParser.extractNumericValue(frameBytes)?.let { sample ->
        // Raw tier : opt-in only (off by default)
        if (ServerConfig.historyRawEnabled) {
            SessionRegistry.numericHistoryBuffer.add(
                NumericHistoryEntry(
                    widgetId   = widgetId,
                    projectId  = device.projectId,
                    ownerId    = device.ownerId,
                    seriesId   = sample.seriesId,
                    value      = sample.value,
                    recordedAt = now
                )
            )
        }

        // Aggregated tiers : always fed (1 min / 1 h / 1 day).
        // The buckets accumulate min/max/sum/count in RAM and will be
        // flushed by the 5s job in Application.kt.
        HistoryAggregators.minute.collect(
            widgetId  = widgetId,
            seriesId  = sample.seriesId,
            ts        = now,
            value     = sample.value,
            projectId = device.projectId,
            ownerId   = device.ownerId
        )
        HistoryAggregators.hour.collect(
            widgetId  = widgetId,
            seriesId  = sample.seriesId,
            ts        = now,
            value     = sample.value,
            projectId = device.projectId,
            ownerId   = device.ownerId
        )
        HistoryAggregators.day.collect(
            widgetId  = widgetId,
            seriesId  = sample.seriesId,
            ts        = now,
            value     = sample.value,
            projectId = device.projectId,
            ownerId   = device.ownerId
        )
    }

    // update last_payload in DB — asynchronous non-blocking
    applicationScope.launch(Dispatchers.IO) {
        widgetRepository.updateLastPayload(widgetId, payloadBase64, now)
    }

    // broadcast the intact frame to the apps watching this project
    broadcastToApps(device.projectId, frameBytes)
}

/**
 * Broadcasts a binary frame to all connected apps watching a project.
 */
private suspend fun broadcastToApps(projectId: String, frameBytes: ByteArray) {
    val appSessions = SessionRegistry.getAppSessionsForProject(projectId)

    appSessions.forEach { appSession ->
        try {
            appSession.session.send(Frame.Binary(true, frameBytes))
        } catch (e: Exception) {
            logger.warn("Failed to broadcast to userId=${appSession.userId} — removing session")
            SessionRegistry.unregisterApp(appSession.userId, appSession.session)
        }
    }
}

/**
 * Reads the initial handshake of the ESP.
 * Format : TOKEN_LEN(1B) | TOKEN_BYTES
 */
/**
 * Result of the device handshake.
 *
 * @param token UUID of the device (authentication key, hashed in DB)
 * @param heartbeatMs interval declared by the Arduino lib via
 *                    `Instant.setHeartbeat(ms)`. `null` = legacy
 *                    device that did not announce an interval
 *                    (server falls back to a 90s soTimeout).
 */
private data class HandshakeResult(
    val token: String,
    val heartbeatMs: Long?
)

/**
 * Reads the handshake sent by the device.
 *
 * Format : 1 byte length prefix + N bytes UTF-8.
 *
 * The payload is either :
 * - `"tokenUUID"` (legacy, no heartbeat announced)
 * - `"tokenUUID:heartbeatMs"` (new, Arduino lib ≥ 0.x with
 *   `setHeartbeat(ms)`)
 *
 * The parser splits on the first `:` only — the tokens
 * themselves (UUID v4) never contain a `:`.
 */
private fun readDeviceHandshake(inputStream: InputStream): HandshakeResult? {
    return try {
        val payloadLength = inputStream.read()
        if (payloadLength <= 0) return null

        val payloadBytes = ByteArray(payloadLength)
        var totalBytesRead = 0
        while (totalBytesRead < payloadLength) {
            val bytesRead = inputStream.read(payloadBytes, totalBytesRead, payloadLength - totalBytesRead)
            if (bytesRead == -1) return null
            totalBytesRead += bytesRead
        }

        val raw = String(payloadBytes, Charsets.UTF_8)
        val parts = raw.split(":", limit = 2)
        val token = parts[0]
        val heartbeatMs = parts.getOrNull(1)?.toLongOrNull()
        HandshakeResult(token = token, heartbeatMs = heartbeatMs)
    } catch (e: Exception) {
        null
    }
}

/**
 * Reads a complete binary frame from the TCP stream.
 *
 * Format : AA(1) | VER(1) | LEN(2 LE) | body(LEN) | CRC(1)
 * Returns null if the connection is closed or the 90s timeout is exceeded.
 */
private fun readFrame(inputStream: InputStream): ByteArray? {
    return try {
        // AA — sync byte
        val syncByte = inputStream.read()
        if (syncByte == -1 || syncByte != 0xAA) return null

        // VER
        val versionByte = inputStream.read()
        if (versionByte == -1) return null

        // LEN (2B little-endian)
        val lenLow  = inputStream.read()
        val lenHigh = inputStream.read()
        if (lenLow == -1 || lenHigh == -1) return null
        val bodyLength = lenLow or (lenHigh shl 8)

        // reject frames that are too large — protection against malicious devices
        if (bodyLength > MAX_FRAME_BODY_SIZE) return null

        // body
        val bodyBytes = ByteArray(bodyLength)
        var totalBytesRead = 0
        while (totalBytesRead < bodyLength) {
            val bytesRead = inputStream.read(bodyBytes, totalBytesRead, bodyLength - totalBytesRead)
            if (bytesRead == -1) return null
            totalBytesRead += bytesRead
        }

        // CRC
        val crcByte = inputStream.read()
        if (crcByte == -1) return null

        // assemble the complete frame
        byteArrayOf(
            syncByte.toByte(),
            versionByte.toByte(),
            lenLow.toByte(),
            lenHigh.toByte(),
        ) + bodyBytes + byteArrayOf(crcByte.toByte())

    } catch (e: Exception) {
        // SocketTimeoutException if 90s without data — device offline
        null
    }
}