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

package com.jeanloickdt.auth

import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.deviceRepository
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.projectRepository
import com.jeanloickdt.userRepository
import com.jeanloickdt.widget.data.WidgetHistoryDayTable
import com.jeanloickdt.widget.data.WidgetHistoryHourTable
import com.jeanloickdt.widget.data.WidgetHistoryMinTable
import com.jeanloickdt.widget.data.WidgetHistoryNumericTable
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import com.jeanloickdt.widgetHistoryDayRepository
import com.jeanloickdt.widgetHistoryHourRepository
import com.jeanloickdt.widgetHistoryMinRepository
import com.jeanloickdt.widgetHistoryNumericRepository
import com.jeanloickdt.widgetHistoryRepository
import com.jeanloickdt.widgetRepository
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.sql.DriverManager
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deletion is a legal promise: after it, NO table holds a row keyed by this
 * owner — and, just as binding, every OTHER account's rows are untouched.
 * Both halves are asserted straight from SQLite: what matters is what is left
 * in the tables, not what a finder chooses to return.
 */
class AccountDeletionTest {

    private val tokenService = HmacTokenService("test-secret", "instantiot-server", "instantiot-app")
    private lateinit var dbFile: File

    /** Every table that carries an owner_id, and the users table itself. */
    private val OWNED_TABLES = listOf(
        "projects", "devices", "widgets",
        "widget_history", "widget_history_numeric",
        "widget_history_min", "widget_history_hour", "widget_history_day",
        "automation_rules", "pending_actions", "push_tokens", "message_usage"
    )

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("instantiot-deletion-", ".db").apply { deleteOnExit() }
        DatabaseFactory.init(
            UserTable, ProjectTable, DeviceTable, WidgetTable,
            WidgetHistoryTable, WidgetHistoryNumericTable,
            WidgetHistoryMinTable, WidgetHistoryHourTable, WidgetHistoryDayTable,
            *AutomationTables.ALL,
            dbFile = dbFile
        )
    }

    private fun ApplicationTestBuilder.installTestApp() {
        application {
            install(ContentNegotiation) { json() }
            install(io.ktor.server.plugins.ratelimit.RateLimit) {
                register(io.ktor.server.plugins.ratelimit.RateLimitName("auth")) {
                    rateLimiter(limit = 100, refillPeriod = kotlin.time.Duration.parse("1m"))
                    requestKey { it.request.local.remoteAddress }
                }
            }
            configureAuth(userRepository, tokenService)
            val connections = com.jeanloickdt.relay.ConnectionRegistry()
            val buffers     = com.jeanloickdt.relay.HistoryBuffers()
            val lastValues  = com.jeanloickdt.relay.InMemoryLastValueCache()
            val events      = com.jeanloickdt.relay.ControlEventBroadcaster(connections)
            val cacheAware  = com.jeanloickdt.relay.CacheAwareWidgetRepository(
                widgetRepository, buffers.knownWidgetIds, lastValues
            )
            val purge = AccountPurge(
                userRepository, projectRepository, deviceRepository, cacheAware,
                widgetHistoryRepository, widgetHistoryNumericRepository,
                widgetHistoryMinRepository, widgetHistoryHourRepository, widgetHistoryDayRepository,
                connections, events
            )
            routing {
                authRoutes(userRepository, projectRepository, deviceRepository, connections, tokenService, purge)
            }
        }
    }

    // ── seeding : un compte COMPLET, chaque table peuplée ─────────────────

    private fun account(username: String, role: String = "user"): Pair<String, String> {
        val id = userRepository.create(username, BCrypt.hashpw("secret123", BCrypt.gensalt()), role, true)
        return id to tokenService.issue(id, 0)
    }

    private fun seedFullAccount(ownerId: String) {
        val projectId = projectRepository.create("p-$ownerId", ownerId)
        deviceRepository.create(
            name = "board", projectId = projectId, ownerId = ownerId,
            tokenHash = "hash-$ownerId",
            deviceType = com.jeanloickdt.device.domain.DeviceType.ESP32,
            connectivity = com.jeanloickdt.device.domain.DeviceConnectivity.WIFI
        )
        widgetRepository.register("w-$ownerId", projectId, ownerId, "gauge")
        exec("""INSERT INTO widget_history (widget_id, project_id, owner_id, payload, recorded_at)
                VALUES ('w-$ownerId','$projectId','$ownerId','AA==',1)""")
        exec("""INSERT INTO widget_history_numeric (widget_id, project_id, owner_id, series_id, value, recorded_at)
                VALUES ('w-$ownerId','$projectId','$ownerId',NULL,1.0,1)""")
        for (tier in listOf("min", "hour", "day")) {
            exec("""INSERT INTO widget_history_$tier
                    (widget_id, project_id, owner_id, series_id, avg_value, min_value, max_value, sample_count, bucket_at)
                    VALUES ('w-$ownerId','$projectId','$ownerId',NULL,1.0,1.0,1.0,1,1)""")
        }
        exec("""INSERT INTO automation_rules (id, owner_id, name, enabled, trigger_kind, definition, created_at, updated_at)
                VALUES ('r-$ownerId','$ownerId','rule',1,'value','{}',1,1)""")
        exec("INSERT INTO automation_state (rule_id, triggered, updated_at) VALUES ('r-$ownerId',0,1)")
        exec("INSERT INTO scheduled_jobs (rule_id, next_run_at, timezone) VALUES ('r-$ownerId',1,'UTC')")
        exec("""INSERT INTO pending_actions
                (idempotency_key, owner_id, type, payload, status, attempts, next_attempt_at, occurred_at, created_at)
                VALUES ('k-$ownerId','$ownerId','PUSH','{}','PENDING',0,0,0,0)""")
        exec("INSERT INTO push_tokens (token, owner_id, platform, updated_at) VALUES ('t-$ownerId','$ownerId','android',1)")
        exec("INSERT INTO message_usage (owner_id, period, count) VALUES ('$ownerId','2026-08',42)")
    }

    private fun exec(sql: String) =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.createStatement().use { it.execute(sql) }
        }

    private fun rowsOf(ownerId: String): Map<String, Int> =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            OWNED_TABLES.associateWith { table ->
                val col = if (table == "projects" || table == "devices" || table == "widgets"
                    || table.startsWith("widget_history") || table == "automation_rules"
                    || table == "pending_actions" || table == "push_tokens" || table == "message_usage"
                ) "owner_id" else error(table)
                c.createStatement().use { s ->
                    s.executeQuery("SELECT count(*) FROM $table WHERE $col = '$ownerId'")
                        .use { rs -> rs.next(); rs.getInt(1) }
                }
            } + mapOf(
                "users" to c.createStatement().use { s ->
                    s.executeQuery("SELECT count(*) FROM users WHERE id = '$ownerId'")
                        .use { rs -> rs.next(); rs.getInt(1) }
                },
                "automation_state" to c.createStatement().use { s ->
                    s.executeQuery("SELECT count(*) FROM automation_state WHERE rule_id = 'r-$ownerId'")
                        .use { rs -> rs.next(); rs.getInt(1) }
                },
                "scheduled_jobs" to c.createStatement().use { s ->
                    s.executeQuery("SELECT count(*) FROM scheduled_jobs WHERE rule_id = 'r-$ownerId'")
                        .use { rs -> rs.next(); rs.getInt(1) }
                }
            )
        }

    // ── LA promesse ───────────────────────────────────────────────────────

    @Test
    fun `deleting my account erases every row I own — and nothing of anyone else`() = testApplication {
        installTestApp()
        val (aliceId, aliceToken) = account("alice")
        val (bobId, _) = account("bob")
        seedFullAccount(aliceId)
        seedFullAccount(bobId)

        // Precondition: every table genuinely holds a row for both.
        rowsOf(aliceId).forEach { (table, n) ->
            assertTrue(n >= 1, "seed must populate $table (got $n)")
        }

        val res = client.delete("/api/users/me") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())

        // The promise, table by table — read straight from SQLite.
        rowsOf(aliceId).forEach { (table, n) ->
            assertEquals(0, n, "$table must hold nothing of the deleted account")
        }
        // And the second half, just as binding.
        rowsOf(bobId).forEach { (table, n) ->
            assertTrue(n >= 1, "$table must still hold bob's rows — deletion leaked ($n)")
        }
    }

    @Test
    fun `the deleted account's token is dead immediately`() = testApplication {
        installTestApp()
        val (id, token) = account("carol")
        seedFullAccount(id)

        client.delete("/api/users/me") { header(HttpHeaders.Authorization, "Bearer $token") }

        // Local validation looks the user up: no row, no entry. Revocation is
        // instant here — the 7-day ghost is a CLOUD problem (étape C).
        val after = client.delete("/api/users/me") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Unauthorized, after.status)
    }

    // ── Les gardes ────────────────────────────────────────────────────────

    @Test
    fun `the last admin cannot delete itself`() = testApplication {
        installTestApp()
        val (_, adminToken) = account("root", role = "admin")

        val res = client.delete("/api/users/me") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.Conflict, res.status,
            "orphaning the panel is a footgun, not a freedom")
    }

    @Test
    fun `an admin can delete itself when another admin remains`() = testApplication {
        installTestApp()
        val (_, adminToken) = account("root", role = "admin")
        account("root2", role = "admin")

        val res = client.delete("/api/users/me") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `an admin deletes another user, with the same full cascade`() = testApplication {
        installTestApp()
        val (_, adminToken) = account("root", role = "admin")
        val (targetId, _) = account("dave")
        seedFullAccount(targetId)

        val res = client.delete("/api/admin/users/$targetId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        rowsOf(targetId).forEach { (table, n) ->
            assertEquals(0, n, "$table must hold nothing of the deleted account")
        }
    }

    @Test
    fun `the admin route refuses self-deletion — that act stays explicit`() = testApplication {
        installTestApp()
        val (adminId, adminToken) = account("root", role = "admin")

        val res = client.delete("/api/admin/users/$adminId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `a non-admin cannot delete anyone else`() = testApplication {
        installTestApp()
        val (_, userToken) = account("eve")
        val (victimId, _) = account("victim")

        val res = client.delete("/api/admin/users/$victimId") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
        }
        assertTrue(res.status == HttpStatusCode.Forbidden || res.status == HttpStatusCode.Unauthorized)
        assertEquals(1, rowsOf(victimId)["users"])
    }

    @Test
    fun `deleting an unknown user is a 404`() = testApplication {
        installTestApp()
        val (_, adminToken) = account("root", role = "admin")
        val res = client.delete("/api/admin/users/ghost") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }
}
