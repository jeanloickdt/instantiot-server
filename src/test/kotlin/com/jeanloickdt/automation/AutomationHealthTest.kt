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

import com.jeanloickdt.auth.HmacTokenService
import com.jeanloickdt.auth.configureAuth
import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.deviceRepository
import com.jeanloickdt.event.EventSinks
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.userRepository
import com.jeanloickdt.widget.data.WidgetHistoryDayTable
import com.jeanloickdt.widget.data.WidgetHistoryHourTable
import com.jeanloickdt.widget.data.WidgetHistoryMinTable
import com.jeanloickdt.widget.data.WidgetHistoryNumericTable
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The watch is what turns a silent delivery outage into a line somebody can
 * alert on. Its thresholds are the contract: past 60 s the queue is not late,
 * it is stuck — and a DEAD increase must be said ONCE, not every 30 s
 * forever, or the one line that matters drowns in its own repetition.
 */
class AutomationHealthTest {

    private val tokenService = HmacTokenService("test-secret", "instantiot-server", "instantiot-app")
    private val repo = SqlitePendingActionRepository()
    private var now = 1_000_000L

    @BeforeTest
    fun setup() {
        val db = File.createTempFile("instantiot-health-", ".db").apply { deleteOnExit() }
        DatabaseFactory.init(
            UserTable, ProjectTable, DeviceTable, WidgetTable,
            WidgetHistoryTable, WidgetHistoryNumericTable,
            WidgetHistoryMinTable, WidgetHistoryHourTable, WidgetHistoryDayTable,
            *AutomationTables.ALL,
            dbFile = db
        )
    }

    private fun engine(sinks: EventSinks = EventSinks()) = AutomationEngine(
        sinks, RuleCache(), repo, SqliteAutomationStateStore(), deviceRepository, clock = { now }
    )

    // ── Les seuils de la veille ───────────────────────────────────────────

    @Test
    fun `a fresh queue raises nothing`() {
        val watch = AutomationHealthWatch()
        assertEquals(emptyList(), watch.check(snapshot(repo, EventSinks(), engine(), now)))
    }

    @Test
    fun `past sixty seconds the queue is not late, it is stuck`() {
        repo.enqueue("k1", "u1", null, "PUSH", "{}", occurredAt = now, nowMs = now)
        val watch = AutomationHealthWatch()

        assertEquals(emptyList(), watch.check(snapshot(repo, EventSinks(), engine(), now + 59_000)),
            "59 s is late, not stuck")
        val warnings = watch.check(snapshot(repo, EventSinks(), engine(), now + 61_000))
        assertTrue(warnings.any { "FALLING BEHIND" in it }, "61 s must warn: $warnings")
    }

    @Test
    fun `a DEAD increase is said once, not every thirty seconds forever`() {
        repo.enqueue("k1", "u1", null, "PUSH", "{}", occurredAt = now, nowMs = now)
        val id = repo.lease(now, 1000, 10).single().id
        repo.markDead(id)

        val watch = AutomationHealthWatch()
        val first = watch.check(snapshot(repo, EventSinks(), engine(), now))
        assertTrue(first.any { "DEAD" in it }, "the increase must be reported")

        val second = watch.check(snapshot(repo, EventSinks(), engine(), now + 30_000))
        assertTrue(second.none { "DEAD" in it },
            "an unchanged count must stay silent — the line that matters must not drown in itself")
    }

    @Test
    fun `dropped discrete events always warn — that channel is not lossy by design`() {
        val sinks = EventSinks(discreteCapacity = 1)
        sinks.publish(com.jeanloickdt.event.RelayEvent.DeviceOffline("u1", "d1", "x", now))
        sinks.publish(com.jeanloickdt.event.RelayEvent.DeviceOffline("u1", "d2", "x", now))

        val warnings = AutomationHealthWatch().check(snapshot(repo, sinks, engine(sinks), now))
        assertTrue(warnings.any { "DISCRETE" in it })
    }

    // ── L'endpoint ────────────────────────────────────────────────────────

    @Test
    fun `the endpoint serves the snapshot to admins only`() = testApplication {
        val sinks = EventSinks()
        application {
            install(ContentNegotiation) { json() }
            configureAuth(userRepository, tokenService)
            routing { automationHealthRoutes(userRepository, repo, sinks, engine(sinks), clock = { now }) }
        }
        val adminId = userRepository.create("root", BCrypt.hashpw("adminpass", BCrypt.gensalt()), "admin", true)
        val userId  = userRepository.create("eve", BCrypt.hashpw("secret123", BCrypt.gensalt()), "user", true)

        repo.enqueue("k1", "u1", null, "PUSH", "{}", occurredAt = now - 5_000, nowMs = now - 5_000)

        val forbidden = client.get("/api/admin/automation/health") {
            header(HttpHeaders.Authorization, "Bearer ${tokenService.issue(userId, 0)}")
        }
        assertTrue(forbidden.status == HttpStatusCode.Forbidden || forbidden.status == HttpStatusCode.Unauthorized)

        val res = client.get("/api/admin/automation/health") {
            header(HttpHeaders.Authorization, "Bearer ${tokenService.issue(adminId, 0)}")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val json = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(5_000L, json["oldestPendingAgeMs"]!!.jsonPrimitive.long)
        assertEquals(1L, json["pendingCount"]!!.jsonPrimitive.long)
        assertEquals(0L, json["deadCount"]!!.jsonPrimitive.long)
    }
}
