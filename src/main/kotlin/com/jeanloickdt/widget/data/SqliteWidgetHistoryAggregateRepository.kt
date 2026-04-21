// widget/data/SqliteWidgetHistoryAggregateRepository.kt
package com.jeanloickdt.widget.data

import com.jeanloickdt.widget.domain.WidgetHistoryAggregateRepository
import com.jeanloickdt.widget.domain.WidgetHistoryAggregateRow
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Impl SQLite paramétrée par la [table] (une instance par tier).
 *
 * Les inserts sont gérés par [HistoryAggregator] directement en SQL via
 * `INSERT OR IGNORE ... SELECT ... GROUP BY ...` — on ne les expose pas
 * ici pour garder ce repo en lecture seule + cleanup.
 */
class SqliteWidgetHistoryAggregateRepository(
    private val table: WidgetHistoryAggregateTable
) : WidgetHistoryAggregateRepository {

    override fun findByWidgetAndRange(
        widgetId: String,
        from: Long,
        to: Long,
        seriesId: String?
    ): List<WidgetHistoryAggregateRow> {
        return transaction {
            table
                .selectAll()
                .where {
                    val base = (table.widgetId eq widgetId) and
                            (table.bucketAt greaterEq from) and
                            (table.bucketAt lessEq to)
                    if (seriesId != null) base and (table.seriesId eq seriesId)
                    else base
                }
                .orderBy(table.bucketAt, SortOrder.ASC)
                .map { it.toRow() }
        }
    }

    override fun deleteOlderThan(timestamp: Long) {
        transaction {
            table.deleteWhere { table.bucketAt less timestamp }
        }
    }

    override fun deleteAllByWidget(widgetId: String) {
        transaction {
            table.deleteWhere { table.widgetId eq widgetId }
        }
    }

    override fun deleteAllByProject(projectId: String) {
        transaction {
            table.deleteWhere { table.projectId eq projectId }
        }
    }

    private fun ResultRow.toRow() = WidgetHistoryAggregateRow(
        id          = this[table.id],
        widgetId    = this[table.widgetId],
        projectId   = this[table.projectId],
        ownerId     = this[table.ownerId],
        seriesId    = this[table.seriesId],
        avgValue    = this[table.avgValue],
        minValue    = this[table.minValue],
        maxValue    = this[table.maxValue],
        sampleCount = this[table.sampleCount],
        bucketAt    = this[table.bucketAt]
    )
}
