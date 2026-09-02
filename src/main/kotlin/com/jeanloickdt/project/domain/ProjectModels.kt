/*
 * InstantIoT Server — self-hosted IoT relay for makers.
 * Copyright (C) 2026 Djoufack Tsobeng Jean Loick (InstantIoT)
 * Author: Djoufack Tsobeng Jean Loick (@jeanloick_dt)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

// project/domain/ProjectModels.kt
package com.jeanloickdt.project.domain

import kotlinx.serialization.Serializable

// ============================================================
// 📥 REQUESTS
// ============================================================

// Create a new project
@Serializable
data class CreateProjectRequest(
    val name: String
)

// Rename a project
@Serializable
data class UpdateProjectNameRequest(
    val name: String
)

// Sync full layout — debounced on the app side
// ProjectLayout serialized as JSON — opaque blob for the server
@Serializable
data class UpdateProjectLayoutRequest(
    val layoutJson: String,
    /**
     * The version the app loaded. Omit it and the write goes through
     * unchecked — the pre-2.0 behaviour, kept only so an app that has not been
     * updated yet keeps working. It buys no protection.
     */
    val version: Int? = null
)

/** What a 409 hands back: enough for the app to reload instead of guessing. */
@Serializable
data class LayoutConflictResponse(
    val error: String,
    val currentVersion: Int,
    val currentLayoutJson: String
)

// ============================================================
// 📤 RESPONSES
// ============================================================

/**
 * Un projet dans une LISTE — sans son layout.
 *
 * ## Pourquoi une seconde forme plutôt qu'un champ nullable
 *
 * Un `layoutJson: String?` laisserait le choix ouvert à chaque appel, et
 * l'oubli reviendrait à la première route ajoutée. Deux types, et le
 * compilateur dit laquelle on construit.
 *
 * ## Ce que ça économise vraiment
 *
 * Pas la sérialisation — la LECTURE. PostgreSQL stocke un `TEXT` volumineux
 * hors ligne (TOAST) : ne pas sélectionner la colonne, c'est ne pas aller la
 * chercher sur le disque. Filtrer en Kotlin après coup aurait tout lu pour
 * tout jeter.
 *
 * `version` s'ajoute au passage : l'app en a besoin pour écrire un layout sous
 * concurrence optimiste, et le lui donner dès la liste lui évite un aller
 * supplémentaire. Son client HTTP ignore les clés inconnues, donc l'ajout ne
 * casse aucune installation existante.
 */
@Serializable
data class ProjectSummaryResponse(
    val id: String,
    val name: String,
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long
)

// Full project response
@Serializable
data class ProjectResponse(
    val id: String,
    val name: String,
    val layoutJson: String, // full ProjectLayout — the app deserializes it
    /**
     * Hand this back when saving. Without it the server cannot tell a fresh
     * edit from one made against a copy someone else has already replaced.
     */
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long
)