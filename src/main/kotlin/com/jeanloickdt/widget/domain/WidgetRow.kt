// widget/domain/WidgetRow.kt
package com.jeanloickdt.widget.domain

data class WidgetRow(
    val id: String,
    val projectId: String,
    val ownerId: String,
    val type: String,          // "display" | "command"
    val lastPayload: String?,  // PAYLOAD brut base64 — écrit par relay uniquement
    val lastSeenAt: Long?      // timestamp dernière trame ESP — écrit par relay
)