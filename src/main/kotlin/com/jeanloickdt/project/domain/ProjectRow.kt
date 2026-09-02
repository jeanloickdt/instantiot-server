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

// project/domain/ProjectRow.kt
package com.jeanloickdt.project.domain

data class ProjectRow(
    val id: String,
    val ownerId: String,
    val name: String,
    val layoutJson: String, // full ProjectLayout — opaque blob
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long
)
/**
 * Un projet SANS son layout — ce qu'une liste a besoin de savoir.
 *
 * Le type existe pour que l'absence du layout soit une propriété de la forme,
 * pas une discipline d'appelant. Un `ProjectRow` dont le `layoutJson` serait
 * vide se serait glissé partout sans que rien ne le distingue d'un projet
 * réellement vide.
 *
 * `ownerId` n'y figure pas : l'appelant vient de le fournir pour filtrer, le
 * lui renvoyer n'apprend rien et donne une seconde source à ce qui n'en a
 * qu'une — le jeton.
 */
data class ProjectSummary(
    val id: String,
    val name: String,
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long
)
