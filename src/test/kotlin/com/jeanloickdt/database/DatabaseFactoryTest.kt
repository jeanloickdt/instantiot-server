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

package com.jeanloickdt.database

import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves [DatabaseFactory] puts the SQLite file in `auto_vacuum=INCREMENTAL`
 * (mode 2), so the weekly reclaim is a lock-light `PRAGMA incremental_vacuum`
 * rather than a multi-second full `VACUUM`. Covers both a fresh DB and the
 * one-time migration of a legacy DB created with the default (mode 0).
 */
class DatabaseFactoryTest {

    private fun initAll(dbFile: File) = DatabaseFactory.init(
        UserTable, ProjectTable, DeviceTable,
        *com.jeanloickdt.signal.data.SignalTables.ALL,
        *com.jeanloickdt.automation.data.AutomationTables.ALL,
        dbFile = dbFile
    )

    private fun autoVacuumMode(dbFile: File): Int =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("PRAGMA auto_vacuum").use { rs -> rs.next(); rs.getInt(1) }
            }
        }

    @Test
    fun `a fresh database is initialised in incremental auto_vacuum`() {
        val db = File.createTempFile("instantiot-dbfactory-fresh-", ".db").apply { deleteOnExit() }
        db.delete()  // start from nothing — let init create it
        initAll(db)
        assertEquals(2, autoVacuumMode(db), "fresh DB must be in incremental auto_vacuum (mode 2)")
    }

    @Test
    fun `a legacy database with default auto_vacuum is migrated to incremental`() {
        val db = File.createTempFile("instantiot-dbfactory-legacy-", ".db").apply { deleteOnExit() }
        db.delete()
        // simulate a DB created by an older server version: a real table, default
        // auto_vacuum (mode 0 = none).
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
            c.createStatement().use { s -> s.execute("CREATE TABLE legacy(x INTEGER)") }
        }
        assertEquals(0, autoVacuumMode(db), "precondition: the legacy DB starts in mode 0 (none)")

        initAll(db)

        assertEquals(2, autoVacuumMode(db), "init must migrate the legacy DB to incremental (mode 2)")
    }

    /** Names of the PK columns of [table], in PK-position order. */
    private fun pkCols(dbFile: File, table: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("PRAGMA table_info($table)").use { rs ->
                    val cols = mutableListOf<Pair<Int, String>>()
                    while (rs.next()) {
                        val pk = rs.getInt("pk")
                        if (pk > 0) cols.add(pk to rs.getString("name"))
                    }
                    cols.sortedBy { it.first }.map { it.second }
                }
            }
        }

    // Le test de migration de cle primaire des widgets est parti avec la
    // table : `widgets` n'existe plus, et la migration qu'il eprouvait non
    // plus. Une adresse se declare desormais dans `signals`, dont la cle est
    // composite depuis le premier jour.

}
