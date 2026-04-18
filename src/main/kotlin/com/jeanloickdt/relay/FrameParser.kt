// relay/FrameParser.kt
package com.jeanloickdt.relay

import java.security.MessageDigest
import java.util.Base64

/**
 * Parser de trames binaires iWidgets v1
 *
 * Format trame complète :
 * AA | VER(01) | LEN(2B LE) | SEQ(1B) | DEV_COUNT | [DEV_LEN|DEV_ID]×N | WID_LEN | WID | TYPE | EVENT | PAYLOAD... | CRC8
 *
 * Le server est un dumb relay — il ne lit jamais TYPE, EVENT ni le contenu du PAYLOAD.
 * Il extrait uniquement ce dont il a besoin pour router et stocker.
 *
 * Deux directions :
 *   App → Server → Device : trame avec DEV_COUNT + device UUIDs → trim → relay TCP
 *   Device → Server → App : trame avec DEV_COUNT=0 → extract widgetId + payload → broadcast WebSocket
 */
object FrameParser {

    // Taille minimale d'une trame valide
    // AA(1) + VER(1) + LEN(2) + SEQ(1) + body(min 1) + CRC(1) = 7
    private const val MIN_FRAME_SIZE = 7

    private const val SYNC_BYTE: Int    = 0xAA
    private const val VERSION_BYTE: Int = 0x01

    // Header fixe : AA + VER + LEN(2) + SEQ = 5 bytes
    private const val FIXED_HEADER_SIZE = 5

    // ================================================================
    // VALIDATION
    // ================================================================

    /**
     * Valide la trame — vérifie sync, version, taille minimale et CRC8.
     * Le CRC ajoute une couche de protection applicative en plus de TCP.
     */
    fun isValid(frame: ByteArray): Boolean {
        if (frame.size < MIN_FRAME_SIZE) return false
        if (frame[0].toInt() and 0xFF != SYNC_BYTE) return false
        if (frame[1].toInt() and 0xFF != VERSION_BYTE) return false

        // vérifier le CRC8 — calculé sur le body (entre le header fixe et le CRC final)
        val bodyLength = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        val expectedFrameSize = FIXED_HEADER_SIZE + bodyLength + 1 // +1 pour CRC
        if (frame.size < expectedFrameSize) return false

        val body = frame.copyOfRange(FIXED_HEADER_SIZE, FIXED_HEADER_SIZE + bodyLength)
        val computedCrc = computeCrc8(body)
        val receivedCrc = frame[FIXED_HEADER_SIZE + bodyLength].toInt() and 0xFF

        return computedCrc.toInt() == receivedCrc
    }

    // ================================================================
    // EXTRACTION — byte SEQ (correlation command_failed)
    // ================================================================

    /**
     * Extrait le byte SEQ de la trame (position 4, apres AA | VER | LEN).
     * Utilise pour la correlation des events command_failed cote app.
     * Retourne null si la trame est trop courte.
     */
    fun extractSeq(frame: ByteArray): Int? {
        if (frame.size < FIXED_HEADER_SIZE) return null
        return frame[4].toInt() and 0xFF
    }

    // ================================================================
    // EXTRACTION — Device → Server → App
    // L'ESP envoie DEV_COUNT=0 — pas de device cible dans cette direction
    // ================================================================

    /**
     * Extrait le widget ID de la trame envoyée par l'ESP.
     * Position : après AA | VER | LEN | SEQ | DEV_COUNT(0) | WID_LEN | WID
     */
    fun extractWidgetId(frame: ByteArray): String? {
        return try {
            var offset = FIXED_HEADER_SIZE

            // skip DEV_COUNT + [DEV_LEN|DEV_ID]×N
            val deviceCount = frame[offset++].toInt() and 0xFF
            repeat(deviceCount) {
                val deviceIdLength = frame[offset++].toInt() and 0xFF
                offset += deviceIdLength
            }

            // WID_LEN + WID
            val widgetIdLength = frame[offset++].toInt() and 0xFF
            frame.decodeToString(offset, offset + widgetIdLength)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extrait le PAYLOAD brut de la trame envoyée par l'ESP.
     * Le server ne comprend pas le contenu — bytes opaques stockés en base64.
     * Position : après WID | TYPE | EVENT — tout ce qui reste avant CRC
     */
    fun extractPayload(frame: ByteArray): ByteArray? {
        return try {
            var offset = FIXED_HEADER_SIZE

            // LEN — taille du body (little-endian)
            val bodyLength = ((frame[2].toInt() and 0xFF)) or
                    ((frame[3].toInt() and 0xFF) shl 8)

            // fin du body = FIXED_HEADER_SIZE + bodyLength
            val bodyEnd = FIXED_HEADER_SIZE + bodyLength

            // skip DEV_COUNT + [DEV_LEN|DEV_ID]×N
            val deviceCount = frame[offset++].toInt() and 0xFF
            repeat(deviceCount) {
                val deviceIdLength = frame[offset++].toInt() and 0xFF
                offset += deviceIdLength
            }

            // skip WID_LEN + WID
            val widgetIdLength = frame[offset++].toInt() and 0xFF
            offset += widgetIdLength

            // skip TYPE + EVENT — le server ne les lit pas
            offset += 2

            // PAYLOAD = tout ce qui reste avant CRC
            val payloadLength = bodyEnd - offset
            if (payloadLength <= 0) ByteArray(0)
            else frame.copyOfRange(offset, offset + payloadLength)
        } catch (e: Exception) {
            null
        }
    }

    // ================================================================
    // EXTRACTION — App → Server → Device
    // L'app envoie DEV_COUNT + [DEV_LEN|DEVICE_UUID]×N
    // ================================================================

    /**
     * Extrait les device UUIDs cibles de la trame envoyée par l'app.
     * Ce sont les UUIDs publics des devices — pas les tokens secrets.
     */
    fun extractDeviceIds(frame: ByteArray): List<String> {
        return try {
            var offset = FIXED_HEADER_SIZE

            val deviceCount = frame[offset++].toInt() and 0xFF
            val deviceIds = mutableListOf<String>()

            repeat(deviceCount) {
                val deviceIdLength = frame[offset++].toInt() and 0xFF
                deviceIds.add(frame.decodeToString(offset, offset + deviceIdLength))
                offset += deviceIdLength
            }

            deviceIds
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Trim le header DEV de la trame App → Device.
     * Retire DEV_COUNT + [DEV_LEN|DEV_ID]×N et recalcule LEN + CRC.
     *
     * Trame originale : AA | VER | LEN | SEQ | DEV_COUNT | [DEV_UUID]×N | WID_LEN | WID | TYPE | EVENT | PAYLOAD | CRC
     * Trame trimée    : AA | VER | LEN | SEQ | 00                        | WID_LEN | WID | TYPE | EVENT | PAYLOAD | CRC
     *
     * L'ESP reçoit une trame identique au mode Direct — DEV_COUNT = 0.
     */
    fun trimDeviceHeader(frame: ByteArray): ByteArray? {
        return try {
            var offset = FIXED_HEADER_SIZE

            // skip DEV_COUNT + [DEV_LEN|DEV_ID]×N pour trouver le début de WID
            val deviceCount = frame[offset++].toInt() and 0xFF
            repeat(deviceCount) {
                val deviceIdLength = frame[offset++].toInt() and 0xFF
                offset += deviceIdLength
            }

            // offset pointe maintenant sur WID_LEN
            val widgetSectionStart = offset

            // corps de la trame trimée : DEV_COUNT=0 + WID_LEN | WID | TYPE | EVENT | PAYLOAD
            val widgetAndPayloadBytes = frame.copyOfRange(widgetSectionStart, frame.size - 1) // sans CRC
            val trimmedBody = byteArrayOf(0x00) + widgetAndPayloadBytes // DEV_COUNT = 0

            // recalculer LEN
            val trimmedBodyLength = trimmedBody.size
            val trimmedLenBytes = byteArrayOf(
                (trimmedBodyLength and 0xFF).toByte(),
                ((trimmedBodyLength shr 8) and 0xFF).toByte()
            )

            // recalculer CRC sur le nouveau body
            val trimmedCrc = computeCrc8(trimmedBody)

            // assembler la trame finale trimée
            byteArrayOf(
                frame[0],               // AA
                frame[1],               // VER
                trimmedLenBytes[0],     // LEN low byte
                trimmedLenBytes[1],     // LEN high byte
                frame[4],               // SEQ — conservé tel quel
            ) + trimmedBody + byteArrayOf(trimmedCrc.toByte())
        } catch (e: Exception) {
            null
        }
    }

    // ================================================================
    // CRC8 — recalcul pour la trame trimée
    // Polynôme 0x07 — CRC-8/SMBUS — identique à Crc8.kt côté app Android
    // ================================================================

    private val crc8Table = ByteArray(256) { index ->
        var crc = index
        repeat(8) {
            crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x07
            else crc shl 1
        }
        (crc and 0xFF).toByte()
    }

    private fun computeCrc8(bodyBytes: ByteArray): UByte {
        var crc = 0
        for (bodyByte in bodyBytes) {
            crc = crc8Table[(crc xor (bodyByte.toInt() and 0xFF)) and 0xFF].toInt() and 0xFF
        }
        return crc.toUByte()
    }

    // ================================================================
    // UTILITAIRES
    // ================================================================

    /**
     * Encode un ByteArray en Base64 pour stockage SQLite.
     * Le PAYLOAD est stocké opaque — le server ne le décode jamais.
     */
    fun encodePayloadToBase64(payloadBytes: ByteArray): String =
        Base64.getEncoder().encodeToString(payloadBytes)

    /**
     * Calcule le SHA-256 du token device reçu en clair lors de la connexion TCP.
     * Utilisé pour le lookup dans DeviceTable — le token en clair n'est jamais stocké.
     */
    fun hashDeviceToken(deviceToken: String): String {
        val hashedBytes = MessageDigest.getInstance("SHA-256").digest(deviceToken.toByteArray())
        return hashedBytes.joinToString("") { "%02x".format(it) }
    }
}