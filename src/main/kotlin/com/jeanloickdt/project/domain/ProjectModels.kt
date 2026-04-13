// project/domain/ProjectModels.kt
package com.jeanloickdt.project.domain

import kotlinx.serialization.Serializable

// ============================================================
// 📥 REQUESTS
// ============================================================

// Créer un nouveau projet
@Serializable
data class CreateProjectRequest(
    val name: String
)

// Renommer un projet
@Serializable
data class UpdateProjectNameRequest(
    val name: String
)

// Sync layout complet — debounce côté app
// ProjectLayout sérialisé en JSON — blob opaque pour le server
@Serializable
data class UpdateProjectLayoutRequest(
    val layoutJson: String
)

// ============================================================
// 📤 RESPONSES
// ============================================================

// Réponse projet complète
@Serializable
data class ProjectResponse(
    val id: String,
    val name: String,
    val layoutJson: String, // ProjectLayout complet — l'app désérialise
    val createdAt: Long,
    val updatedAt: Long
)