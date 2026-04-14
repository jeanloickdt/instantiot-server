// relay/DeviceRelay.kt
package com.jeanloickdt.relay

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
        // timeout 90s — readFrame retourne null si rien reçu → device offline
        clientSocket.soTimeout = 90_000

        // handshake — lire le token envoyé par l'ESP
        val deviceToken = readDeviceToken(inputStream)
        if (deviceToken == null) {
            logger.warn("Invalid handshake from $deviceAddress — closing connection")
            clientSocket.close()
            return
        }

        // vérifier le token — SHA-256 lookup dans DeviceTable
        val tokenHash = FrameParser.hashDeviceToken(deviceToken)
        val device = withContext(Dispatchers.IO) {
            deviceRepository.findByTokenHash(tokenHash)
        }

        if (device == null) {
            logger.warn("Unknown device token from $deviceAddress — closing connection")
            clientSocket.close()
            return
        }

        // device authentifié — enregistrer la session + marquer online
        SessionRegistry.registerDevice(device.id, device, clientSocket)
        withContext(Dispatchers.IO) {
            deviceRepository.updateOnlineStatus(device.id, isOnline = true)
            deviceRepository.updateLastSeen(device.id, System.currentTimeMillis())
        }
        logger.info("Device connected — deviceId=${device.id} name=${device.name} address=$deviceAddress")

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
            SessionRegistry.unregisterApp(appSession.userId)
        }
    }
}

/**
 * Lit le handshake initial de l'ESP.
 * Format : TOKEN_LEN(1B) | TOKEN_BYTES
 */
private fun readDeviceToken(inputStream: InputStream): String? {
    return try {
        val tokenLength = inputStream.read()
        if (tokenLength <= 0) return null

        val tokenBytes = ByteArray(tokenLength)
        var totalBytesRead = 0
        while (totalBytesRead < tokenLength) {
            val bytesRead = inputStream.read(tokenBytes, totalBytesRead, tokenLength - totalBytesRead)
            if (bytesRead == -1) return null
            totalBytesRead += bytesRead
        }

        String(tokenBytes, Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}

/**
 * Lit une trame binaire complète depuis le stream TCP.
 *
 * Format : AA(1) | VER(1) | LEN(2 LE) | SEQ(1) | body(LEN) | CRC(1)
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

        // SEQ
        val seqByte = inputStream.read()
        if (seqByte == -1) return null

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
            seqByte.toByte()
        ) + bodyBytes + byteArrayOf(crcByte.toByte())

    } catch (e: Exception) {
        // SocketTimeoutException si 90s sans données — device offline
        null
    }
}