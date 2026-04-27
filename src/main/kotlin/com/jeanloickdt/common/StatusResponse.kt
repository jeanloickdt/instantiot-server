// common/Models.kt
package com.jeanloickdt.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Réponse de `GET /api/status` — exposé sans authentification.
 *
 * Le frontend admin panel utilise [setupState] (V1 first-launch) pour
 * router vers la bonne page : "needs_licence" → /setup, "needs_welcome"
 * → /welcome, "ready" → /login.
 *
 * Les champs [setup_required] et [licence_required] sont conservés pour
 * compat ascendante avec l'ancien admin panel — ils peuvent être
 * dérivés de [setupState] mais on les sérialise en double pour ne rien
 * casser pendant la transition vers le nouveau flow.
 *
 * [licence] est null tant que la licence n'est pas activée. Une fois
 * activée, le frontend l'affiche dans la page Settings → Licence.
 */
@Serializable
data class StatusResponse(
    val status: String,
    @SerialName("setup_state")
    val setupState: String,
    val licence: LicenceSummary? = null,
    // ── Legacy — gardés pour compat avec l'ancien admin panel ──
    val setup_required: Boolean,
    val licence_required: Boolean
)

/**
 * Résumé licence inclus dans /api/status quand activée.
 * Plus léger que LicenceResponse (pas de token notamment).
 */
@Serializable
data class LicenceSummary(
    val id: String,
    val plan: String,
    val expiresAt: Long
)
