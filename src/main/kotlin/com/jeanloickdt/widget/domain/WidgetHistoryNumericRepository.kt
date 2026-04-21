// widget/domain/WidgetHistoryNumericRepository.kt
package com.jeanloickdt.widget.domain

interface WidgetHistoryNumericRepository {

    /** Batch insert — appelé par le relay toutes les 5s. */
    fun insertBatch(entries: List<WidgetHistoryNumericRow>)

    /**
     * Historique numérique par plage de temps.
     * Si [seriesId] est `null`, retourne **tous** les échantillons du widget
     * (toutes séries confondues pour un chart, ou la série unique pour les
     * widgets simples). Si non-null, filtre sur cette série spécifique.
     */
    fun findByWidgetAndRange(
        widgetId: String,
        from: Long,
        to: Long,
        seriesId: String? = null
    ): List<WidgetHistoryNumericRow>

    /** Supprime les rows de plus de [timestamp] ms — appelé par le cleanup cron. */
    fun deleteOlderThan(timestamp: Long)

    /** Supprime tout l'historique numérique d'un widget — DELETE /api/widgets/{id}. */
    fun deleteAllByWidget(widgetId: String)

    /** Supprime tout l'historique numérique d'un projet — DELETE /api/projects/{id}. */
    fun deleteAllByProject(projectId: String)
}
