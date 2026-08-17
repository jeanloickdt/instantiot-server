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

package com.jeanloickdt.relay

import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.widget.data.SqliteWidgetRepository
import com.jeanloickdt.widget.data.WidgetHistoryDayTable
import com.jeanloickdt.widget.data.WidgetHistoryHourTable
import com.jeanloickdt.widget.data.WidgetHistoryMinTable
import com.jeanloickdt.widget.data.WidgetHistoryNumericTable
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the cache invariant: knownWidgetIds + lastValues always reflect the
 * widgets table because every mutating write goes through this decorator. The
 * key regression it guards is the cascade hole — DELETE /projects used to wipe
 * widgets from the DB without purging the RAM caches.
 */
class CacheAwareWidgetRepositoryTest {

    private val known = ConcurrentHashMap.newKeySet<WidgetKey>()
    private val lastValues = InMemoryLastValueCache()
    private lateinit var repo: CacheAwareWidgetRepository

    @BeforeTest
    fun setup() {
        val tmpDb = File.createTempFile("instantiot-cacheaware-", ".db").apply { deleteOnExit() }
        DatabaseFactory.init(
            UserTable, ProjectTable, DeviceTable, WidgetTable,
            WidgetHistoryTable, WidgetHistoryNumericTable,
            WidgetHistoryMinTable, WidgetHistoryHourTable, WidgetHistoryDayTable,
            *com.jeanloickdt.automation.data.AutomationTables.ALL,
            dbFile = tmpDb
        )
        known.clear()
        repo = CacheAwareWidgetRepository(SqliteWidgetRepository(), known, lastValues)
    }

    @Test
    fun `registerIfAbsent seeds knownWidgetIds`() {
        repo.registerIfAbsent("gauge1", "p1", "A", "gauge")
        assertTrue(WidgetKey("A", "gauge1") in known)
    }

    @Test
    fun `delete purges both caches for that key only`() {
        repo.registerIfAbsent("gauge1", "p1", "A", "gauge")
        repo.registerIfAbsent("gauge1", "p1", "B", "gauge")   // colliding id, other owner
        lastValues.put("A", "gauge1", "valA", 1)
        lastValues.put("B", "gauge1", "valB", 1)

        repo.delete("A", "gauge1")

        assertFalse(WidgetKey("A", "gauge1") in known, "A's key purged from knownWidgetIds")
        assertNull(lastValues.get("A", "gauge1"), "A's last value evicted")
        assertTrue(WidgetKey("B", "gauge1") in known, "B's key under the same id untouched")
        assertEquals("valB", lastValues.get("B", "gauge1")?.payload, "B's last value untouched")
    }

    @Test
    fun `deleteAllByProject purges the cache for every widget of that project (cascade hole closed)`() {
        // owner A has two widgets in projA and one in projB
        repo.registerIfAbsent("gauge1", "projA", "A", "gauge")
        repo.registerIfAbsent("btn1", "projA", "A", "button")
        repo.registerIfAbsent("temp1", "projB", "A", "gauge")
        lastValues.put("A", "gauge1", "v", 1)
        lastValues.put("A", "btn1", "v", 1)
        lastValues.put("A", "temp1", "v", 1)

        repo.deleteAllByProject("projA")

        // projA widgets gone from BOTH caches — no phantom keys
        assertFalse(WidgetKey("A", "gauge1") in known)
        assertFalse(WidgetKey("A", "btn1") in known)
        assertNull(lastValues.get("A", "gauge1"))
        assertNull(lastValues.get("A", "btn1"))
        // projB widget survives
        assertTrue(WidgetKey("A", "temp1") in known, "another project's widget is untouched")
        assertEquals("v", lastValues.get("A", "temp1")?.payload)
    }

    @Test
    fun `findAll reflects the table for the boot-time cache seed`() {
        repo.registerIfAbsent("gauge1", "projA", "A", "gauge")
        repo.registerIfAbsent("gauge1", "projB", "B", "gauge")   // collision across owners

        // exactly the boot reload module() performs
        val seeded = ConcurrentHashMap.newKeySet<WidgetKey>()
        repo.findAll().forEach { seeded.add(WidgetKey(it.ownerId, it.id)) }

        assertEquals(setOf(WidgetKey("A", "gauge1"), WidgetKey("B", "gauge1")), seeded,
            "boot reload seeds one key per (owner, widget) row, collisions kept distinct")
    }
}
