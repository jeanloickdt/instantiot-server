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
    // Créer un nouveau projet avec layout vide
    // ============================================================
    override fun create(name: String, ownerId: String): String {
        val id  = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        transaction {
            ProjectTable.insert {
                it[ProjectTable.id]         = id
                it[ProjectTable.ownerId]    = ownerId
                it[ProjectTable.name]       = name
                it[ProjectTable.layoutJson] = "{}" // layout vide — app initialise
                it[ProjectTable.createdAt]  = now
                it[ProjectTable.updatedAt]  = now
            }
        }
        return id
    }

    // ============================================================
    // Trouver un projet par son id
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
    // Lister tous les projets d'un user — isolation par owner_id
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
    // Renommer un projet — bump updatedAt
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
    // Sync layout complet — blob opaque — bump updatedAt
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
    // Supprimer un projet
    // ============================================================
    override fun delete(id: String): Boolean {
        return transaction {
            ProjectTable.deleteWhere { ProjectTable.id eq id } > 0
        }
    }

    // ============================================================
    // Mapper ResultRow → ProjectRow
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