// relay/ControlEvent.kt
package com.jeanloickdt.relay

import kotlinx.serialization.Serializable

/**
 * Control events envoyes aux apps via Frame.Text (JSON) sur WebSocket /ws/app.
 *
 * Protocole WS app :
 *   - Frame.Text handshake : projectId (1er message)
 *   - Frame.Binary : trames iWidgets v1 (data devices)
 *   - Frame.Text {"type": "..."} : control events (apres handshake)
 *
 * L'app distingue text vs binary pour router entre parser iWidgets et parser events.
 *
 * Types d'events :
 *   - "device_online"   : un device ESP vient de se connecter en TCP
 *   - "device_offline"  : un device ESP est deconnecte (disconnect / token_renewed / deleted)
 *   - "command_failed"  : une commande App->Device a echoue (device_offline / forbidden / relay_error)
 */
@Serializable
data class ControlEvent(
    val type: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val reason: String? = null,   // motif pour device_offline et command_failed
    val seq: Int? = null,         // byte SEQ de la trame pour correlation command_failed

    // ════════════════════════════════════════════════════════════════
    // Realtime sync fields — feature `realtime_sync`
    // Nullable partout pour retro-compatibilite avec les anciens events.
    // ════════════════════════════════════════════════════════════════
    val projectId: String? = null,
    val layoutJson: String? = null,
    val name: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    /** ClientSessionId de l'appareil emetteur — lui permet d'ignorer l'echo. */
    val sourceSessionId: String? = null
)

/**
 * Types d'events — constantes pour eviter les typos.
 */
object ControlEventType {
    const val DEVICE_ONLINE             = "device_online"
    const val DEVICE_OFFLINE            = "device_offline"
    const val COMMAND_FAILED            = "command_failed"

    // Realtime sync events
    const val PROJECT_LAYOUT_UPDATED    = "project_layout_updated"
    const val PROJECT_CREATED           = "project_created"
    const val PROJECT_RENAMED           = "project_renamed"
    const val PROJECT_DELETED           = "project_deleted"
    const val DEVICE_REGISTERED         = "device_registered"
    const val DEVICE_RENAMED            = "device_renamed"
    const val DEVICE_DELETED            = "device_deleted"
}

/** Nom du header HTTP transportant le client session id pour le sync. */
const val CLIENT_SESSION_ID_HEADER = "X-Client-Session-Id"

/**
 * Raisons d'un device_offline.
 */
object DeviceOfflineReason {
    const val DISCONNECTED  = "disconnected"     // TCP disconnect normal (perte reseau, socket ferme)
    const val TOKEN_RENEWED = "token_renewed"    // admin a regenere le token → ancien kick
    const val DELETED       = "deleted"          // admin a supprime le device
}

/**
 * Raisons d'un command_failed.
 */
object CommandFailedReason {
    const val DEVICE_OFFLINE = "device_offline"  // session TCP absente/fermee
    const val FORBIDDEN      = "forbidden"       // device appartient a un autre user
    const val RELAY_ERROR    = "relay_error"     // exception lors de l'ecriture TCP
}
