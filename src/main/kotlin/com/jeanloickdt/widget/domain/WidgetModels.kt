// widget/domain/WidgetModels.kt
package com.jeanloickdt.widget.domain

import kotlinx.serialization.Serializable

// ============================================================
// 📥 REQUESTS
// ============================================================

// Enregistrer un widget — appelé par l'app quand un widget est ajouté
// Le server ne connaît que l'id et le type — pas la géométrie ni les settings
@Serializable
data class RegisterWidgetRequest(
    val id: String,    // UUID généré par l'app — doit correspondre au widget dans layoutJson
    val type: String   // "display" | "command"
)

// ============================================================
// 📤 RESPONSES
// ============================================================

// État d'un widget — last_payload pour reconnexion
// Retourné par GET /api/projects/{id}/states
@Serializable
data class WidgetStateResponse(
    val widgetId: String,
    val payload: String?,  // null si jamais reçu de l'ESP
    val lastSeenAt: Long?
)

// Historique d'un widget — payload par plage de temps
// Retourné par GET /api/widgets/{id}/history?from=&to=
@Serializable
data class WidgetHistoryResponse(
    val payload: String,
    val recordedAt: Long
)