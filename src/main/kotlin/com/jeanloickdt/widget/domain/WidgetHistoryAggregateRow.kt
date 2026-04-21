// widget/domain/WidgetHistoryAggregateRow.kt
package com.jeanloickdt.widget.domain

/**
 * Row d'un bucket d'agrégation (minute / heure / jour).
 *
 * `bucketAt` est le timestamp du début du bucket, aligné sur la granularité.
 * `sampleCount` est utile pour pondérer les agrégations ultérieures (hour →
 * day) — on fait en pratique `SUM(avg * count) / SUM(count)`.
 */
data class WidgetHistoryAggregateRow(
    val id: Int,
    val widgetId: String,
    val projectId: String,
    val ownerId: String,
    val seriesId: String?,
    val avgValue: Double,
    val minValue: Double,
    val maxValue: Double,
    val sampleCount: Int,
    val bucketAt: Long
)
