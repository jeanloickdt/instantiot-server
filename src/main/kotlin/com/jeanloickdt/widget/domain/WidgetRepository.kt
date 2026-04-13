// widget/domain/WidgetRepository.kt
package com.jeanloickdt.widget.domain

interface WidgetRepository {

    // Enregistrer un widget — appelé quand l'app ajoute un widget
    // Le server stocke id + type uniquement — pas de géométrie ni settings
    fun register(id: String, projectId: String, ownerId: String, type: String)

    // Trouver un widget par son id
    fun findById(id: String): WidgetRow?

    // Lister tous les widgets d'un projet — pour GET /api/projects/{id}/states
    fun findAllByProject(projectId: String): List<WidgetRow>

    // Update last_payload + last_seen_at — appelé par le relay uniquement
    fun updateLastPayload(id: String, payload: String, timestamp: Long)

    // Supprimer un widget + cascade history
    fun delete(id: String): Boolean

    // Supprimer tous les widgets d'un projet — appelé au DELETE /api/projects/{id}
    fun deleteAllByProject(projectId: String)
}