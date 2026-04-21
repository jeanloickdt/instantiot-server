// widget/domain/WidgetRepository.kt
package com.jeanloickdt.widget.domain

interface WidgetRepository {

    // Enregistrer un widget — appelé quand l'app ajoute un widget
    // Le server stocke id + type uniquement — pas de géométrie ni settings
    fun register(id: String, projectId: String, ownerId: String, type: String)

    /**
     * Enregistre le widget s'il n'existe pas encore (no-op sinon).
     *
     * Utilisé par l'auto-register dans `DeviceRelay.handleDeviceFrame` :
     * la première trame d'un widget protocolId inconnu crée la ligne
     * dans `widgets` avec `type="auto"` → les REST history lookups
     * fonctionnent ensuite sans que l'app ait à POST explicitement.
     *
     * Implementation SQLite : `INSERT OR IGNORE`. Retourne `true` si la
     * ligne a été créée, `false` si elle existait déjà.
     */
    fun registerIfAbsent(id: String, projectId: String, ownerId: String, type: String): Boolean

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