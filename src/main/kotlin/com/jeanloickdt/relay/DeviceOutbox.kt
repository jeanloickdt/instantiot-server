// relay/DeviceOutbox.kt
package com.jeanloickdt.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.Socket

private val logger = LoggerFactory.getLogger("DeviceOutbox")

/**
 * Sérialise les écritures TCP vers **un device** via un Channel borné +
 * coroutine consommatrice dédiée.
 *
 * ## Problème résolu
 *
 * `AppRelay` faisait `launch(Dispatchers.IO) { socket.write(frame) }` pour
 * chaque trame reçue d'une app. Avec un slider draggé à haute fréquence,
 * on obtenait 30-60 coroutines en parallèle bloquées sur `write()` vers
 * le même ESP32 (kernel TCP send buffer plein, ESP lent à consommer via
 * son `loop()` Arduino). Quand l'app se fermait, ces coroutines restaient
 * orphelines sur le `applicationScope` global et continuaient à attendre.
 * Une nouvelle app qui se connectait voyait ses commandes faire la queue
 * derrière les ~50 writes bloqués — d'où les "5 min" avant qu'un Press
 * aboutisse.
 *
 * ## Solution
 *
 * Un `DeviceOutbox` par device : toutes les frames destinées à cet ESP
 * passent par un Channel borné (cap. 8). Une **unique** coroutine
 * consommatrice draine le channel et fait `socket.write()` séquentiel.
 *
 * ### Règles de backpressure
 *
 * - **Trames streaming** (slider ValueChanging, joystick PositionChanged) :
 *   `trySend` non-bloquant. Si le channel est plein → la frame est
 *   **droppée silencieusement**. On garde ainsi les plus récentes sans
 *   accumulation. 400 ms max de latence sur un slider en drag continu.
 *
 * - **Trames discrètes** (Press, Release, Toggle, VALUE_CHANGED final,
 *   SetValue, etc.) : `send` suspendant. Si le channel est plein, le
 *   caller suspend jusqu'à ce qu'un slot se libère (qq ms en pratique,
 *   le consumer drain le channel en parallèle). **Aucune discrète n'est
 *   jamais perdue.**
 *
 * ## Cycle de vie
 *
 * - Créé dans `SessionRegistry.registerDevice(...)` après auth ESP réussie.
 * - Fermé dans `SessionRegistry.unregisterDevice(...)` → la coroutine
 *   consommatrice sort de `for (msg in channel)` proprement.
 * - Si le `socket.write()` throw (ESP déconnecté sans FIN propre), la
 *   coroutine log et close le channel — les sends suivants retourneront
 *   `ClosedSendChannelException`, captés par `AppRelay.relayFrameToDevices`.
 */
class DeviceOutbox(
    private val deviceId: String,
    private val socket: Socket,
    scope: CoroutineScope
) {
    private val channel = Channel<FrameMsg>(capacity = CHANNEL_CAPACITY)
    private val consumerJob: Job = scope.launch(Dispatchers.IO) { runConsumer() }

    /**
     * Envoie une frame au device.
     *
     * @param bytes trame iWidgets v1 déjà trimée (DEV_COUNT=0, CRC recalculé)
     * @param isStreaming `true` pour slider ValueChanging / joystick
     *                    PositionChanged — sera droppée si le channel est
     *                    plein. `false` pour toute autre commande — sera
     *                    suspendue jusqu'à avoir un slot.
     * @return `true` si la frame a été mise en queue (ou droppée
     *         intentionnellement), `false` si le channel est déjà fermé
     *         (device déconnecté). Le caller doit notifier l'app via
     *         `command_failed(device_offline)` dans ce cas.
     */
    @OptIn(DelicateCoroutinesApi::class)  // isClosedForSend — check rapide, safe read
    suspend fun send(bytes: ByteArray, isStreaming: Boolean): Boolean {
        if (channel.isClosedForSend) return false

        return if (isStreaming) {
            // trySend : si plein → drop silencieux, on garde les N plus récentes
            val sendResult = channel.trySend(FrameMsg(bytes, isStreaming = true))
            if (sendResult.isFailure && !sendResult.isClosed) {
                logger.debug("Outbox full for device=$deviceId — dropped streaming frame")
            }
            !sendResult.isClosed
        } else {
            // send suspendant : backpressure propre, jamais de perte
            try {
                channel.send(FrameMsg(bytes, isStreaming = false))
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Ferme le channel et arrête la coroutine consommatrice.
     * Idempotent — safe d'appeler plusieurs fois.
     */
    fun close() {
        channel.close()
        consumerJob.cancel()
    }

    /**
     * Coroutine unique qui draine le channel et écrit sur le socket TCP.
     *
     * Boucle jusqu'à fermeture du channel (disconnect) ou erreur I/O
     * (ESP disparu). Toute exception ferme l'outbox — le `DeviceRelay`
     * détectera le socket mort à sa prochaine lecture et appellera
     * `unregisterDevice` qui cleanera l'outbox au propre.
     */
    private suspend fun runConsumer() {
        try {
            val outputStream = socket.getOutputStream()
            for (msg in channel) {
                try {
                    outputStream.write(msg.bytes)
                    outputStream.flush()
                } catch (e: IOException) {
                    logger.warn("TCP write failed for device=$deviceId — closing outbox (${e.message})")
                    break
                }
            }
        } catch (e: Exception) {
            logger.warn("Outbox consumer error for device=$deviceId — ${e.message}")
        } finally {
            channel.close()
        }
    }

    companion object {
        /**
         * Capacité du Channel par device.
         *
         * 8 frames = couvre une burst de slider drag sans perte pendant
         * ~400 ms (8 × 50 ms d'intervalle throttler app). Au-delà, les
         * streaming sont droppées — imperceptible pour l'user.
         */
        private const val CHANNEL_CAPACITY = 8
    }
}

/**
 * Wrapper pour les frames en attente dans l'outbox.
 *
 * `isStreaming` n'est pas utilisé par le consumer (il écrit tout
 * séquentiellement) mais est conservé pour du futur logging / metrics.
 */
private data class FrameMsg(
    val bytes: ByteArray,
    val isStreaming: Boolean
)
