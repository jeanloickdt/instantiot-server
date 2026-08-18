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

package com.jeanloickdt.project

import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.project.data.SqliteProjectRepository
import com.jeanloickdt.project.domain.LayoutWrite
import com.jeanloickdt.widget.data.WidgetTable
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two phones, one dashboard.
 *
 * The failure this guards against is the quiet kind: nobody sees an error, the
 * app that saved second simply erases what the first did, and the loss is only
 * noticed later — by which time nobody can say what was lost.
 */
class LayoutConcurrencyTest {

    private lateinit var projects: SqliteProjectRepository
    private lateinit var projectId: String

    @BeforeTest
    fun setup() {
        val db = File.createTempFile("instantiot-layout-", ".db").apply { deleteOnExit() }
        DatabaseFactory.init(
            UserTable, ProjectTable, DeviceTable, WidgetTable,
            com.jeanloickdt.widget.data.WidgetHistoryTable,
            com.jeanloickdt.widget.data.WidgetHistoryNumericTable,
            com.jeanloickdt.widget.data.WidgetHistoryMinTable,
            com.jeanloickdt.widget.data.WidgetHistoryHourTable,
            com.jeanloickdt.widget.data.WidgetHistoryDayTable,
            com.jeanloickdt.signal.data.SignalTable,
            *com.jeanloickdt.automation.data.AutomationTables.ALL,
            dbFile = db
        )
        projects = SqliteProjectRepository()
        projectId = projects.create("Maison", "u1")
    }

    private fun layout(id: String) = projects.findById(id)!!.layoutJson

    // ── Le scénario exact ─────────────────────────────────────────────────

    @Test
    fun `the second phone is refused instead of erasing the first`() {
        // Both open the dashboard: same version in hand.
        val v = projects.findById(projectId)!!.version

        val a = projects.updateLayout(projectId, """{"widgets":["gauge moved by A"]}""", v)
        assertTrue(a is LayoutWrite.Ok)

        val b = projects.updateLayout(projectId, """{"widgets":["B's stale copy"]}""", v)

        assertTrue(b is LayoutWrite.Conflict, "B saved from a copy A had already replaced")
        assertTrue("A" in layout(projectId), "A's work survives — that is the whole point")
    }

    @Test
    fun `the conflict hands back what actually won`() {
        val v = projects.findById(projectId)!!.version
        projects.updateLayout(projectId, """{"who":"A"}""", v)

        val b = projects.updateLayout(projectId, """{"who":"B"}""", v) as LayoutWrite.Conflict

        assertEquals(v + 1, b.currentVersion)
        assertTrue("""{"who":"A"}""" == b.currentLayoutJson,
            "\"someone saved\" is useless to the app without \"and here is what they saved\"")
    }

    @Test
    fun `B reloads and its save then goes through`() {
        val v = projects.findById(projectId)!!.version
        projects.updateLayout(projectId, """{"who":"A"}""", v)
        val conflict = projects.updateLayout(projectId, """{"who":"B"}""", v) as LayoutWrite.Conflict

        // B merges onto what it just received, and retries with that version.
        val retry = projects.updateLayout(projectId, """{"who":"A+B"}""", conflict.currentVersion)

        assertTrue(retry is LayoutWrite.Ok)
        assertEquals(v + 2, (retry as LayoutWrite.Ok).version)
        assertTrue("A+B" in layout(projectId))
    }

    // ── Le compteur ───────────────────────────────────────────────────────

    @Test
    fun `the version moves on every write, so two saves in the same millisecond differ`() {
        var v = projects.findById(projectId)!!.version
        repeat(3) {
            val r = projects.updateLayout(projectId, """{"n":$it}""", v) as LayoutWrite.Ok
            assertEquals(v + 1, r.version)
            v = r.version
        }
        // A timestamp would have collapsed these three into one value; a
        // counter cannot, and it cannot go backwards on a clock adjustment.
        assertEquals(4, projects.findById(projectId)!!.version)
    }

    @Test
    fun `a write for an unknown project is NotFound, never a silent no-op`() {
        assertTrue(projects.updateLayout("does-not-exist", "{}", 1) is LayoutWrite.NotFound)
    }

    // ── La compatibilité, et ce qu'elle coûte ─────────────────────────────

    @Test
    fun `no version means no protection — and that is why it is temporary`() {
        val v = projects.findById(projectId)!!.version
        projects.updateLayout(projectId, """{"who":"A"}""", v)

        // An app that predates the guard: it keeps working…
        val b = projects.updateLayout(projectId, """{"who":"B"}""", null)

        assertTrue(b is LayoutWrite.Ok)
        assertTrue("B" in layout(projectId),
            "…but it still erases A. The guard only protects once the app sends its version.")
    }
}
