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

package com.jeanloickdt.widget

import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.widget.data.SqliteWidgetHistoryAggregateRepository
import com.jeanloickdt.widget.data.SqliteWidgetHistoryNumericRepository
import com.jeanloickdt.widget.data.SqliteWidgetHistoryRepository
import com.jeanloickdt.widget.data.WidgetHistoryDayTable
import com.jeanloickdt.widget.data.WidgetHistoryHourTable
import com.jeanloickdt.widget.data.WidgetHistoryMinTable
import com.jeanloickdt.widget.data.WidgetHistoryNumericTable
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.widget.domain.WidgetHistoryAggregateRepository
import com.jeanloickdt.widget.domain.WidgetHistoryNumericRow
import com.jeanloickdt.widget.domain.WidgetHistoryRow
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The widgetId namespace is global, but protocolIds (gauge1, temp…) are chosen
 * locally per user and collide across tenants — so two users' frames land in the
 * history tables under the SAME widgetId, distinguished only by owner_id. These
 * tests pin the rule that every history read is scoped to its owner, so a
 * collision can never leak one user's samples into another's chart.
 */
class WidgetHistoryIsolationTest {

    private val numeric = SqliteWidgetHistoryNumericRepository()
    private val opaque  = SqliteWidgetHistoryRepository()
    private val minAgg: WidgetHistoryAggregateRepository =
        SqliteWidgetHistoryAggregateRepository(WidgetHistoryMinTable)

    private val widget = "gauge1"   // the colliding protocolId
    private val ownerA = "user-A"
    private val ownerB = "user-B"

    @BeforeTest
    fun setup() {
        val tmpDb = File.createTempFile("instantiot-widget-iso-", ".db").apply { deleteOnExit() }
        DatabaseFactory.init(
            UserTable, ProjectTable, DeviceTable, WidgetTable,
            WidgetHistoryTable, WidgetHistoryNumericTable,
            WidgetHistoryMinTable, WidgetHistoryHourTable, WidgetHistoryDayTable,
            *com.jeanloickdt.automation.data.AutomationTables.ALL,
            dbFile = tmpDb
        )
    }

    @Test
    fun `numeric history is scoped to its owner under a colliding widgetId`() {
        numeric.insertBatch(listOf(
            WidgetHistoryNumericRow(0, widget, "projA", ownerA, null, 1.0, 100),
            WidgetHistoryNumericRow(0, widget, "projA", ownerA, null, 2.0, 200),
            WidgetHistoryNumericRow(0, widget, "projB", ownerB, null, 99.0, 150),
        ))

        val aPoints = numeric.findByWidgetAndRange(widget, ownerA, 0, 1_000)
        val bPoints = numeric.findByWidgetAndRange(widget, ownerB, 0, 1_000)

        assertEquals(listOf(1.0, 2.0), aPoints.map { it.value }, "A reads only A's samples")
        assertEquals(listOf(99.0), bPoints.map { it.value }, "B reads only B's samples")
    }

    @Test
    fun `opaque history is scoped to its owner under a colliding widgetId`() {
        opaque.insertBatch(listOf(
            WidgetHistoryRow(0, widget, "projA", ownerA, "A-payload", 100),
            WidgetHistoryRow(0, widget, "projB", ownerB, "B-payload", 150),
        ))

        val a = opaque.findByWidgetAndRange(widget, ownerA, 0, 1_000)
        val b = opaque.findByWidgetAndRange(widget, ownerB, 0, 1_000)

        assertEquals(listOf("A-payload"), a.map { it.payload })
        assertEquals(listOf("B-payload"), b.map { it.payload })
    }

    @Test
    fun `two owners' aggregate buckets coexist at the SAME bucket and read back scoped`() {
        // SAME widgetId, SAME bucketAt, different owners. The unique index now
        // includes owner_id, so INSERT OR IGNORE keeps BOTH rows (an owner-blind
        // index would have dropped the second). Reads stay owner-scoped.
        minAgg.insertBatch(listOf(
            WidgetHistoryAggregateRepository.AggregateInsertRow(widget, "projA", ownerA, null, 10.0, 5.0, 15.0, 3, 60_000),
            WidgetHistoryAggregateRepository.AggregateInsertRow(widget, "projB", ownerB, null, 88.0, 80.0, 90.0, 4, 60_000),
        ))

        val a = minAgg.findByWidgetAndRange(widget, ownerA, 0, 1_000_000)
        val b = minAgg.findByWidgetAndRange(widget, ownerB, 0, 1_000_000)

        assertEquals(listOf(10.0), a.map { it.avgValue }, "A reads only A's bucket (not dropped, not B's)")
        assertEquals(listOf(88.0), b.map { it.avgValue }, "B reads only B's bucket (kept despite same bucket)")
    }
}
