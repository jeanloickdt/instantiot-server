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

package com.jeanloickdt

import com.jeanloickdt.auth.JwtConfig
import com.jeanloickdt.auth.authRoutes
import com.jeanloickdt.auth.configureAuth
import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.common.ServerConfig
import com.jeanloickdt.common.systemRoutes
import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.device.deviceRoutes
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.project.projectRoutes
import com.jeanloickdt.widget.data.WidgetHistoryDayTable
import com.jeanloickdt.widget.data.WidgetHistoryHourTable
import com.jeanloickdt.widget.data.WidgetHistoryMinTable
import com.jeanloickdt.widget.data.WidgetHistoryNumericTable
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import com.jeanloickdt.widget.widgetRoutes
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mindrot.jbcrypt.BCrypt
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * HTTP-level integration tests for the REST API: auth, the forced
 * password-change signal, ownership isolation, the admin guard and
 * registration gating.
 *
 * Harness: each test gets a fresh throwaway SQLite DB (temp file) via the
 * new `DatabaseFactory.init(dbFile = ...)` overload, and a slim Ktor app that
 * wires only the plugins + auth + routes — NO TCP relay, mDNS or background
 * loops (those bind real ports and would make the test flaky). The routes run
 * against the same global repositories as production.
 */
class RoutesIntegrationTest {

    @BeforeTest
    fun setup() {
        val tmpDb = File.createTempFile("instantiot-test-", ".db").apply { deleteOnExit() }
        DatabaseFactory.init(
            UserTable, ProjectTable, DeviceTable, WidgetTable,
            WidgetHistoryTable, WidgetHistoryNumericTable,
            WidgetHistoryMinTable, WidgetHistoryHourTable, WidgetHistoryDayTable,
            dbFile = tmpDb
        )
        JwtConfig.init("instantiot-server", "instantiot-app")
        ServerConfig.registrationOpen = false
    }

    // ── slim test application (no relay / mDNS / loops) ──────────
    private fun ApplicationTestBuilder.installTestApp() {
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) {
                exception<Throwable> { call, _ ->
                    call.respondText("500", status = HttpStatusCode.InternalServerError)
                }
            }
            install(RateLimit) {
                register(RateLimitName("auth")) {
                    rateLimiter(limit = 100, refillPeriod = 1.minutes) // generous: don't throttle tests
                    requestKey { it.request.local.remoteAddress }
                }
            }
            configureAuth(userRepository)
            // per-test relay state (DI) — isolated, no global singleton
            val registry = com.jeanloickdt.relay.SessionRegistry()
            val events   = com.jeanloickdt.relay.ControlEventBroadcaster(registry)
            routing {
                systemRoutes()
                authRoutes(userRepository, projectRepository, deviceRepository, registry)
                projectRoutes(
                    projectRepository, deviceRepository, widgetRepository,
                    widgetHistoryRepository, widgetHistoryNumericRepository,
                    widgetHistoryMinRepository, widgetHistoryHourRepository, widgetHistoryDayRepository,
                    registry, events
                )
                deviceRoutes(deviceRepository, registry, events)
                widgetRoutes(
                    widgetRepository, widgetHistoryRepository, widgetHistoryNumericRepository,
                    widgetHistoryMinRepository, widgetHistoryHourRepository, widgetHistoryDayRepository,
                    registry
                )
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────
    private fun createUser(
        username: String,
        password: String,
        role: String = "user",
        passwordChanged: Boolean = true
    ): Pair<String, String> {
        val id = userRepository.create(username, BCrypt.hashpw(password, BCrypt.gensalt()), role, passwordChanged)
        return id to JwtConfig.generateToken(id)
    }

    private fun jsonOf(body: String) = Json.parseToJsonElement(body).jsonObject

    // ── tests ───────────────────────────────────────────────────

    @Test
    fun `login succeeds and signals passwordChanged false for default admin`() = testApplication {
        installTestApp()
        createUser("admin", "admin", role = "admin", passwordChanged = false)

        val res = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"admin"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val json = jsonOf(res.bodyAsText())
        assertFalse(json["passwordChanged"]!!.jsonPrimitive.boolean, "default admin must be flagged to change")
        assertTrue(json["token"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `login with a wrong password is rejected`() = testApplication {
        installTestApp()
        createUser("bob", "secret123")

        val res = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"bob","password":"wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `changing the password clears the must-change flag`() = testApplication {
        installTestApp()
        val (_, token) = createUser("admin", "admin", role = "admin", passwordChanged = false)

        val change = client.patch("/api/users/me/password") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"currentPassword":"admin","newPassword":"newsecret1"}""")
        }
        assertEquals(HttpStatusCode.OK, change.status)

        val relog = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"newsecret1"}""")
        }
        assertEquals(HttpStatusCode.OK, relog.status)
        assertTrue(jsonOf(relog.bodyAsText())["passwordChanged"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a user cannot read another user's project`() = testApplication {
        installTestApp()
        val (_, tokenA) = createUser("alice", "password1")
        val (_, tokenB) = createUser("bob", "password2")

        val created = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Alice project"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val projectId = jsonOf(created.bodyAsText())["id"]!!.jsonPrimitive.content

        // owner sees it
        val ownerGet = client.get("/api/projects/$projectId") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, ownerGet.status)

        // a different user gets 404 (not 403 — existence is not leaked)
        val intruderGet = client.get("/api/projects/$projectId") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }
        assertEquals(HttpStatusCode.NotFound, intruderGet.status)
    }

    @Test
    fun `project list is scoped to the owner`() = testApplication {
        installTestApp()
        val (_, tokenA) = createUser("alice", "password1")
        val (_, tokenB) = createUser("bob", "password2")

        client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Alice project"}""")
        }

        val bobList = client.get("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }
        assertEquals(HttpStatusCode.OK, bobList.status)
        // bob has no projects → Alice's must not appear
        assertFalse(bobList.bodyAsText().contains("Alice project"))
    }

    @Test
    fun `admin routes reject a non-admin user`() = testApplication {
        installTestApp()
        val (_, userToken) = createUser("carol", "password1", role = "user")
        val (_, adminToken) = createUser("admin", "password2", role = "admin")

        val denied = client.get("/api/admin/stats") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
        }
        assertEquals(HttpStatusCode.Forbidden, denied.status)

        val allowed = client.get("/api/admin/stats") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, allowed.status)
    }

    @Test
    fun `protected routes require a JWT`() = testApplication {
        installTestApp()
        val res = client.get("/api/projects") // no Authorization header
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `registration is gated by the open flag`() = testApplication {
        installTestApp()

        ServerConfig.registrationOpen = false
        val closed = client.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"newuser","password":"password12"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, closed.status)

        ServerConfig.registrationOpen = true
        val open = client.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"newuser","password":"password12"}""")
        }
        assertEquals(HttpStatusCode.Created, open.status)
    }

    @Test
    fun `health and version endpoints are public`() = testApplication {
        installTestApp()

        val health = client.get("/health") // no Authorization header
        assertEquals(HttpStatusCode.OK, health.status)
        assertEquals("ok", jsonOf(health.bodyAsText())["status"]!!.jsonPrimitive.content)

        val version = client.get("/api/version")
        assertEquals(HttpStatusCode.OK, version.status)
        assertTrue(jsonOf(version.bodyAsText())["version"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `error responses use the unified ApiError JSON envelope`() = testApplication {
        installTestApp()
        createUser("bob", "secret123")

        val res = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"bob","password":"wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        // P1-2 contract: every error is a JSON object with an `error` field
        // (this path was a bare string before — would have failed to parse here).
        assertEquals("Invalid credentials", jsonOf(res.bodyAsText())["error"]!!.jsonPrimitive.content)
    }
}
