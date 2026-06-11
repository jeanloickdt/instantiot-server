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
    // Insert a payload — called by the relay
    // 1/sec throttle handled on the relay side — not here
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
    // Batch insert — called by the relay every 5s
    // 1 transaction for N rows — much faster
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
    // History by time range
    // Index (widget_id, recorded_at) — fast query
    // ============================================================
    override fun findByWidgetAndRange(widgetId: String, ownerId: String, from: Long, to: Long): List<WidgetHistoryRow> {
        return transaction {
            WidgetHistoryTable
                .selectAll()
                .where {
                    (WidgetHistoryTable.widgetId eq widgetId) and
                            (WidgetHistoryTable.ownerId eq ownerId) and
                            (WidgetHistoryTable.recordedAt greaterEq from) and
                            (WidgetHistoryTable.recordedAt lessEq to)
                }
                .orderBy(WidgetHistoryTable.recordedAt, SortOrder.ASC)
                .map { it.toWidgetHistoryRow() }
        }
    }

    // ============================================================
    // Cleanup — delete rows older than 24h
    // Called at startup + every hour
    // ============================================================
    override fun deleteOlderThan(timestamp: Long) {
        transaction {
            WidgetHistoryTable.deleteWhere {
                WidgetHistoryTable.recordedAt less timestamp
            }
        }
    }

    // ============================================================
    // Delete a widget's entire history
    // ============================================================
    override fun deleteAllByWidget(ownerId: String, widgetId: String) {
        transaction {
            WidgetHistoryTable.deleteWhere {
                (WidgetHistoryTable.ownerId eq ownerId) and (WidgetHistoryTable.widgetId eq widgetId)
            }
        }
    }

    // ============================================================
    // Delete a project's entire history — project DELETE cascade
    // ============================================================
    override fun deleteAllByProject(projectId: String) {
        transaction {
            WidgetHistoryTable.deleteWhere {
                WidgetHistoryTable.projectId eq projectId
            }
        }
    }

    // ============================================================
    // Map ResultRow → WidgetHistoryRow
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