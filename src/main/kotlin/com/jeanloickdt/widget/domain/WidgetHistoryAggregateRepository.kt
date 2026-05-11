// widget/domain/WidgetHistoryAggregateRepository.kt
package com.jeanloickdt.widget.domain

/**
 * Repository d'une table d'agrégation (min / hour / day).
 * Une instance = une granularité (binding à la bonne table SQLite).
 */
interface WidgetHistoryAggregateRepository {

    /**
     * Lecture des buckets pour un widget et une fenêtre temporelle.
     * `seriesId` null → toutes les séries (sinon filtre sur la série).
     */
    fun findByWidgetAndRange(
        widgetId: String,
        from: Long,
        to: Long,
        seriesId: String? = null
    ): List<WidgetHistoryAggregateRow>

    /**
     * Insère un lot de buckets en batch.
     *
     * Idempotent via l'INDEX UNIQUE `(widget_id, COALESCE(series_id, ''),
     * bucket_at)` créé dans `DatabaseFactory.init` — un retry après un
     * crash partiel ne crée pas de doublons.
     *
     * Utilisé par le job de flush 5s qui draine les buckets fermés des
     * `TierAggregator` RAM vers la DB.
     */
    fun insertBatch(rows: List<AggregateInsertRow>)

    /**
     * Row d'insertion (sans `id` qui est auto-incrémenté).
     * Plus léger que [WidgetHistoryAggregateRow] qui contient l'id
     * post-insert.
     */
    data class AggregateInsertRow(
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

    /**
     * Supprime les rows de plus de `timestamp` ms — appelé par la
     * cleanup cron (retention par tier, configurable).
     */
    fun deleteOlderThan(timestamp: Long)

    /** Cascade DELETE widget. */
    fun deleteAllByWidget(widgetId: String)

    /** Cascade DELETE project. */
    fun deleteAllByProject(projectId: String)
}
