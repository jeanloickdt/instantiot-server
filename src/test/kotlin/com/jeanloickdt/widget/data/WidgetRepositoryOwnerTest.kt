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

package com.jeanloickdt.widget.data

import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.widget.domain.LastPayloadUpdate
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves widget identity is (ownerId, widgetId) at the repository layer — the
 * composite PK closes the lockout (two owners can both hold "gauge1") and every
 * write is owner-scoped so it can never cross into another owner's row.
 */
class WidgetRepositoryOwnerTest {

    private val repo = SqliteWidgetRepository()
    private val widget = "gauge1"   // the colliding protocolId
    private val A = "owner-A"
    private val B = "owner-B"

    @BeforeTest
    fun setup() {
        val tmpDb = File.createTempFile("instantiot-widgetrepo-", ".db").apply { deleteOnExit() }
        DatabaseFactory.init(
            UserTable, ProjectTable, DeviceTable, WidgetTable,
            WidgetHistoryTable, WidgetHistoryNumericTable,
            WidgetHistoryMinTable, WidgetHistoryHourTable, WidgetHistoryDayTable,
            *com.jeanloickdt.automation.data.AutomationTables.ALL,
            dbFile = tmpDb
        )
    }

    @Test
    fun `two owners can each register the same widgetId (lockout closed)`() {
        assertTrue(repo.registerIfAbsent(widget, "projA", A, "gauge"), "A's row is created")
        assertTrue(repo.registerIfAbsent(widget, "projB", B, "gauge"),
            "B's row is ALSO created — not a silent no-op (the lockout bug)")

        val a = repo.findById(A, widget)
        val b = repo.findById(B, widget)
        assertNotNull(a); assertNotNull(b)
        assertEquals("projA", a.projectId)
        assertEquals("projB", b.projectId, "B resolves to B's own row, not A's")
    }

    @Test
    fun `updateLastPayloadBatch for one owner never touches another owner's row`() {
        repo.registerIfAbsent(widget, "projA", A, "gauge")
        repo.registerIfAbsent(widget, "projB", B, "gauge")

        repo.updateLastPayloadBatch(listOf(LastPayloadUpdate(A, widget, "A-payload", 100)))

        assertEquals("A-payload", repo.findById(A, widget)!!.lastPayload, "A's row updated")
        assertNull(repo.findById(B, widget)!!.lastPayload, "B's row untouched by A's write")
    }

    @Test
    fun `deleting one owner's widget leaves the other owner's intact`() {
        repo.registerIfAbsent(widget, "projA", A, "gauge")
        repo.registerIfAbsent(widget, "projB", B, "gauge")

        assertTrue(repo.delete(A, widget))

        assertNull(repo.findById(A, widget), "A's row gone")
        assertNotNull(repo.findById(B, widget), "B's row survives A's delete under the same widgetId")
    }
}
