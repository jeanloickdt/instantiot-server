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
    /**
     * Writes the layout under optimistic concurrency.
     *
     * [expectedVersion] null keeps the legacy behaviour — write and hope. It
     * exists only so an app that has not been updated yet keeps working; it
     * offers no protection, and that is the point of naming it.
     */
    fun updateLayout(id: String, layoutJson: String, expectedVersion: Int? = null): LayoutWrite

    // Delete a project
    fun delete(id: String): Boolean

    // Total number of projects
    fun count(): Long
}
/**
 * What a layout write did.
 *
 * [Conflict] carries the current state so the caller can answer the app with
 * something it can act on — "someone else saved" is useless without "and here
 * is what they saved".
 */
sealed interface LayoutWrite {
    data class Ok(val version: Int) : LayoutWrite
    data class Conflict(val currentVersion: Int, val currentLayoutJson: String) : LayoutWrite
    data object NotFound : LayoutWrite
}
