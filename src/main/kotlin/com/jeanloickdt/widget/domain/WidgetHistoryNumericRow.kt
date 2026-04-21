// widget/domain/WidgetHistoryNumericRow.kt
package com.jeanloickdt.widget.domain

/**
 * Row de l'historique numérique décodé.
 *
 * `seriesId` vaut `null` pour les widgets simples (gauge, metric, level,
 * slider). Pour les charts multi-séries, c'est l'identifiant de série
 * passé par le device ("line1", "temperature", etc.).
 */
data class WidgetHistoryNumericRow(
    val id: Int,
    val widgetId: String,
    val projectId: String,
    val ownerId: String,
    val seriesId: String?,
    val value: Double,
    val recordedAt: Long
)
