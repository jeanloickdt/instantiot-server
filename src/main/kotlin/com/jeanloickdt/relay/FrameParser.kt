// relay/FrameParser.kt
package com.jeanloickdt.relay

import java.security.MessageDigest
import java.util.Base64

/**
 * Parser de trames binaires iWidgets v1
 *
 * Format trame complète :
 * AA | VER(01) | LEN(2B LE) | DEV_COUNT | [DEV_LEN|DEV_ID]×N | WID_LEN | WID | TYPE | EVENT | PAYLOAD... | CRC8
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
    // AA(1) + VER(1) + LEN(2) + body(min 1) + CRC(1) = 6
    private const val MIN_FRAME_SIZE = 6

    private const val SYNC_BYTE: Int    = 0xAA
    private const val VERSION_BYTE: Int = 0x01

    // Header fixe : AA + VER + LEN(2) = 4 bytes
    private const val FIXED_HEADER_SIZE = 4

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
    // EXTRACTION — Device → Server → App
    // L'ESP envoie DEV_COUNT=0 — pas de device cible dans cette direction
    // ================================================================

    /**
     * Extrait le widget ID de la trame envoyée par l'ESP.
     * Position : après AA | VER | LEN | DEV_COUNT(0) | WID_LEN | WID
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
     * Extrait le byte TYPE d'une trame device→server.
     *
     * Position : après AA | VER | LEN | DEV_COUNT(0) | WID_LEN | WID
     *
     * Utilisé pour distinguer les trames applicatives (widget updates)
     * des trames de service comme le heartbeat (`TYPE = 0xFE`).
     */
    fun extractType(frame: ByteArray): Int? {
        return try {
            var offset = FIXED_HEADER_SIZE
            // skip DEV_COUNT + [DEV_LEN|DEV_ID]×N
            val deviceCount = frame[offset++].toInt() and 0xFF
            repeat(deviceCount) {
                val deviceIdLength = frame[offset++].toInt() and 0xFF
                offset += deviceIdLength
            }
            // skip WID_LEN + WID
            val widgetIdLength = frame[offset++].toInt() and 0xFF
            offset += widgetIdLength
            // TYPE
            frame[offset].toInt() and 0xFF
        } catch (e: Exception) {
            null
        }
    }

    // ================================================================
    // STREAMING CLASSIFICATION — backpressure DeviceOutbox
    // ================================================================

    // Codes iWidgets v1 — synchronisés avec app `BinaryProtocolCodes.kt`.
    // Si ces valeurs changent côté app, il faut les mettre à jour ici.
    private const val TYPE_GAUGE: Int    = 0x03
    private const val TYPE_JOYSTICK: Int = 0x04
    private const val TYPE_HLEVEL: Int   = 0x05
    private const val TYPE_VLEVEL: Int   = 0x06
    private const val TYPE_METRIC: Int   = 0x07
    private const val TYPE_CHART: Int    = 0x09
    private const val TYPE_HSLIDER: Int  = 0x0A
    private const val TYPE_VSLIDER: Int  = 0x0B

    private const val CMD_POSITION_CHANGED: Int = 0x01  // joystick drag
    private const val CMD_VALUE_CHANGING: Int   = 0x10  // slider drag

    // Device→App event codes (0x01..0x0E)
    private const val EV_SETVALUE: Int        = 0x01  // gauge/hlevel/vlevel/metric/slider
    private const val EV_UPDATE: Int          = 0x03  // gauge/hlevel/vlevel : value+min+max
    private const val EV_ADD_POINT: Int       = 0x01  // chart : seriesId + y  (même code que EV_SETVALUE, disambiguated par TYPE)
    private const val EV_ADD_TIMED_POINT: Int = 0x02  // chart : seriesId + x + y

    /**
     * Identifie les commandes émises par un **geste continu** (drag
     * slider, joystick actif). Ces frames arrivent à 20 Hz pendant toute
     * la durée du geste et saturent le pipe TCP → ESP si toutes
     * transmises.
     *
     * Streaming actuellement reconnus :
     * - HSlider `CMD_VALUE_CHANGING`    (type=0x0A, event=0x10)
     * - VSlider `CMD_VALUE_CHANGING`    (type=0x0B, event=0x10)
     * - Joystick `CMD_POSITION_CHANGED` (type=0x04, event=0x01)
     *
     * Toute autre trame est considérée **discrète** et doit arriver
     * intacte (Press, Release, Toggle, ValueChanged final, DragStarted,
     * DragEnded, Released joystick, SetValue switch, etc.).
     *
     * Appelé sur la trame **originale** (avant trim) — le format est
     * identique côté type/event.
     *
     * @return `true` si streaming (peut être droppée sous backpressure),
     *         `false` sinon ou si la frame est invalide/incomplète.
     */
    fun isStreamingCommand(frame: ByteArray): Boolean {
        return try {
            var offset = FIXED_HEADER_SIZE

            // skip DEV_COUNT + [DEV_LEN|DEV_ID]×N
            val deviceCount = frame[offset++].toInt() and 0xFF
            repeat(deviceCount) {
                val deviceIdLength = frame[offset++].toInt() and 0xFF
                offset += deviceIdLength
            }

            // skip WID_LEN + WID
            val widgetIdLength = frame[offset++].toInt() and 0xFF
            offset += widgetIdLength

            // TYPE
            val type = frame[offset++].toInt() and 0xFF
            // EVENT
            val event = frame[offset].toInt() and 0xFF

            when (type) {
                TYPE_HSLIDER, TYPE_VSLIDER -> event == CMD_VALUE_CHANGING
                TYPE_JOYSTICK              -> event == CMD_POSITION_CHANGED
                else                       -> false
            }
        } catch (e: Exception) {
            // Trame mal formée → safe default : non-streaming, pas de drop
            false
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
    // EXTRACTION NUMÉRIQUE — pour l'historique des widgets analogiques
    // ================================================================

    /**
     * Échantillon numérique décodé d'une trame device→app.
     *
     * @param seriesId Pour chart multi-séries ("line1"). `null` pour les
     *                 widgets simples (gauge, metric, level, slider).
     * @param value    La valeur numérique principale (toujours un double).
     */
    data class NumericSample(
        val seriesId: String?,
        val value: Double
    )

    /**
     * Décode une trame device→app et extrait un échantillon numérique
     * si l'event contient un `value` (float) analogique.
     *
     * Supporté :
     * - Gauge / HLevel / VLevel : EV_SETVALUE (4B float), EV_UPDATE (value+min+max, on prend value)
     * - Metric                  : EV_SETVALUE
     * - HSlider / VSlider       : EV_SETVALUE (device→app, rare mais possible)
     * - Chart                   : EV_ADD_POINT (seriesId + y), EV_ADD_TIMED_POINT (seriesId + x + y, on prend y)
     *
     * Tout autre (boutons, switchs, joystick push, segSwitch, etc.) → `null`
     * → pas d'historique numérique pour ce type.
     *
     * @return L'échantillon décodé, ou `null` si non-extractable / trame invalide.
     */
    fun extractNumericValue(frame: ByteArray): NumericSample? {
        return try {
            var offset = FIXED_HEADER_SIZE

            // skip DEV_COUNT + [DEV_LEN|DEV_ID]×N (device→app : devCount=0 normalement)
            val deviceCount = frame[offset++].toInt() and 0xFF
            repeat(deviceCount) {
                val deviceIdLength = frame[offset++].toInt() and 0xFF
                offset += deviceIdLength
            }

            // skip WID_LEN + WID
            val widgetIdLength = frame[offset++].toInt() and 0xFF
            offset += widgetIdLength

            // TYPE + EVENT
            val type  = frame[offset++].toInt() and 0xFF
            val event = frame[offset++].toInt() and 0xFF

            // offset pointe maintenant sur le début du payload.
            // Tous les payloads numériques se suffisent à eux-mêmes
            // (pas besoin de connaître la fin du body pour ces cas).
            when (type) {
                TYPE_GAUGE, TYPE_HLEVEL, TYPE_VLEVEL -> when (event) {
                    EV_SETVALUE -> readFloatAt(frame, offset)?.let { NumericSample(null, it.toDouble()) }
                    EV_UPDATE   -> readFloatAt(frame, offset)?.let { NumericSample(null, it.toDouble()) } // value, puis min, max — on garde value
                    else        -> null
                }
                TYPE_METRIC -> when (event) {
                    EV_SETVALUE -> readFloatAt(frame, offset)?.let { NumericSample(null, it.toDouble()) }
                    else        -> null
                }
                TYPE_HSLIDER, TYPE_VSLIDER -> when (event) {
                    EV_SETVALUE -> readFloatAt(frame, offset)?.let { NumericSample(null, it.toDouble()) }
                    else        -> null
                }
                TYPE_CHART -> {
                    // Payload format : seriesId (1B len + bytes) + float(s)
                    val seriesIdLen = frame[offset].toInt() and 0xFF
                    val seriesIdStart = offset + 1
                    val seriesIdEnd   = seriesIdStart + seriesIdLen
                    if (seriesIdEnd + 4 > frame.size) return null
                    val seriesId = frame.decodeToString(seriesIdStart, seriesIdEnd)
                    when (event) {
                        EV_ADD_POINT -> {
                            // seriesId + y (4B)
                            readFloatAt(frame, seriesIdEnd)?.let { NumericSample(seriesId, it.toDouble()) }
                        }
                        EV_ADD_TIMED_POINT -> {
                            // seriesId + x (4B) + y (4B) — on garde y (la valeur), x sert à l'axe temps côté app mais recordedAt fait le job pour nous
                            if (seriesIdEnd + 8 > frame.size) return null
                            readFloatAt(frame, seriesIdEnd + 4)?.let { NumericSample(seriesId, it.toDouble()) }
                        }
                        else -> null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Lit 4 octets little-endian en Float IEEE 754. `null` si hors borne. */
    private fun readFloatAt(frame: ByteArray, offset: Int): Float? {
        if (offset + 4 > frame.size) return null
        val bits = (frame[offset].toInt() and 0xFF) or
                ((frame[offset + 1].toInt() and 0xFF) shl 8) or
                ((frame[offset + 2].toInt() and 0xFF) shl 16) or
                ((frame[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
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
     * Trame originale : AA | VER | LEN | DEV_COUNT | [DEV_UUID]×N | WID_LEN | WID | TYPE | EVENT | PAYLOAD | CRC
     * Trame trimée    : AA | VER | LEN | 00                        | WID_LEN | WID | TYPE | EVENT | PAYLOAD | CRC
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