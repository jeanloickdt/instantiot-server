// relay/DeviceRelay.kt
package com.jeanloickdt.relay

import com.jeanloickdt.common.ServerConfig
import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.device.domain.DeviceRow
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

// Taille max du body d'une trame TCP — protège contre les frames malveillantes
private const val MAX_FRAME_BODY_SIZE = 1024

// ════════════════════════════════════════════════════════════════════
// Session timeouts
// ════════════════════════════════════════════════════════════════════

// Fenêtre provisoire pour lire le handshake (avant de connaître le
// heartbeat déclaré par le device). Évite qu'un client muet bloque un
// thread indéfiniment.
private const val HANDSHAKE_TIMEOUT_MS = 10_000

// Borne basse du soTimeout post-handshake. Protège contre un device qui
// déclare heartbeat=50ms → timeout 125ms qui timeout-loop immédiatement.
private const val MIN_SESSION_TIMEOUT_MS = 2_000L

// Borne haute du soTimeout post-handshake. Protège contre un device qui
// déclare heartbeat=1h → détection offline inutilement lente.
private const val MAX_SESSION_TIMEOUT_MS = 120_000L

// Fallback legacy — device qui n'envoie pas `:heartbeatMs` au handshake.
// Valeur historique (90s) pour garder la compat.
private const val LEGACY_SESSION_TIMEOUT_MS = 90_000L

// ════════════════════════════════════════════════════════════════════
// Protocole iWidgets v1 — type byte dédié au heartbeat
// ════════════════════════════════════════════════════════════════════

// Type = 0xFE : trame de heartbeat émise par le device toutes les
// `heartbeatMs` (lib Arduino). Le serveur la valide (CRC/sync) mais
// **ne dispatch pas** — pas d'event widget, pas de persist. Sa seule
// fonction côté serveur est de reset le soTimeout (ce que l'OS fait
// automatiquement dès qu'on reçoit un byte).
internal const val TYPE_HEARTBEAT: UByte = 0xFEu

/**
 * TCP relay — connexions devices ESP32/ESP8266.
 *
 * Protocol :
 *   1. ESP ouvre connexion TCP → port 9001
 *   2. ESP envoie handshake : [TOKEN_LEN(1B) | TOKEN_BYTES]
 *   3. Server vérifie SHA-256(token) → DeviceTable
 *   4. Device authentifié → session enregistrée dans SessionRegistry
 *   5. ESP envoie trames binaires iWidgets v1 en continu
 *   6. Server extrait widgetId + payload → lastPayloads + historyBuffer
 *   7. Server broadcast trame aux apps qui regardent ce projet
 *   8. ESP se déconnecte → device.isOnline = false → session retirée
 *
 * Chaque connexion ESP tourne dans sa propre coroutine IO — non-bloquant.
 * 50 ESP connectés = 50 coroutines légères.
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

        // fermer proprement le ServerSocket au shutdown
        monitor.subscribe(ApplicationStopping) {
            serverSocket.close()
        }

        while (!serverSocket.isClosed) {
            try {
                val clientSocket = serverSocket.accept()

                // chaque device dans sa propre coroutine — isolation totale
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
 * Gère la connexion complète d'un device ESP.
 * Tourne dans sa propre coroutine — isolation totale entre devices.
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

        // keepalive TCP — OS détecte si le device disparaît sans fermer le socket
        clientSocket.keepAlive = true
        // soTimeout provisoire avant handshake (évite de bloquer sur un client muet)
        clientSocket.soTimeout = HANDSHAKE_TIMEOUT_MS

        // handshake — format : "token" (legacy, 90s timeout) ou
        //                       "token:heartbeatMs" (nouveau, soTimeout adaptatif)
        val handshake = readDeviceHandshake(inputStream)
        if (handshake == null) {
            logger.warn("Invalid handshake from $deviceAddress — closing connection")
            clientSocket.close()
            return
        }

        // Post-handshake : ajuster le soTimeout selon le heartbeat annoncé.
        // - Si heartbeat déclaré : soTimeout = heartbeat × 2.5 (tolère 2 miss + jitter)
        // - Sinon (legacy) : 90s fallback
        val sessionTimeoutMs = handshake.heartbeatMs?.let { hb ->
            (hb * 25 / 10).coerceIn(MIN_SESSION_TIMEOUT_MS, MAX_SESSION_TIMEOUT_MS)
        } ?: LEGACY_SESSION_TIMEOUT_MS
        clientSocket.soTimeout = sessionTimeoutMs.toInt()
        logger.info("Handshake OK — token=${handshake.token.take(8)}… heartbeat=${handshake.heartbeatMs ?: "legacy"}ms timeout=${sessionTimeoutMs}ms")

        // vérifier le token — SHA-256 lookup dans DeviceTable
        val tokenHash = FrameParser.hashDeviceToken(handshake.token)
        val device = withContext(Dispatchers.IO) {
            deviceRepository.findByTokenHash(tokenHash)
        }

        if (device == null) {
            logger.warn("Unknown device token from $deviceAddress — closing connection")
            clientSocket.close()
            return
        }

        // device authentifié — enregistrer la session + marquer online.
        // On passe `applicationScope` à l'outbox → sa coroutine
        // consommatrice survit à la coroutine `handleDeviceConnection`
        // et s'arrête proprement via `unregisterDevice`.
        SessionRegistry.registerDevice(device.id, device, clientSocket, applicationScope)
        withContext(Dispatchers.IO) {
            deviceRepository.updateOnlineStatus(device.id, isOnline = true)
            deviceRepository.updateLastSeen(device.id, System.currentTimeMillis())
        }
        logger.info("Device connected — deviceId=${device.id} name=${device.name} address=$deviceAddress")

        // broadcast device_online aux apps du projet
        ControlEventBroadcaster.deviceOnline(
            projectId  = device.projectId,
            deviceId   = device.id,
            deviceName = device.name
        )

        // écouter les trames binaires en continu
        try {
            while (!clientSocket.isClosed) {
                val frameBytes = readFrame(inputStream) ?: break

                if (!FrameParser.isValid(frameBytes)) {
                    logger.warn("Invalid frame from device=${device.id} — ignored")
                    continue
                }

                // traitement dans Dispatchers.Default — parsing CPU non-bloquant
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
            // déconnexion — marquer offline + retirer session
            SessionRegistry.unregisterDevice(device.id)
            withContext(Dispatchers.IO) {
                deviceRepository.updateOnlineStatus(device.id, isOnline = false)
            }
            logger.info("Device disconnected — deviceId=${device.id}")
            clientSocket.close()

            // broadcast device_offline aux apps du projet
            // reason = DISCONNECTED (normal TCP disconnect ou timeout)
            // Si renew-token ou delete a deja broadcast avec un reason specifique,
            // l'app recoit 2 events — acceptable, elle deduplique sur deviceId offline.
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
 * Traite une trame binaire reçue d'un ESP.
 *
 * Flow :
 *   1. Extraire widgetId + payload
 *   2. Mettre à jour lastPayloads en RAM — sub-milliseconde
 *   3. Ajouter au buffer history — flush toutes les 5s vers SQLite WAL batch
 *   4. Mettre à jour last_payload en DB — asynchrone non-bloquant
 *   5. Broadcast trame intacte aux apps qui regardent ce projet
 *
 * La DB n'est jamais dans le chemin critique du relay.
 */
private suspend fun handleDeviceFrame(
    frameBytes: ByteArray,
    device: DeviceRow,
    widgetRepository: WidgetRepository,
    applicationScope: CoroutineScope
) {
    // Heartbeat (TYPE = 0xFE) : la réception du byte reset automatiquement
    // le soTimeout OS — aucune DB ni broadcast, on retourne early.
    // `last_seen` était déjà MAJ au connect et sera updaté sur la prochaine
    // vraie trame widget. Pas besoin de flood la DB pour chaque heartbeat.
    val type = FrameParser.extractType(frameBytes)
    if (type == TYPE_HEARTBEAT.toInt()) return

    val widgetId      = FrameParser.extractWidgetId(frameBytes) ?: return
    val payloadBytes  = FrameParser.extractPayload(frameBytes)  ?: return
    val payloadBase64 = FrameParser.encodePayloadToBase64(payloadBytes)
    val now           = System.currentTimeMillis()

    // mettre à jour lastPayloads en RAM — accès sub-milliseconde
    SessionRegistry.lastPayloads[widgetId] = payloadBase64

    // ajouter au buffer history — flush toutes les 5s vers SQLite WAL batch
    SessionRegistry.historyBuffer.add(
        HistoryEntry(
            widgetId   = widgetId,
            projectId  = device.projectId,
            ownerId    = device.ownerId,
            payload    = payloadBase64,
            recordedAt = now
        )
    )

    // historique NUMÉRIQUE — décoder la valeur si le widget est analogique
    // (gauge/metric/level/slider/chart). Throttle par (widgetId, seriesId).
    FrameParser.extractNumericValue(frameBytes)?.let { sample ->
        val throttleKey = widgetId + "|" + (sample.seriesId ?: "")
        val lastWriteAt = SessionRegistry.numericThrottleMap[throttleKey]
        val intervalMs  = ServerConfig.historyThrottleRawIntervalMs
        if (lastWriteAt == null || (now - lastWriteAt) >= intervalMs) {
            SessionRegistry.numericThrottleMap[throttleKey] = now
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
    }

    // mettre à jour last_payload en DB — asynchrone non-bloquant
    applicationScope.launch(Dispatchers.IO) {
        widgetRepository.updateLastPayload(widgetId, payloadBase64, now)
    }

    // broadcast trame intacte aux apps qui regardent ce projet
    broadcastToApps(device.projectId, frameBytes)
}

/**
 * Broadcast une trame binaire à toutes les apps connectées qui regardent un projet.
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
 * Lit le handshake initial de l'ESP.
 * Format : TOKEN_LEN(1B) | TOKEN_BYTES
 */
/**
 * Résultat du handshake device.
 *
 * @param token UUID du device (clé d'authentification, hash en DB)
 * @param heartbeatMs intervalle déclaré par la lib Arduino via
 *                    `Instant.setHeartbeat(ms)`. `null` = device
 *                    legacy qui n'a pas annoncé d'intervalle
 *                    (serveur fallback à 90s de soTimeout).
 */
private data class HandshakeResult(
    val token: String,
    val heartbeatMs: Long?
)

/**
 * Lit le handshake envoyé par le device.
 *
 * Format : 1 byte length prefix + N bytes UTF-8.
 *
 * La payload est soit :
 * - `"tokenUUID"` (legacy, pas de heartbeat annoncé)
 * - `"tokenUUID:heartbeatMs"` (nouveau, lib Arduino ≥ 0.x avec
 *   `setHeartbeat(ms)`)
 *
 * Le parseur split sur le premier `:` uniquement — les tokens
 * eux-mêmes (UUID v4) ne contiennent jamais de `:`.
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
 * Lit une trame binaire complète depuis le stream TCP.
 *
 * Format : AA(1) | VER(1) | LEN(2 LE) | body(LEN) | CRC(1)
 * Retourne null si connexion fermée ou timeout 90s dépassé.
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

        // rejet des frames trop grandes — protection contre les devices malveillants
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

        // assembler la trame complète
        byteArrayOf(
            syncByte.toByte(),
            versionByte.toByte(),
            lenLow.toByte(),
            lenHigh.toByte(),
        ) + bodyBytes + byteArrayOf(crcByte.toByte())

    } catch (e: Exception) {
        // SocketTimeoutException si 90s sans données — device offline
        null
    }
}