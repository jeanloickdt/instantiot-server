/*
 * InstantIoT Server — self-hosted IoT relay for makers.
 * Copyright (C) 2026 InstantIoT
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

// project/domain/WidgetRepository.kt
package com.jeanloickdt.project.domain

interface ProjectRepository {

    // Create a new project
    fun create(name: String, ownerId: String): String

    // Find a project by its id
    fun findById(id: String): ProjectRow?

    // List all projects of a user
    fun findAllByOwner(ownerId: String): List<ProjectRow>

    // Rename a project
    fun updateName(id: String, name: String): Boolean

    // Sync full layout — called with debounce from the app
    fun updateLayout(id: String, layoutJson: String): Boolean

    // Delete a project
    fun delete(id: String): Boolean

    // Total number of projects
    fun count(): Long
}