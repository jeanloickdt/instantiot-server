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
import com.jeanloickdt.common.ServerConfig
import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.deviceRepository
import com.jeanloickdt.signalHistoryRepository
import com.jeanloickdt.signalRepository
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.projectRepository
import com.jeanloickdt.userRepository
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The email-less half of local account management: registration stays CLOSED
 * (secure-by-default) and the admin provisions accounts with a provisional
 * password — whose whole point is that the first login forces it changed, so
 * the admin never durably knows anyone's password.
 */
class AdminCreateUserTest {

    private val tokenService = HmacTokenService("test-secret", "instantiot-server", "instantiot-app")

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.fresh("admincreate")
        // The scenario this feature exists for: registration CLOSED.
        ServerConfig.registrationOpen = false
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
            val purge = AccountPurge(
                userRepository, projectRepository, deviceRepository,
                signalRepository, signalHistoryRepository,
                connections, events
            )
            routing {
                authRoutes(userRepository, projectRepository, deviceRepository, connections, tokenService, purge)
            }
        }
    }

    private fun admin(): String {
        val id = userRepository.create("root", BCrypt.hashpw("adminpass", BCrypt.gensalt()), "admin", true)
        return tokenService.issue(id, 0)
    }

    private suspend fun io.ktor.client.HttpClient.createUser(
        token: String, username: String, password: String = "provisoire1", role: String? = null
    ) = post("/api/admin/users") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(if (role == null) """{"username":"$username","password":"$password"}"""
                else """{"username":"$username","password":"$password","role":"$role"}""")
    }

    private suspend fun io.ktor.client.HttpClient.login(username: String, password: String) =
        post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }

    // ── Le flot complet, sans email ───────────────────────────────────────

    @Test
    fun `admin creates the account, the user logs in and is forced to change the password`() = testApplication {
        installTestApp()
        val token = admin()

        val created = client.createUser(token, "alice")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())

        // Registration is CLOSED, yet the account exists — that is the point.
        val login = client.login("alice", "provisoire1")
        assertEquals(HttpStatusCode.OK, login.status)
        val json = Json.parseToJsonElement(login.bodyAsText()).jsonObject
        assertFalse(
            json["passwordChanged"]!!.jsonPrimitive.boolean,
            "the provisional password must force a change — the admin never durably knows it"
        )
    }

    @Test
    fun `the created admin counts toward the last-admin guard`() = testApplication {
        installTestApp()
        val token = admin()

        assertEquals(HttpStatusCode.Created, client.createUser(token, "root2", role = "admin").status)

        // Two admins now — the founder can leave.
        val res = client.delete("/api/users/me") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    // ── Les refus ─────────────────────────────────────────────────────────

    @Test
    fun `a taken username is a 409, not a silent overwrite`() = testApplication {
        installTestApp()
        val token = admin()
        client.createUser(token, "alice")

        assertEquals(HttpStatusCode.Conflict, client.createUser(token, "alice").status)
    }

    @Test
    fun `validation matches self-registration — no back door`() = testApplication {
        installTestApp()
        val token = admin()

        assertEquals(HttpStatusCode.BadRequest, client.createUser(token, "ab").status,
            "username too short")
        assertEquals(HttpStatusCode.BadRequest, client.createUser(token, "alice", password = "short").status,
            "password under the minimum")
        assertEquals(HttpStatusCode.BadRequest, client.createUser(token, "alice", role = "superuser").status,
            "unknown role")
    }

    @Test
    fun `a non-admin cannot create accounts`() = testApplication {
        installTestApp()
        admin()
        val userId = userRepository.create("eve", BCrypt.hashpw("secret123", BCrypt.gensalt()), "user", true)
        val userToken = tokenService.issue(userId, 0)

        val res = client.createUser(userToken, "mallory")
        assertTrue(res.status == HttpStatusCode.Forbidden || res.status == HttpStatusCode.Unauthorized)
    }
}
