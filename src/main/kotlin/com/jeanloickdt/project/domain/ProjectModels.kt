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
    val layoutJson: String
)

// ============================================================
// 📤 RESPONSES
// ============================================================

// Full project response
@Serializable
data class ProjectResponse(
    val id: String,
    val name: String,
    val layoutJson: String, // full ProjectLayout — the app deserializes it
    val createdAt: Long,
    val updatedAt: Long
)