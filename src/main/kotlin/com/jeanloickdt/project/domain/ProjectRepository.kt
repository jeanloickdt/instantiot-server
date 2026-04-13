// project/domain/WidgetRepository.kt
package com.jeanloickdt.project.domain

interface ProjectRepository {

    // Créer un nouveau projet
    fun create(name: String, ownerId: String): String

    // Trouver un projet par son id
    fun findById(id: String): ProjectRow?

    // Lister tous les projets d'un user
    fun findAllByOwner(ownerId: String): List<ProjectRow>

    // Renommer un projet
    fun updateName(id: String, name: String): Boolean

    // Sync layout complet — appelé avec debounce depuis l'app
    fun updateLayout(id: String, layoutJson: String): Boolean

    // Supprimer un projet
    fun delete(id: String): Boolean
}