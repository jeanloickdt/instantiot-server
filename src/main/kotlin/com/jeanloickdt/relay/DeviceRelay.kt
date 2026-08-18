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
    /**
     * Rule-event producers. Null = publish nothing (self-hosted pays zero).
     * [watchedWidgets] gates WidgetValue at the producer: without it, 100 % of
     * the traffic would be pushed toward an engine that discards 99 % of it.
     * Defaults to "nobody watches" until the rule cache exists.
     */
    sinks: com.jeanloickdt.event.EventSinks? = null,
    watchedWidgets: (WidgetKey) -> Boolean = { false },
    /** The messages.perMonth ledger — one RAM bump per accepted data frame. */
    usage: com.jeanloickdt.automation.MessageUsageCounter? = null,
    /**
     * The 2.0 value store. Null keeps the node on the widget path only — a
     * SIGNAL frame is then dropped rather than half-handled.
     */
    signals: com.jeanloickdt.signal.domain.SignalRepository? = null,
    tcpPort: Int = 9001
) {
    // SupervisorJob: device↔device isolation (a crashing connection does not
    // cancel its siblings or the accept loop). Dispatchers.Default — reads are
    // suspending; DB work is explicitly offloaded to Dispatchers.IO.
    val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val selectorManager = SelectorManager(Dispatchers.IO)

    // The fuse ceiling — hardcoded: the fuse protects the machine from one
    // faulty board, and a loop() without delay() does not care who hosts you.
    // (The cloud edition lets its plan file TIGHTEN this; never create it.)
    val frameRate = FrameRateLimiter.DEFAULT_RATE_PER_SECOND
    logger.info("Device frame fuse: $frameRate frames/s per board (burst ${frameRate * 2})")

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
                        frameRatePerSecond = frameRate,
                        sinks            = sinks,
                        watchedWidgets   = watchedWidgets,
                        usage            = usage,
                        signals          = signals,
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
    frameRatePerSecond: Int,
    sinks: com.jeanloickdt.event.EventSinks?,
    watchedWidgets: (WidgetKey) -> Boolean,
    usage: com.jeanloickdt.automation.MessageUsageCounter?,
    signals: com.jeanloickdt.signal.domain.SignalRepository?,
    scope: CoroutineScope
) {
    val deviceAddress = socket.remoteAddress.toString()
    val readCh: ByteReadChannel = socket.openReadChannel()
    val writeCh = socket.openWriteChannel(autoFlush = false)

    var registered: DeviceRow? = null
    var rateLimited = false
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
        sinks?.publish(com.jeanloickdt.event.RelayEvent.DeviceOnline(
            device.ownerId, device.id, System.currentTimeMillis()
        ))

        // ── Read loop (sequential, RAM/CPU only) ──
        // One fuse per connection, owned by this coroutine — no shared state,
        // freed with the socket. See FrameRateLimiter for why per device.
        //
        // The heartbeat fuse is separate and two orders of magnitude wider:
        // exempting heartbeats from the data fuse is right (dropping one would
        // make the fuse look like a network timeout) but without its own
        // ceiling the exemption is a hole — 10 000 heartbeats/s bounded by
        // nothing.
        val fuse = FrameRateLimiter(frameRatePerSecond)
        val heartbeatFuse = FrameRateLimiter(FrameRateLimiter.HEARTBEAT_RATE_PER_SECOND)
        while (true) {
            val frame = withTimeoutOrNull(sessionTimeoutMs) { readFrame(readCh) } ?: break
            if (!FrameParser.isValid(frame)) {
                logger.warn("Invalid frame from device=${device.id} — ignored")
                continue
            }
            // Monotonic, not wall-clock: an NTP correction that steps the wall
            // clock backwards would freeze the refill until it caught up, and a
            // perfectly healthy board would burn its burst, open an abuse
            // streak, and be evicted for a problem that is not its own.
            val now = System.nanoTime() / 1_000_000
            val isHeartbeat = FrameParser.extractType(frame) == TYPE_HEARTBEAT.toInt()
            val gate = if (isHeartbeat) heartbeatFuse else fuse
            if (!gate.tryAcquire(now)) {
                if (gate.dropped == 1L) {
                    logger.warn("Device ${device.id} is over budget (${if (isHeartbeat) "heartbeats" else "frames"}) — dropping (a sketch without delay()?)")
                }
                if (gate.shouldDisconnect(now)) {
                    rateLimited = true
                    logger.warn("Device ${device.id} flooded severely for 30 s straight (${gate.dropped} dropped) — closing")
                    break
                }
                continue   // frame dropped, socket kept — never a delay
            }
            // Accepted data frames feed the messages.perMonth ledger — one
            // RAM bump; heartbeats and dropped frames never count.
            if (!isHeartbeat) usage?.increment(device.ownerId)
            // inline + sequential: one bad frame is isolated inside handleDeviceFrame
            handleDeviceFrame(frame, device, connections, buffers, lastValues, sinks, watchedWidgets, signals)
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
                // EVERY offline effect is gated on still owning the slot — not
                // just the removal. A zombie whose timeout fires after the
                // board reconnected must touch nothing: no red dot in the app
                // (markOffline + deviceOffline), and no false DeviceOffline
                // into the RULE feed — or "warn me if the greenhouse drops"
                // would push a lie about a live board.
                if (connections.unregisterDevice(dev.id, socket)) {
                    runCatching { presence.markOffline(dev.id) }
                    runCatching {
                        // rate_limited tells the owner WHY: a silent cap creates a
                        // support ticket, an explained one fixes the sketch.
                        val reason = if (rateLimited) DeviceOfflineReason.RATE_LIMITED
                                     else DeviceOfflineReason.DISCONNECTED
                        events.deviceOffline(dev.projectId, dev.id, reason)

                        val at = System.currentTimeMillis()
                        sinks?.publish(com.jeanloickdt.event.RelayEvent.DeviceOffline(
                            dev.ownerId, dev.id, reason, at
                        ))
                        // The fuse eviction is ALSO a system event: "your board is
                        // broken" is a fact a rule may want to act on, distinct
                        // from a mere disconnection.
                        if (rateLimited) {
                            sinks?.publish(com.jeanloickdt.event.RelayEvent.DeviceRejected(
                                dev.ownerId, dev.id, DeviceOfflineReason.RATE_LIMITED, at
                            ))
                        }
                    }
                    logger.info("Device disconnected — deviceId=${dev.id}")
                } else {
                    // Supplanted: the board already reconnected on a newer
                    // socket. If this line loops for one deviceId, it is two
                    // boards flashed with the SAME token evicting each other —
                    // not a network problem.
                    logger.info("Stale connection closed — deviceId=${dev.id} (supplanted; fast reconnect, or a duplicated token?)")
                }
            }
        }
    }
}

/**
 * Processes one validated binary frame. Pure RAM/CPU on the read path:
 *   - heartbeat → return early
 *   - strict model: drop the frame if (ownerId, widgetId) is not a declared widget
 *   - extract widgetId + payload → LastValueCache (RAM) + history buffer (RAM)
 *   - numeric value (validated finite) → 3 RAM aggregators (+ opt-in raw buffer)
 *   - broadcast the intact frame to the apps
 * No DB write here at all: last_payload is coalesced into the 5s flush, and a
 * frame for an undeclared widget is dropped by the strict-model guard before any
 * RAM write.
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
    lastValues: LastValueCache,
    sinks: com.jeanloickdt.event.EventSinks? = null,
    watchedWidgets: (WidgetKey) -> Boolean = { false },
    signals: com.jeanloickdt.signal.domain.SignalRepository? = null
) {
    try {
        // Heartbeat (0xFE): byte reception already reset the read timeout — drop.
        val type = FrameParser.extractType(frameBytes)
        if (type == TYPE_HEARTBEAT.toInt()) return

        // SIGNAL (0x20) — the 2.0 value path. It rides this very layout, so the
        // branch sits here and the widget path below is untouched.
        if (type == com.jeanloickdt.signal.SignalFrame.TYPE_SIGNAL) {
            if (signals == null) return   // no signal store wired: not our frame
            val accepted = com.jeanloickdt.signal.ingestSignalFrame(
                frameBytes  = frameBytes,
                ownerId     = device.ownerId,
                deviceId    = device.id,
                deviceName  = device.name,
                projectId   = device.projectId,
                signals     = signals,
                buffers     = buffers,
                lastValues  = lastValues,
                // Self-hosted has no plan file: the raw tier is the operator's
                // switch alone, which ServerConfig already answers downstream.
                rawAllowed  = true,
                sinks       = sinks,
                watched     = watchedWidgets,
                nowMs       = System.currentTimeMillis()
            )
            // An undeclared address never reaches an app: it is noise, and
            // showing it would make the diagnostic harder, not easier.
            if (accepted) dispatchToApps(connections, device.projectId, frameBytes)
            return
        }

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
        // aggregation or live relay.
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
            // Raw tier — the most expensive setting there is per device: it
            // doubles disk growth (428 vs 202 bytes per frame, measured). Here
            // the operator's switch alone decides; the cloud edition adds a
            // per-account plan gate on top.
            if (ServerConfig.historyRawEnabled) {
                buffers.numericHistoryBuffer.add(
                    NumericHistoryEntry(widgetId, device.projectId, device.ownerId, sample.seriesId, sample.value, now)
                )
            }
            // Rule feed — gated at the producer: only widgets a rule actually
            // watches are published, so with no rules this line costs one
            // predicate call and nothing else.
            if (sinks != null && watchedWidgets(WidgetKey(device.ownerId, widgetId))) {
                sinks.publish(com.jeanloickdt.event.RelayEvent.WidgetValue(
                    device.ownerId, widgetId, sample.seriesId, sample.value, now
                ))
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
 * Single dispatch point for device→app frames. The predicted slow-app staller
 * is now handled: each session owns an [AppOutbox], so this never suspends.
 */
private fun dispatchToApps(connections: ConnectionRegistry, projectId: String, frameBytes: ByteArray) {
    broadcastToApps(connections, projectId, frameBytes)
}

/**
 * Hands a binary frame to every app watching a project.
 *
 * Non-suspending by contract: called from the device read coroutine, a single
 * unresponsive app would otherwise stop that device from being read at all —
 * its receive buffer fills, the TCP window closes, and the board can no longer
 * emit. The outbox absorbs the frame and drops the oldest under pressure.
 */
private fun broadcastToApps(connections: ConnectionRegistry, projectId: String, frameBytes: ByteArray) {
    connections.getAppSessionsForProject(projectId).forEach { appSession ->
        appSession.outbox.trySendTelemetry(frameBytes)
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
