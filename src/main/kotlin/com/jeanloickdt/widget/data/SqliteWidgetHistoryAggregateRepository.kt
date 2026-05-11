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
 * Architecture Blynk-style (depuis refonte historique iWidgets) : les
 * inserts arrivent en batch depuis le job de flush 5s qui draine les
 * [TierAggregator] RAM. Idempotence via INDEX UNIQUE
 * `(widget_id, COALESCE(series_id, ''), bucket_at)` créé dans
 * `DatabaseFactory.init` → INSERT OR IGNORE silencieux sur doublons.
 */
class SqliteWidgetHistoryAggregateRepository(
    private val table: WidgetHistoryAggregateTable
) : WidgetHistoryAggregateRepository {

    override fun insertBatch(rows: List<WidgetHistoryAggregateRepository.AggregateInsertRow>) {
        if (rows.isEmpty()) return
        // SQL brut via JDBC PreparedStatement pour :
        //   1. Bénéficier du `INSERT OR IGNORE` SQLite — skip silencieux
        //      des rows qui violeraient l'INDEX UNIQUE (widget_id,
        //      COALESCE(series_id, ''), bucket_at) défini dans
        //      DatabaseFactory. Idempotent : un retry après crash
        //      partiel ne crée pas de doublons.
        //   2. Éviter les frictions d'inference Kotlin avec
        //      `batchInsert` sur une abstract Table (les Column<T> ne
        //      sont pas toujours résolus correctement sur l'abstract).
        //   3. Performance — une seule prepared statement, exec batch.
        val tableName = table.tableName
        val sql = """
            INSERT OR IGNORE INTO $tableName
                (widget_id, project_id, owner_id, series_id,
                 avg_value, min_value, max_value, sample_count, bucket_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        transaction {
            val conn = (connection as org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl).connection
            conn.prepareStatement(sql).use { ps ->
                for (row in rows) {
                    ps.setString(1, row.widgetId)
                    ps.setString(2, row.projectId)
                    ps.setString(3, row.ownerId)
                    if (row.seriesId != null) ps.setString(4, row.seriesId)
                    else                       ps.setNull(4, java.sql.Types.VARCHAR)
                    ps.setDouble(5, row.avgValue)
                    ps.setDouble(6, row.minValue)
                    ps.setDouble(7, row.maxValue)
                    ps.setInt(8, row.sampleCount)
                    ps.setLong(9, row.bucketAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

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
