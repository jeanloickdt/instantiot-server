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

package com.jeanloickdt.signal

import com.jeanloickdt.auth.HmacTokenService
import com.jeanloickdt.auth.configureAuth
import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.deviceRepository
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.projectRepository
import com.jeanloickdt.signal.data.SignalTable
import com.jeanloickdt.signal.data.SqliteSignalRepository
import com.jeanloickdt.userRepository
import com.jeanloickdt.widget.data.WidgetHistoryDayTable
import com.jeanloickdt.widget.data.WidgetHistoryHourTable
import com.jeanloickdt.widget.data.WidgetHistoryMinTable
import com.jeanloickdt.widget.data.WidgetHistoryNumericTable
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The declaration API. Its guards protect two different people: the OTHER
 * tenant, whose boards must not even be confirmed to exist, and the USER
 * themselves, whose sketch and history must not be silently reinterpreted.
 */
class SignalRoutesTest {

    private val tokenService = HmacTokenService("test-secret", "instantiot-server", "instantiot-app")
    private lateinit var signals: SqliteSignalRepository

    @BeforeTest
    fun setup() {
        val db = File.createTempFile("instantiot-sigroutes-", ".db").apply { deleteOnExit() }
        DatabaseFactory.init(
            UserTable, ProjectTable, DeviceTable, WidgetTable,
            WidgetHistoryTable, WidgetHistoryNumericTable,
            WidgetHistoryMinTable, WidgetHistoryHourTable, WidgetHistoryDayTable,
            SignalTable, *AutomationTables.ALL,
            dbFile = db
        )
        signals = SqliteSignalRepository()
    }

    private fun ApplicationTestBuilder.installTestApp(policies: SignalPolicies = SignalPolicies()) {
        application {
            install(ContentNegotiation) { json() }
            configureAuth(userRepository, tokenService)
            routing { signalRoutes(signals, deviceRepository, policies) }
        }
    }

    /** @return (token, deviceId) */
    private fun account(username: String): Pair<String, String> {
        val id = userRepository.create(username, BCrypt.hashpw("secret123", BCrypt.gensalt()), "user", true)
        val projectId = projectRepository.create("p-$username", id)
        val deviceId = deviceRepository.create(
            name = "board-$username", projectId = projectId, ownerId = id, tokenHash = "h-$username",
            deviceType = com.jeanloickdt.device.domain.DeviceType.ESP32,
            connectivity = com.jeanloickdt.device.domain.DeviceConnectivity.WIFI
        )
        return tokenService.issue(id, 0) to deviceId
    }

    private suspend fun io.ktor.client.HttpClient.createSignal(
        token: String, deviceId: String, body: String
    ): HttpResponse = post("/api/devices/$deviceId/signals") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    // ── Le cycle ──────────────────────────────────────────────────────────

    @Test
    fun `create, list, patch, delete`() = testApplication {
        installTestApp()
        val (token, dev) = account("alice")

        val created = client.createSignal(token, dev,
            """{"label":"Température serre","type":"float","unit":"°C","minValue":0,"maxValue":50}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val dto = json(created.bodyAsText())
        assertEquals(0, dto["address"]!!.jsonPrimitive.content.toInt())
        assertEquals("I0", dto["ref"]!!.jsonPrimitive.content,
            "the client never re-implements the rendering")

        val list = client.get("/api/devices/$dev/signals") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(1, Json.parseToJsonElement(list.bodyAsText()).jsonArray.size)

        val patched = client.patch("/api/devices/$dev/signals/I0") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"label":"Serre","historised":false}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
        assertEquals("Serre", json(patched.bodyAsText())["label"]!!.jsonPrimitive.content)
        assertEquals(false, json(patched.bodyAsText())["historised"]!!.jsonPrimitive.content.toBoolean())

        val deleted = client.delete("/api/devices/$dev/signals/0") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, deleted.status, "both `0` and `I0` address the same signal")
    }

    @Test
    fun `addresses are attributed densely, so a sketch reads I0 I1 I2`() = testApplication {
        installTestApp()
        val (token, dev) = account("bob")

        val refs = (1..3).map {
            json(client.createSignal(token, dev, """{"label":"s$it","type":"float"}""").bodyAsText())
                .getValue("ref").jsonPrimitive.content
        }
        assertEquals(listOf("I0", "I1", "I2"), refs)
    }

    // ── Les portes ────────────────────────────────────────────────────────

    @Test
    fun `another tenant's board is a 404 — never a 403`() = testApplication {
        installTestApp()
        val (aliceToken, _) = account("alice2")
        val (_, bobDevice) = account("bob2")

        val res = client.createSignal(aliceToken, bobDevice, """{"label":"x","type":"float"}""")

        assertEquals(HttpStatusCode.NotFound, res.status,
            "a 403 would confirm the board exists — one bit of somebody else's inventory")
    }

    @Test
    fun `the same address on two tenants does not collide`() = testApplication {
        installTestApp()
        val (aToken, aDev) = account("alice3")
        val (bToken, bDev) = account("bob3")

        assertEquals(HttpStatusCode.Created, client.createSignal(aToken, aDev, """{"label":"a","type":"float"}""").status)
        assertEquals(HttpStatusCode.Created, client.createSignal(bToken, bDev, """{"label":"b","type":"float"}""").status)

        val aList = client.get("/api/signals") { header(HttpHeaders.Authorization, "Bearer $aToken") }
        assertEquals(1, Json.parseToJsonElement(aList.bodyAsText()).jsonArray.size,
            "the project-wide picker must only ever see its own owner")
    }

    @Test
    fun `no token, no signals`() = testApplication {
        installTestApp()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/signals").status)
    }

    // ── Les refus qui protègent l'utilisateur ─────────────────────────────

    @Test
    fun `a taken address is a 409 that names it`() = testApplication {
        installTestApp()
        val (token, dev) = account("carol")
        client.createSignal(token, dev, """{"label":"a","type":"float","address":5}""")

        val res = client.createSignal(token, dev, """{"label":"b","type":"float","address":5}""")

        assertEquals(HttpStatusCode.Conflict, res.status)
        assertTrue("I5" in res.bodyAsText(), res.bodyAsText())
    }

    @Test
    fun `an unknown type is refused with the list of the real ones`() = testApplication {
        installTestApp()
        val (token, dev) = account("dave")

        val res = client.createSignal(token, dev, """{"label":"x","type":"double"}""")

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue("float" in res.bodyAsText(), "the app needs the WHY: ${res.bodyAsText()}")
    }

    @Test
    fun `min must be below max — including when the patch sends only one of them`() = testApplication {
        installTestApp()
        val (token, dev) = account("erin")
        client.createSignal(token, dev, """{"label":"x","type":"float","minValue":0,"maxValue":50}""")

        val res = client.patch("/api/devices/$dev/signals/I0") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"minValue":80}""")
        }

        assertEquals(HttpStatusCode.BadRequest, res.status,
            "checking only the request would let a lone min invert the bounds")
    }

    @Test
    fun `the address space is bounded at 255`() = testApplication {
        installTestApp()
        val (token, dev) = account("frank")

        assertEquals(HttpStatusCode.Created,
            client.createSignal(token, dev, """{"label":"x","type":"float","address":255}""").status)
        assertEquals(HttpStatusCode.BadRequest,
            client.createSignal(token, dev, """{"label":"y","type":"float","address":256}""").status,
            "one byte on the wire — 256 would not fit")
    }

    @Test
    fun `the type is not editable — the sketch and the history already rely on it`() = testApplication {
        installTestApp()
        val (token, dev) = account("gina")
        client.createSignal(token, dev, """{"label":"x","type":"float"}""")

        // An unknown field is ignored by the DTO rather than accepted.
        client.patch("/api/devices/$dev/signals/I0") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"type":"bool"}""")
        }

        assertEquals("float", signals.find(signals.listByOwner(
            userRepository.findByUsername("gina")!!.id).first().ownerId, dev, 0)!!.type,
            "changing it would silently reinterpret every stored sample")
    }

    // ── Le quota ──────────────────────────────────────────────────────────

    @Test
    fun `the quota gate can refuse, and it sees the real count`() = testApplication {
        var seen = -1
        installTestApp(SignalPolicies(quotaGate = { call, _, current ->
            seen = current()
            if (seen >= 2) {
                call.respond(HttpStatusCode.PaymentRequired, com.jeanloickdt.common.ApiError("Signal quota reached"))
                false
            } else true
        }))
        val (token, dev) = account("hugo")

        client.createSignal(token, dev, """{"label":"a","type":"float"}""")
        client.createSignal(token, dev, """{"label":"b","type":"float"}""")
        val third = client.createSignal(token, dev, """{"label":"c","type":"float"}""")

        assertEquals(HttpStatusCode.PaymentRequired, third.status)
        assertEquals(2, seen, "the gate counts what exists, not what the request claims")
    }
}
