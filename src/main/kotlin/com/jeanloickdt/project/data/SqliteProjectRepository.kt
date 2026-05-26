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

// project/data/SqliteWidgetHistoryRepository.kt
package com.jeanloickdt.project.data

import com.jeanloickdt.project.domain.ProjectRepository
import com.jeanloickdt.project.domain.ProjectRow
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class SqliteProjectRepository : ProjectRepository {

    // ============================================================
    // Create a new project with an empty layout
    // ============================================================
    override fun create(name: String, ownerId: String): String {
        val id  = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        transaction {
            ProjectTable.insert {
                it[ProjectTable.id]         = id
                it[ProjectTable.ownerId]    = ownerId
                it[ProjectTable.name]       = name
                it[ProjectTable.layoutJson] = "{}" // empty layout — the app initializes it
                it[ProjectTable.createdAt]  = now
                it[ProjectTable.updatedAt]  = now
            }
        }
        return id
    }

    // ============================================================
    // Find a project by its id
    // ============================================================
    override fun findById(id: String): ProjectRow? {
        return transaction {
            ProjectTable
                .selectAll()
                .where { ProjectTable.id eq id }
                .singleOrNull()
                ?.toProjectRow()
        }
    }

    // ============================================================
    // List all projects of a user — isolation by owner_id
    // ============================================================
    override fun findAllByOwner(ownerId: String): List<ProjectRow> {
        return transaction {
            ProjectTable
                .selectAll()
                .where { ProjectTable.ownerId eq ownerId }
                .map { it.toProjectRow() }
        }
    }

    // ============================================================
    // Rename a project — bump updatedAt
    // ============================================================
    override fun updateName(id: String, name: String): Boolean {
        return transaction {
            ProjectTable.update({ ProjectTable.id eq id }) {
                it[ProjectTable.name]      = name
                it[ProjectTable.updatedAt] = System.currentTimeMillis()
            } > 0
        }
    }

    // ============================================================
    // Sync full layout — opaque blob — bump updatedAt
    // ============================================================
    override fun updateLayout(id: String, layoutJson: String): Boolean {
        return transaction {
            ProjectTable.update({ ProjectTable.id eq id }) {
                it[ProjectTable.layoutJson] = layoutJson
                it[ProjectTable.updatedAt]  = System.currentTimeMillis()
            } > 0
        }
    }

    // ============================================================
    // Delete a project
    // ============================================================
    override fun delete(id: String): Boolean {
        return transaction {
            ProjectTable.deleteWhere { ProjectTable.id eq id } > 0
        }
    }

    // ============================================================
    // Total number of projects
    // ============================================================
    override fun count(): Long {
        return transaction {
            ProjectTable.selectAll().count()
        }
    }

    // ============================================================
    // Map ResultRow → ProjectRow
    // ============================================================
    private fun ResultRow.toProjectRow() = ProjectRow(
        id         = this[ProjectTable.id],
        ownerId    = this[ProjectTable.ownerId],
        name       = this[ProjectTable.name],
        layoutJson = this[ProjectTable.layoutJson],
        createdAt  = this[ProjectTable.createdAt],
        updatedAt  = this[ProjectTable.updatedAt]
    )
}