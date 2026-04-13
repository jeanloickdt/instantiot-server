// widget/data/SqliteWidgetRepository.kt
package com.jeanloickdt.widget.data

import com.jeanloickdt.widget.domain.WidgetRepository
import com.jeanloickdt.widget.domain.WidgetRow
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class SqliteWidgetRepository : WidgetRepository {

    // ============================================================
    // Enregistrer un widget — id généré par l'app
    // ============================================================
    override fun register(id: String, projectId: String, ownerId: String, type: String) {
        transaction {
            WidgetTable.insert {
                it[WidgetTable.id]          = id
                it[WidgetTable.projectId]   = projectId
                it[WidgetTable.ownerId]     = ownerId
                it[WidgetTable.type]        = type
                it[WidgetTable.lastPayload] = null
                it[WidgetTable.lastSeenAt]  = null
            }
        }
    }

    // ============================================================
    // Trouver un widget par son id
    // ============================================================
    override fun findById(id: String): WidgetRow? {
        return transaction {
            WidgetTable
                .selectAll()
                .where { WidgetTable.id eq id }
                .singleOrNull()
                ?.toWidgetRow()
        }
    }

    // ============================================================
    // Lister tous les widgets d'un projet
    // ============================================================
    override fun findAllByProject(projectId: String): List<WidgetRow> {
        return transaction {
            WidgetTable
                .selectAll()
                .where { WidgetTable.projectId eq projectId }
                .map { it.toWidgetRow() }
        }
    }

    // ============================================================
    // Update last_payload + last_seen_at — relay uniquement
    // ============================================================
    override fun updateLastPayload(id: String, payload: String, timestamp: Long) {
        transaction {
            WidgetTable.update({ WidgetTable.id eq id }) {
                it[WidgetTable.lastPayload] = payload
                it[WidgetTable.lastSeenAt]  = timestamp
            }
        }
    }

    // ============================================================
    // Supprimer un widget
    // ============================================================
    override fun delete(id: String): Boolean {
        return transaction {
            WidgetTable.deleteWhere { WidgetTable.id eq id } > 0
        }
    }

    // ============================================================
    // Supprimer tous les widgets d'un projet — cascade DELETE projet
    // ============================================================
    override fun deleteAllByProject(projectId: String) {
        transaction {
            WidgetTable.deleteWhere { WidgetTable.projectId eq projectId }
        }
    }

    // ============================================================
    // Mapper ResultRow → WidgetRow
    // ============================================================
    private fun ResultRow.toWidgetRow() = WidgetRow(
        id          = this[WidgetTable.id],
        projectId   = this[WidgetTable.projectId],
        ownerId     = this[WidgetTable.ownerId],
        type        = this[WidgetTable.type],
        lastPayload = this[WidgetTable.lastPayload],
        lastSeenAt  = this[WidgetTable.lastSeenAt]
    )
}