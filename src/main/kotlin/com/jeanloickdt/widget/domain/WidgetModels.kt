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

// Historique d'un widget — payload opaque par plage de temps
// Retourné par GET /api/widgets/{id}/history-raw?from=&to=
@Serializable
data class WidgetHistoryResponse(
    val payload: String,
    val recordedAt: Long
)

// Point numérique d'un widget — pour chart/gauge/metric/level/slider
// Retourné par GET /api/widgets/{id}/history?from=&to=&seriesId=&granularity=
//
// Pour granularity=raw : yMin/yMax/count null (point individuel).
// Pour granularity=min/hour/day : yMin/yMax/count populés (bucket agrégé).
@Serializable
data class WidgetHistoryPointResponse(
    val t: Long,                  // timestamp ms epoch (recordedAt pour raw, bucket_at pour agrégé)
    val y: Double,                // value pour raw, avg pour agrégé
    val seriesId: String? = null, // null pour widgets non-chart
    val yMin: Double? = null,     // null pour raw ; min du bucket pour agrégé
    val yMax: Double? = null,     // null pour raw ; max du bucket pour agrégé
    val count: Int? = null        // null pour raw ; nombre d'échantillons du bucket
)