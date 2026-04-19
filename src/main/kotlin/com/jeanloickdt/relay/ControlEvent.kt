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
 * Types d'events (section 21 du plan flows-maker-pro) :
 *   - "device_online"     : un device ESP vient de se connecter en TCP
 *   - "device_offline"    : un device ESP est deconnecte (disconnect / token_renewed / deleted)
 *   - "command_failed"    : une commande App->Device a echoue (device_offline / forbidden / relay_error)
 *
 * Realtime sync (nouvelles familles) :
 *   - "project_updated"   : field="layout" ou "name" ; carry layoutJson ou value
 *   - "project_created"   : project creation (broadcast user-scope)
 *   - "project_deleted"   : project deletion
 *   - "device_created"    : device registered — device object complet dans payload
 *   - "device_updated"    : field="token_renewed" (ou autre) pour renew-token
 *   - "device_deleted"    : device supprime
 *   - "widget_registered" : reserve V2 (pas d'endpoint REST widget actuellement)
 *   - "widget_deleted"    : reserve V2
 */
@Serializable
data class ControlEvent(
    val type: String,

    // Champs historiques (device_online/offline/command_failed)
    val deviceId: String? = null,
    val deviceName: String? = null,
    val reason: String? = null,
    val seq: Int? = null,

    // ════════════════════════════════════════════════════════════════
    // Realtime sync fields
    // ════════════════════════════════════════════════════════════════

    /** Projet concerne par l'event. */
    val projectId: String? = null,

    /** Pour project_updated : "layout" ou "name" — pour device_updated : "token_renewed" etc. */
    val field: String? = null,

    /** Pour project_updated field="name" : nouvelle valeur. */
    val value: String? = null,

    /** Pour project_updated field="layout" : blob layout. */
    val layoutJson: String? = null,

    /** Pour project_created : nom du projet. */
    val name: String? = null,

    /** Pour device_created : device complet. */
    val device: DevicePayload? = null,

    /**
     * Timestamp epoch ms cote serveur — utilise cote client pour
     * ordonner les events et afficher "a l'instant" / "il y a 5s".
     */
    val at: Long? = null,

    /**
     * ClientSessionId de l'appareil emetteur — lui permet d'ignorer
     * l'echo en faisant `if (event.sourceSessionId == mySessionId) skip`.
     */
    val sourceSessionId: String? = null
)

/**
 * Device object embarque dans le payload des events device_created / device_updated.
 * Mirror du DeviceResponse cote API REST (sans le token, qui reste un one-shot).
 */
@Serializable
data class DevicePayload(
    val id: String,
    val name: String,
    val projectId: String,
    val isOnline: Boolean,
    val lastSeen: Long?
)

/**
 * Types d'events — constantes pour eviter les typos.
 */
object ControlEventType {
    const val DEVICE_ONLINE    = "device_online"
    const val DEVICE_OFFLINE   = "device_offline"
    const val COMMAND_FAILED   = "command_failed"

    // Realtime sync events
    const val PROJECT_UPDATED  = "project_updated"
    const val PROJECT_CREATED  = "project_created"
    const val PROJECT_DELETED  = "project_deleted"
    const val DEVICE_CREATED   = "device_created"
    const val DEVICE_UPDATED   = "device_updated"
    const val DEVICE_DELETED   = "device_deleted"
}

/** Field discriminator pour project_updated. */
object ProjectUpdatedField {
    const val LAYOUT = "layout"
    const val NAME   = "name"
}

/** Field discriminator pour device_updated. */
object DeviceUpdatedField {
    const val TOKEN_RENEWED = "token_renewed"
}

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

/** Nom du header HTTP transportant le client session id pour le sync. */
const val CLIENT_SESSION_ID_HEADER = "X-Session-Id"
