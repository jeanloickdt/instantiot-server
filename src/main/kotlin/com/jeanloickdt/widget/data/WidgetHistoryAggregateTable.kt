// widget/data/WidgetHistoryAggregateTable.kt
package com.jeanloickdt.widget.data

import org.jetbrains.exposed.sql.Table

/**
 * Schéma partagé des 3 tables d'agrégation de l'historique :
 *   - widget_history_min   (bucket = 1 minute)
 *   - widget_history_hour  (bucket = 1 heure)
 *   - widget_history_day   (bucket = 1 jour)
 *
 * Chaque bucket stocke :
 *   - `avg_value` : moyenne des échantillons raw tombant dans la fenêtre
 *   - `min_value` / `max_value` : extrema (utile pour band charts)
 *   - `sample_count` : nombre de points agrégés
 *   - `bucket_at` : timestamp du début du bucket (aligné sur la granularité)
 *
 * Unicité via INDEX UNIQUE `(widget_id, COALESCE(series_id, ''), bucket_at)`
 * créé dans `DatabaseFactory.init` → INSERT OR IGNORE idempotent.
 */
abstract class WidgetHistoryAggregateTable(tableName: String) : Table(tableName) {
    val id          = integer("id").autoIncrement()
    val widgetId    = text("widget_id")
    val projectId   = text("project_id")
    val ownerId     = text("owner_id")
    val seriesId    = text("series_id").nullable()
    val avgValue    = double("avg_value")
    val minValue    = double("min_value")
    val maxValue    = double("max_value")
    val sampleCount = integer("sample_count")
    val bucketAt    = long("bucket_at")
    override val primaryKey = PrimaryKey(id)
}

object WidgetHistoryMinTable  : WidgetHistoryAggregateTable("widget_history_min")
object WidgetHistoryHourTable : WidgetHistoryAggregateTable("widget_history_hour")
object WidgetHistoryDayTable  : WidgetHistoryAggregateTable("widget_history_day")
