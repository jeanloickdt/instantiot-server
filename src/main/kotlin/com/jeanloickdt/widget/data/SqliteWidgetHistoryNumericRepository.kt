// widget/data/SqliteWidgetHistoryNumericRepository.kt
package com.jeanloickdt.widget.data

import com.jeanloickdt.widget.domain.WidgetHistoryNumericRepository
import com.jeanloickdt.widget.domain.WidgetHistoryNumericRow
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction

class SqliteWidgetHistoryNumericRepository : WidgetHistoryNumericRepository {

    override fun insertBatch(entries: List<WidgetHistoryNumericRow>) {
        if (entries.isEmpty()) return
        transaction {
            WidgetHistoryNumericTable.batchInsert(entries) { entry ->
                this[WidgetHistoryNumericTable.widgetId]   = entry.widgetId
                this[WidgetHistoryNumericTable.projectId]  = entry.projectId
                this[WidgetHistoryNumericTable.ownerId]    = entry.ownerId
                this[WidgetHistoryNumericTable.seriesId]   = entry.seriesId
                this[WidgetHistoryNumericTable.value]      = entry.value
                this[WidgetHistoryNumericTable.recordedAt] = entry.recordedAt
            }
        }
    }

    override fun findByWidgetAndRange(
        widgetId: String,
        from: Long,
        to: Long,
        seriesId: String?
    ): List<WidgetHistoryNumericRow> {
        return transaction {
            WidgetHistoryNumericTable
                .selectAll()
                .where {
                    val base = (WidgetHistoryNumericTable.widgetId eq widgetId) and
                            (WidgetHistoryNumericTable.recordedAt greaterEq from) and
                            (WidgetHistoryNumericTable.recordedAt lessEq to)
                    if (seriesId != null) base and (WidgetHistoryNumericTable.seriesId eq seriesId)
                    else base
                }
                .orderBy(WidgetHistoryNumericTable.recordedAt, SortOrder.ASC)
                .map { it.toRow() }
        }
    }

    override fun deleteOlderThan(timestamp: Long) {
        transaction {
            WidgetHistoryNumericTable.deleteWhere {
                WidgetHistoryNumericTable.recordedAt less timestamp
            }
        }
    }

    override fun deleteAllByWidget(widgetId: String) {
        transaction {
            WidgetHistoryNumericTable.deleteWhere {
                WidgetHistoryNumericTable.widgetId eq widgetId
            }
        }
    }

    override fun deleteAllByProject(projectId: String) {
        transaction {
            WidgetHistoryNumericTable.deleteWhere {
                WidgetHistoryNumericTable.projectId eq projectId
            }
        }
    }

    private fun ResultRow.toRow() = WidgetHistoryNumericRow(
        id         = this[WidgetHistoryNumericTable.id],
        widgetId   = this[WidgetHistoryNumericTable.widgetId],
        projectId  = this[WidgetHistoryNumericTable.projectId],
        ownerId    = this[WidgetHistoryNumericTable.ownerId],
        seriesId   = this[WidgetHistoryNumericTable.seriesId],
        value      = this[WidgetHistoryNumericTable.value],
        recordedAt = this[WidgetHistoryNumericTable.recordedAt]
    )
}
