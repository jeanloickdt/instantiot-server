// widget/data/WidgetHistoryNumericTable.kt
package com.jeanloickdt.widget.data

import org.jetbrains.exposed.sql.Table

/**
 * Historique **numérique** des widgets analogiques (gauge, metric,
 * level, slider, chart). Populée en parallèle de la table `widget_history`
 * (opaque Base64) mais uniquement quand `FrameParser.extractNumericValue`
 * peut décoder une valeur.
 *
 * ## Pourquoi une table séparée
 * - Query rapide `AVG(value)` / `MIN/MAX` pour le downsampling (Phase 2)
 * - Pas de décodage Base64 à la lecture → API REST sert du JSON direct
 * - L'opaque `widget_history` reste la vérité pour les payloads non-numériques
 *   (boutons, segmented switch, direction pad, etc.)
 *
 * ## Série (seriesId)
 * `null` pour les widgets simples (gauge, metric, level, slider).
 * Pour les charts multi-séries : "line1", "temp", etc.
 */
object WidgetHistoryNumericTable : Table("widget_history_numeric") {
    val id         = integer("id").autoIncrement()
    val widgetId   = text("widget_id")              // FK → widgets.id
    val projectId  = text("project_id")             // query par projet sans JOIN
    val ownerId    = text("owner_id")               // isolation sans JOIN
    val seriesId   = text("series_id").nullable()   // null pour widgets non-chart
    val value      = double("value")                // IEEE 754 double (promu depuis Float)
    val recordedAt = long("recorded_at")            // timestamp ms epoch
    override val primaryKey = PrimaryKey(id)
}
