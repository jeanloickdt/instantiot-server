// widget/data/SqliteWidgetHistoryRepository.kt
package com.jeanloickdt.widget.data

import com.jeanloickdt.widget.domain.WidgetHistoryRepository
import com.jeanloickdt.widget.domain.WidgetHistoryRow
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction

class SqliteWidgetHistoryRepository : WidgetHistoryRepository {

    // ============================================================
    // Insérer un payload — appelé par le relay
    // Throttle 1/sec géré côté relay — pas ici
    // ============================================================
    override fun insert(widgetId: String, projectId: String, ownerId: String, payload: String) {
        transaction {
            WidgetHistoryTable.insert {
                it[WidgetHistoryTable.widgetId]   = widgetId
                it[WidgetHistoryTable.projectId]  = projectId
                it[WidgetHistoryTable.ownerId]    = ownerId
                it[WidgetHistoryTable.payload]    = payload
                it[WidgetHistoryTable.recordedAt] = System.currentTimeMillis()
            }
        }
    }

    // ============================================================
    // Batch insert — appelé par le relay toutes les 5s
    // 1 transaction pour N rows — beaucoup plus rapide
    // ============================================================
    override fun insertBatch(entries: List<WidgetHistoryRow>) {
        if (entries.isEmpty()) return
        transaction {
            WidgetHistoryTable.batchInsert(entries) { entry ->
                this[WidgetHistoryTable.widgetId]   = entry.widgetId
                this[WidgetHistoryTable.projectId]  = entry.projectId
                this[WidgetHistoryTable.ownerId]    = entry.ownerId
                this[WidgetHistoryTable.payload]    = entry.payload
                this[WidgetHistoryTable.recordedAt] = entry.recordedAt
            }
        }
    }

    // ============================================================
    // Historique par plage de temps
    // Index (widget_id, recorded_at) — query rapide
    // ============================================================
    override fun findByWidgetAndRange(widgetId: String, from: Long, to: Long): List<WidgetHistoryRow> {
        return transaction {
            WidgetHistoryTable
                .selectAll()
                .where {
                    (WidgetHistoryTable.widgetId eq widgetId) and
                            (WidgetHistoryTable.recordedAt greaterEq from) and
                            (WidgetHistoryTable.recordedAt lessEq to)
                }
                .orderBy(WidgetHistoryTable.recordedAt, SortOrder.ASC)
                .map { it.toWidgetHistoryRow() }
        }
    }

    // ============================================================
    // Cleanup — supprimer rows de plus de 24h
    // Appelé au démarrage + toutes les heures
    // ============================================================
    override fun deleteOlderThan(timestamp: Long) {
        transaction {
            WidgetHistoryTable.deleteWhere {
                WidgetHistoryTable.recordedAt less timestamp
            }
        }
    }

    // ============================================================
    // Supprimer tout l'historique d'un widget
    // ============================================================
    override fun deleteAllByWidget(widgetId: String) {
        transaction {
            WidgetHistoryTable.deleteWhere {
                WidgetHistoryTable.widgetId eq widgetId
            }
        }
    }

    // ============================================================
    // Supprimer tout l'historique d'un projet — cascade DELETE projet
    // ============================================================
    override fun deleteAllByProject(projectId: String) {
        transaction {
            WidgetHistoryTable.deleteWhere {
                WidgetHistoryTable.projectId eq projectId
            }
        }
    }

    // ============================================================
    // Mapper ResultRow → WidgetHistoryRow
    // ============================================================
    private fun ResultRow.toWidgetHistoryRow() = WidgetHistoryRow(
        id         = this[WidgetHistoryTable.id],
        widgetId   = this[WidgetHistoryTable.widgetId],
        projectId  = this[WidgetHistoryTable.projectId],
        ownerId    = this[WidgetHistoryTable.ownerId],
        payload    = this[WidgetHistoryTable.payload],
        recordedAt = this[WidgetHistoryTable.recordedAt]
    )
}