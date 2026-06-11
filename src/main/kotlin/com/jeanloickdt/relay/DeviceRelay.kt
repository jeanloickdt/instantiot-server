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
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.server.application.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readFully
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("DeviceRelay")

// Max body size of a TCP frame — protects against malicious frames.
// The length field is attacker-controlled: this bound is validated BEFORE the
// body ByteArray is allocated (no unbounded allocation from a crafted LEN).
private const val MAX_FRAME_BODY_SIZE = 1024

// ════════════════════════════════════════════════════════════════════
// Session timeouts (now enforced with withTimeoutOrNull on the suspending
// reads — there is no blocking-socket soTimeout anymore).
// ════════════════════════════════════════════════════════════════════

// Provisional window to read the handshake (before knowing the heartbeat).
private const val HANDSHAKE_TIMEOUT_MS = 10_000L

// Lower / upper clamps for the post-handshake read timeout.
private const val MIN_SESSION_TIMEOUT_MS = 2_000L
private const val MAX_SESSION_TIMEOUT_MS = 120_000L

// Legacy fallback — device that does not announce `:heartbeatMs`.
private const val LEGACY_SESSION_TIMEOUT_MS = 90_000L

// ════════════════════════════════════════════════════════════════════
// iWidgets v1 protocol — type byte dedicated to the heartbeat
// ════════════════════════════════════════════════════════════════════

// Type = 0xFE : heartbeat frame. Validated then dropped (no dispatch); its only
// effect is that receiving its bytes resets the read timeout.
internal const val TYPE_HEARTBEAT: UByte = 0xFEu

/**
 * Non-blocking TCP relay for ESP32/ESP8266 device connections (ktor-network).
 *
 * Protocol:
 *   1. ESP opens a TCP connection → tcpPort
 *   2. ESP sends handshake `[TOKEN_LEN(1B) | TOKEN_BYTES]` (token or token:heartbeatMs)
 *   3. Server verifies SHA-256(token) → DeviceTable
 *   4. Authenticated → session registered in ConnectionRegistry + presence online
 *   5. ESP streams iWidgets v1 binary frames; the server extracts/aggregates them
 *      and broadcasts the intact frame to the project's apps
 *   6. Disconnect / timeout → offline
 *
 * Each device runs in its own coroutine, child of a SupervisorJob: a failure on
 * one connection NEVER cancels the accept loop or the other devices. Reads are
 * SUSPENDING (ByteReadChannel), so thousands of idle connections cost a handful
 * of threads, not one thread each — no dedicated pool, no Semaphore cap.
 */
fun Application.startDeviceRelay(
    deviceRepository: DeviceRepository,
    connections: ConnectionRegistry,
    buffers: HistoryBuffers,
    lastValues: LastValueCache,
    presence: PresenceStore,
    events: ControlEventBroadcaster,
    tcpPort: Int = 9001
) {
    // SupervisorJob: device↔device isolation (a crashing connection does not
    // cancel its siblings or the accept loop). Dispatchers.Default — reads are
    // suspending; DB work is explicitly offloaded to Dispatchers.IO.
    val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val selectorManager = SelectorManager(Dispatchers.IO)

    relayScope.launch {
        val serverSocket = aSocket(selectorManager).tcp().bind(port = tcpPort)
        logger.info("Device TCP relay (non-blocking) listening on port $tcpPort")

        // Clean shutdown: close the listener + selector and cancel the scope so
        // accept() and every in-flight connection unwind.
        monitor.subscribe(ApplicationStopping) {
            runCatching { serverSocket.close() }
            runCatching { selectorManager.close() }
            relayScope.cancel()
        }

        while (true) {
            try {
                val socket = serverSocket.accept()   // suspends until a device connects
                // child of the SupervisorJob → isolated per device
                relayScope.launch {
                    handleDeviceConnection(
                        socket           = socket,
                        deviceRepository = deviceRepository,
                        connections      = connections,
                        buffers          = buffers,
                        lastValues       = lastValues,
                        presence         = presence,
                        events           = events,
                        scope            = relayScope
                    )
                }
            } catch (e: CancellationException) {
                throw e   // shutdown — leave the loop
            } catch (e: Exception) {
                logger.error("Error accepting device connection — ${e.message}")
                // Backoff so a repeated accept failure (e.g. FD exhaustion)
                // does not become a tight CPU-pegging spin loop.
                delay(100)
            }
        }
    }
}

/**
 * Full lifecycle of one ESP connection. Runs in its own coroutine (child of the
 * relay SupervisorJob). Reads are suspending; the per-frame work is sequential
 * (natural backpressure: the next read only happens after the previous frame is
 * handled) and pure RAM/CPU — no DB on the read path.
 */
private suspend fun handleDeviceConnection(
    socket: Socket,
    deviceRepository: DeviceRepository,
    connections: ConnectionRegistry,
    buffers: HistoryBuffers,
    lastValues: LastValueCache,
    presence: PresenceStore,
    events: ControlEventBroadcaster,
    scope: CoroutineScope
) {
    val deviceAddress = socket.remoteAddress.toString()
    val readCh: ByteReadChannel = socket.openReadChannel()
    val writeCh = socket.openWriteChannel(autoFlush = false)

    var registered: DeviceRow? = null
    try {
        // ── Handshake (bounded by HANDSHAKE_TIMEOUT_MS) ──
        val handshake = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { readDeviceHandshake(readCh) }
        if (handshake == null) {
            logger.warn("Invalid/slow handshake from $deviceAddress — closing connection")
            return
        }

        val sessionTimeoutMs = handshake.heartbeatMs?.let { hb ->
            (hb * 25 / 10).coerceIn(MIN_SESSION_TIMEOUT_MS, MAX_SESSION_TIMEOUT_MS)
        } ?: LEGACY_SESSION_TIMEOUT_MS

        // ── Auth (DB lookup off the read path, on Dispatchers.IO) ──
        val tokenHash = FrameParser.hashDeviceToken(handshake.token)
        val device = withContext(Dispatchers.IO) { deviceRepository.findByTokenHash(tokenHash) }
        if (device == null) {
            logger.warn("Unknown device token from $deviceAddress — closing connection")
            return
        }

        // ── Register (after this point the finally always cleans up) ──
        connections.registerDevice(device.id, device, socket, writeCh, scope)
        registered = device
        runCatching { presence.markOnline(device.id, System.currentTimeMillis()) }
        logger.info("Device connected — deviceId=${device.id} name=${device.name} address=$deviceAddress " +
            "heartbeat=${handshake.heartbeatMs ?: "legacy"}ms timeout=${sessionTimeoutMs}ms")
        events.deviceOnline(device.projectId, device.id, device.name)

        // ── Read loop (sequential, RAM/CPU only) ──
        while (true) {
            val frame = withTimeoutOrNull(sessionTimeoutMs) { readFrame(readCh) } ?: break
            if (!FrameParser.isValid(frame)) {
                logger.warn("Invalid frame from device=${device.id} — ignored")
                continue
            }
            // inline + sequential: one bad frame is isolated inside handleDeviceFrame
            handleDeviceFrame(frame, device, connections, buffers, lastValues)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Error handling device from $deviceAddress — ${e.message}")
    } finally {
        // Hardened cleanup: close the socket FIRST (never skipped by a later
        // failure), then each step in its own runCatching. The session-side
        // cleanup is suspending (presence DB write, offline WS broadcast), so it
        // runs under NonCancellable to survive shutdown cancellation.
        runCatching { socket.close() }
        registered?.let { dev ->
            withContext(NonCancellable) {
                runCatching { connections.unregisterDevice(dev.id) }
                runCatching { presence.markOffline(dev.id) }
                runCatching {
                    events.deviceOffline(dev.projectId, dev.id, DeviceOfflineReason.DISCONNECTED)
                }
            }
            logger.info("Device disconnected — deviceId=${dev.id}")
        }
    }
}

/**
 * Processes one validated binary frame. Pure RAM/CPU on the read path:
 *   - heartbeat → return early
 *   - extract widgetId + payload → LastValueCache (RAM) + history buffer (RAM)
 *   - numeric value (validated finite) → 3 RAM aggregators (+ opt-in raw buffer)
 *   - broadcast the intact frame to the apps
 * No DB write here (last_payload is coalesced into the 5s flush). The only DB
 * touch is the RARE, gated auto-register, fired off the read path.
 *
 * Wrapped in try/catch: a single malformed/aberrant frame logs and is skipped —
 * it never tears down the connection (that isolation is per-frame, complementing
 * the per-device SupervisorJob).
 */
private suspend fun handleDeviceFrame(
    frameBytes: ByteArray,
    device: DeviceRow,
    connections: ConnectionRegistry,
    buffers: HistoryBuffers,
    lastValues: LastValueCache
) {
    try {
        // Heartbeat (0xFE): byte reception already reset the read timeout — drop.
        val type = FrameParser.extractType(frameBytes)
        if (type == TYPE_HEARTBEAT.toInt()) return

        val widgetId      = FrameParser.extractWidgetId(frameBytes) ?: return
        val payloadBytes  = FrameParser.extractPayload(frameBytes)  ?: return
        val payloadBase64 = FrameParser.encodePayloadToBase64(payloadBytes)
        val now           = System.currentTimeMillis()

        // Strict model: the server only serves DECLARED widgets. The app is the
        // single source of truth — it declares widgets (POST /widgets, which the
        // cache-aware repo adds to knownWidgetIds), and knownWidgetIds is seeded
        // from the table at boot. A frame for an UNDECLARED (owner, widget) has
        // no recipient: no widget on a dashboard awaits it, no row should store
        // it (a firmware typo, or a widget removed from the dash but left in the
        // device code). It is noise — drop it before any persistence,
        // aggregation or live relay. (Replaces the old auto-register.)
        if (WidgetKey(device.ownerId, widgetId) !in buffers.knownWidgetIds) return

        // RAM-only writes — keyed by (ownerId, widgetId), never widgetId alone
        lastValues.put(device.ownerId, widgetId, payloadBase64, now)
        buffers.historyBuffer.add(
            HistoryEntry(widgetId, device.projectId, device.ownerId, payloadBase64, now)
        )

        // Numeric value → aggregators. GUARD: a non-finite value (NaN / ±Inf,
        // reachable from a CRC-valid frame whose float bits decode to NaN) would
        // silently and permanently poison min/max/avg — reject it here.
        FrameParser.extractNumericValue(frameBytes)?.let { sample ->
            if (!sample.value.isFinite()) {
                logger.warn("Dropping non-finite sample widget=$widgetId series=${sample.seriesId} value=${sample.value}")
                return@let
            }
            if (ServerConfig.historyRawEnabled) {
                buffers.numericHistoryBuffer.add(
                    NumericHistoryEntry(widgetId, device.projectId, device.ownerId, sample.seriesId, sample.value, now)
                )
            }
            HistoryAggregators.minute.collect(widgetId, sample.seriesId, now, sample.value, device.projectId, device.ownerId)
            HistoryAggregators.hour.collect(widgetId, sample.seriesId, now, sample.value, device.projectId, device.ownerId)
            HistoryAggregators.day.collect(widgetId, sample.seriesId, now, sample.value, device.projectId, device.ownerId)
        }

        // Broadcast the intact frame to the apps watching this project.
        dispatchToApps(connections, device.projectId, frameBytes)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Frame processing failed for device=${device.id} — ${e.message}")
    }
}

/**
 * Single dispatch point for device→app frames (seam: if a slow-app staller is
 * ever observed, a per-app outbox plugs in HERE without touching the read loop).
 */
private suspend fun dispatchToApps(connections: ConnectionRegistry, projectId: String, frameBytes: ByteArray) {
    broadcastToApps(connections, projectId, frameBytes)
}

/** Broadcasts a binary frame to all connected apps watching a project. */
private suspend fun broadcastToApps(connections: ConnectionRegistry, projectId: String, frameBytes: ByteArray) {
    val appSessions = connections.getAppSessionsForProject(projectId)
    appSessions.forEach { appSession ->
        try {
            appSession.session.send(Frame.Binary(true, frameBytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to broadcast to userId=${appSession.userId} — removing session")
            connections.unregisterApp(appSession.userId, appSession.session)
        }
    }
}

/**
 * Result of the device handshake.
 *
 * @param token UUID of the device (authentication key, hashed in DB)
 * @param heartbeatMs interval declared by the Arduino lib via `setHeartbeat(ms)`;
 *                    `null` for a legacy device (→ 90s read timeout).
 */
private data class HandshakeResult(
    val token: String,
    val heartbeatMs: Long?
)

/**
 * Reads the handshake: 1-byte length prefix (≤255 intrinsically) + N UTF-8 bytes.
 * Payload is `"token"` (legacy) or `"token:heartbeatMs"` (split on the first `:`,
 * UUIDs never contain `:`). EOF/closed/malformed → null.
 */
private suspend fun readDeviceHandshake(channel: ByteReadChannel): HandshakeResult? {
    return try {
        val length = channel.readByte().toInt() and 0xFF
        if (length <= 0) return null
        val bytes = ByteArray(length)
        channel.readFully(bytes)   // suspends until full; throws on premature close
        val raw = String(bytes, Charsets.UTF_8)
        val parts = raw.split(":", limit = 2)
        HandshakeResult(token = parts[0], heartbeatMs = parts.getOrNull(1)?.toLongOrNull())
    } catch (e: CancellationException) {
        throw e   // timeout / shutdown — must propagate (else withTimeoutOrNull breaks)
    } catch (e: Exception) {
        null      // EOFException (disconnect) / malformed
    }
}

/**
 * Reads a complete iWidgets v1 frame: AA(1) | VER(1) | LEN(2 LE) | body(LEN) | CRC8(1).
 *
 * LEN is read as two bytes reassembled little-endian (NOT readShort(), which is
 * big-endian). The MAX_FRAME_BODY_SIZE bound is validated BEFORE allocating the
 * body. readFully suspends until the body is complete → TCP fragmentation is
 * handled transparently. Returns null on timeout (withTimeoutOrNull cancels the
 * read) / EOF / malformed → caller breaks the loop and the device goes offline.
 */
private suspend fun readFrame(channel: ByteReadChannel): ByteArray? {
    return try {
        val sync = channel.readByte().toInt() and 0xFF
        if (sync != 0xAA) return null

        val version = channel.readByte()

        // LEN — 2 bytes little-endian, reassembled by hand
        val lenLow  = channel.readByte().toInt() and 0xFF
        val lenHigh = channel.readByte().toInt() and 0xFF
        val bodyLength = lenLow or (lenHigh shl 8)

        // bound BEFORE allocation — no unbounded ByteArray from a crafted LEN
        if (bodyLength > MAX_FRAME_BODY_SIZE) return null

        val body = ByteArray(bodyLength)
        channel.readFully(body)   // suspends until full — reassembles fragments

        val crc = channel.readByte()

        byteArrayOf(0xAA.toByte(), version, lenLow.toByte(), lenHigh.toByte()) + body + byteArrayOf(crc)
    } catch (e: CancellationException) {
        throw e   // read timeout / shutdown — propagate so the timeout fires & we break
    } catch (e: Exception) {
        null      // EOFException (disconnect) / malformed
    }
}
