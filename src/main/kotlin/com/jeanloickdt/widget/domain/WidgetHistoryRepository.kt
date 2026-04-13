// widget/domain/WidgetHistoryRepository.kt
package com.jeanloickdt.widget.domain

interface WidgetHistoryRepository {

    // Insérer un payload dans l'historique — appelé par le relay
    // Throttle 1/sec max par widget — géré côté relay
    fun insert(widgetId: String, projectId: String, ownerId: String, payload: String)

    // Batch insert — appelé par le relay toutes les 5s
    // Plus performant que N inserts individuels
    fun insertBatch(entries: List<WidgetHistoryRow>)

    // Historique par plage de temps — pour GET /api/widgets/{id}/history
    fun findByWidgetAndRange(widgetId: String, from: Long, to: Long): List<WidgetHistoryRow>

    // Cleanup — supprimer les rows de plus de 24h
    // Appelé automatiquement au démarrage et toutes les heures
    fun deleteOlderThan(timestamp: Long)

    // Supprimer tout l'historique d'un widget — appelé au DELETE /api/widgets/{id}
    fun deleteAllByWidget(widgetId: String)

    // Supprimer tout l'historique d'un projet — appelé au DELETE /api/projects/{id}
    fun deleteAllByProject(projectId: String)
}