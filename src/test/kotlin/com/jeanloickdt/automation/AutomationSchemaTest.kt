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

package com.jeanloickdt.automation

import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.widget.data.WidgetHistoryDayTable
import com.jeanloickdt.widget.data.WidgetHistoryHourTable
import com.jeanloickdt.widget.data.WidgetHistoryMinTable
import com.jeanloickdt.widget.data.WidgetHistoryNumericTable
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The six automation tables land on databases that already hold production
 * data — and one index among them is not an optimisation but the delivery
 * guarantee itself. Both facts get proven here, against real SQLite.
 */
class AutomationSchemaTest {

    private lateinit var db: File

    @BeforeTest
    fun setup() {
        db = File.createTempFile("instantiot-autoschema-", ".db").apply { delete(); deleteOnExit() }
    }

    private fun initAll() = DatabaseFactory.init(
        UserTable, ProjectTable, DeviceTable, WidgetTable,
        WidgetHistoryTable, WidgetHistoryNumericTable,
        WidgetHistoryMinTable, WidgetHistoryHourTable, WidgetHistoryDayTable,
        *AutomationTables.ALL,
        dbFile = db
    )

    private fun <T> sql(query: String, read: (java.sql.ResultSet) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
            c.createStatement().use { s -> s.executeQuery(query).use(read) }
        }

    private fun exec(statement: String) =
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
            c.createStatement().use { s -> s.execute(statement) }
        }

    @Test
    fun `a legacy database gains the six tables without losing a row`() {
        // A database as the previous build left it: users only, with data.
        exec(
            """CREATE TABLE users (
                 id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE,
                 pwd_hash TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'user',
                 password_changed INTEGER NOT NULL DEFAULT 0,
                 token_version INTEGER NOT NULL DEFAULT 0,
                 created_at INTEGER NOT NULL)"""
        )
        exec("INSERT INTO users VALUES ('u1','alice','h','user',1,0,1700000000000)")

        initAll()

        val tables = sql("SELECT name FROM sqlite_master WHERE type='table'") { rs ->
            buildSet { while (rs.next()) add(rs.getString(1)) }
        }
        listOf(
            "automation_rules", "automation_state", "pending_actions",
            "scheduled_jobs", "push_tokens", "message_usage"
        ).forEach { assertTrue(it in tables, "$it must exist after migration") }

        assertEquals(1, sql("SELECT count(*) FROM users") { it.next(); it.getInt(1) })
    }

    @Test
    fun `the idempotency key is UNIQUE — the guarantee, not an optimisation`() {
        initAll()
        val insert = """INSERT INTO pending_actions
            (idempotency_key, owner_id, type, payload, status, attempts, next_attempt_at, occurred_at, created_at)
            VALUES ('rule1:evt1', 'u1', 'PUSH', '{}', 'PENDING', 0, 0, 0, 0)"""

        exec(insert)

        // The engine evaluating the same event twice — restart mid-batch —
        // must die on the constraint here, not send the owner two pushes.
        assertFailsWith<SQLException> { exec(insert) }
        assertEquals(1, sql("SELECT count(*) FROM pending_actions") { it.next(); it.getInt(1) })
    }

    @Test
    fun `the worker's sweep and the rule cache load have their indexes`() {
        initAll()
        val indexes = sql("SELECT name FROM sqlite_master WHERE type='index'") { rs ->
            buildSet { while (rs.next()) add(rs.getString(1)) }
        }
        listOf(
            "uniq_pending_idempotency",   // the guarantee above
            "idx_pending_due",            // the every-second "what is due?"
            "idx_rules_owner_widget",     // the rule-cache load
            "idx_scheduled_due",          // the scheduler's range scan
            "idx_push_tokens_owner"       // delivery fan-out per owner
        ).forEach { assertTrue(it in indexes, "index $it must exist") }
    }

    @Test
    fun `running the migration twice is a no-op`() {
        initAll()
        initAll()   // idempotent: IF NOT EXISTS everywhere, additive only
        assertEquals(
            1,
            sql("SELECT count(*) FROM sqlite_master WHERE name='uniq_pending_idempotency'") {
                it.next(); it.getInt(1)
            }
        )
    }
}
