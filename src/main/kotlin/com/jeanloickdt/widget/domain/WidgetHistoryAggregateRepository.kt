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
     * Supprime les rows de plus de `timestamp` ms — appelé par la
     * cleanup cron (retention par tier, configurable).
     */
    fun deleteOlderThan(timestamp: Long)

    /** Cascade DELETE widget. */
    fun deleteAllByWidget(widgetId: String)

    /** Cascade DELETE project. */
    fun deleteAllByProject(projectId: String)
}
