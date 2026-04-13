// widget/domain/WidgetHistoryRow.kt
package com.jeanloickdt.widget.domain

data class WidgetHistoryRow(
    val id: Int,           // auto-increment — table interne
    val widgetId: String,
    val projectId: String,
    val ownerId: String,
    val payload: String,   // PAYLOAD brut base64 — opaque server
    val recordedAt: Long
)