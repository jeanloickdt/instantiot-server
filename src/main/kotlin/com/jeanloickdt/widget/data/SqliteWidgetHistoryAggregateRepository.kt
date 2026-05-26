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

// widget/data/SqliteWidgetHistoryAggregateRepository.kt
package com.jeanloickdt.widget.data

import com.jeanloickdt.widget.domain.WidgetHistoryAggregateRepository
import com.jeanloickdt.widget.domain.WidgetHistoryAggregateRow
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * SQLite impl parameterized by the [table] (one instance per tier).
 *
 * Blynk-style architecture (since the iWidgets history rework): the
 * inserts arrive in batch from the 5s flush job that drains the
 * RAM [TierAggregator]s. Idempotence via UNIQUE INDEX
 * `(widget_id, COALESCE(series_id, ''), bucket_at)` created in
 * `DatabaseFactory.init` → silent INSERT OR IGNORE on duplicates.
 */
class SqliteWidgetHistoryAggregateRepository(
    private val table: WidgetHistoryAggregateTable
) : WidgetHistoryAggregateRepository {

    override fun insertBatch(rows: List<WidgetHistoryAggregateRepository.AggregateInsertRow>) {
        if (rows.isEmpty()) return
        // Raw SQL via JDBC PreparedStatement to:
        //   1. Benefit from SQLite's `INSERT OR IGNORE` — silently skip
        //      rows that would violate the UNIQUE INDEX (widget_id,
        //      COALESCE(series_id, ''), bucket_at) defined in
        //      DatabaseFactory. Idempotent: a retry after a partial
        //      crash does not create duplicates.
        //   2. Avoid Kotlin inference friction with
        //      `batchInsert` on an abstract Table (the Column<T> are
        //      not always resolved correctly on the abstract).
        //   3. Performance — a single prepared statement, batch exec.
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