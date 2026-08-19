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
import io.ktor.client.request.put
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    private fun ApplicationTestBuilder.installTestApp(
        policies: SignalPolicies = SignalPolicies(),
        /** Default: the board is unreachable — the route's own default too. */
        sendToDevice: suspend (String, ByteArray) -> Boolean = { _, _ -> false }
    ) {
        application {
            install(ContentNegotiation) { json() }
            configureAuth(userRepository, tokenService)
            routing {
                signalRoutes(signals, deviceRepository, policies, sendToDevice = sendToDevice)
            }
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

    private suspend fun io.ktor.client.HttpClient.writeValue(
        token: String, deviceId: String, address: Int, body: String
    ): HttpResponse = put("/api/devices/$deviceId/signals/$address/value") {
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

    // ── Changer le type ───────────────────────────────────────────────────
    //
    // Il est modifiable, et ce que ça coûte est précis : la valeur stockée,
    // pas l'historique. Le prix est là parce que `lastPayload` porte des
    // octets encodés avec l'ANCIEN tag — les rejouer sous le nouveau enverrait
    // à la carte un float lu comme un entier.

    @Test
    fun `the type can be changed`() = testApplication {
        installTestApp()
        val (token, dev) = account("gina")
        client.createSignal(token, dev, """{"label":"x","type":"float"}""")

        val r = client.patch("/api/devices/$dev/signals/I0") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"type":"bool"}""")
        }

        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("bool", json(r.bodyAsText())["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `changing the type drops the stored value, so nothing is replayed under the wrong tag`() = testApplication {
        var sent: ByteArray? = null
        installTestApp(sendToDevice = { _, frame -> sent = frame; true })
        val (token, dev) = account("hana")
        client.createSignal(token, dev, """{"label":"Consigne","type":"float","direction":"setpoint"}""")
        client.writeValue(token, dev, 0, """{"value":23.4}""")
        val owner = userRepository.findByUsername("hana")!!.id
        assertNotNull(signals.find(owner, dev, 0)!!.lastPayload, "précondition : une valeur est stockée")

        client.patch("/api/devices/$dev/signals/I0") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"type":"int"}""")
        }

        assertNull(signals.find(owner, dev, 0)!!.lastPayload,
            "23.4 relu comme un entier vaudrait 1_102_263_091 — envoyé à une pompe")

        sent = null
        assertEquals(0, SignalSetpoint.restoreOnConnect(signals, owner, dev) { _, f -> sent = f; true })
        assertNull(sent, "et rien ne part à la reconnexion")
    }

    @Test
    fun `a patch that repeats the current type keeps the stored value`() = testApplication {
        installTestApp(sendToDevice = { _, _ -> true })
        val (token, dev) = account("ilan")
        client.createSignal(token, dev, """{"label":"Consigne","type":"float","direction":"setpoint"}""")
        client.writeValue(token, dev, 0, """{"value":23.4}""")
        val owner = userRepository.findByUsername("ilan")!!.id

        // Une app qui renvoie tout le formulaire, type compris, ne doit rien perdre.
        client.patch("/api/devices/$dev/signals/I0") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"label":"Renommée","type":"float"}""")
        }

        val after = signals.find(owner, dev, 0)!!
        assertEquals("Renommée", after.label)
        assertNotNull(after.lastPayload,
            "renvoyer le type inchangé n'est pas un changement de type")
    }

    @Test
    fun `an unknown type is refused on patch too`() = testApplication {
        installTestApp()
        val (token, dev) = account("jade")
        client.createSignal(token, dev, """{"label":"x","type":"float"}""")

        val r = client.patch("/api/devices/$dev/signals/I0") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"type":"decimal128"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("float", signals.find(
            userRepository.findByUsername("jade")!!.id, dev, 0)!!.type)
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

    // ── Écrire une consigne ───────────────────────────────────────────────
    //
    // These go through the real HTTP response, not just SignalSetpoint.write.
    // The logic had 16 tests and still shipped a 500: the failure was in
    // SERIALIZING the answer, which no unit test on the write path can see.

    @Test
    fun `an offline board answers 202, with a body the client can actually read`() = testApplication {
        installTestApp()   // sendToDevice returns false: the board is away
        val (token, dev) = account("iris")
        client.createSignal(token, dev, """{"label":"Consigne","type":"float","direction":"setpoint"}""")

        val r = client.writeValue(token, dev, 0, """{"value":21.5}""")

        assertEquals(HttpStatusCode.Accepted, r.status, r.bodyAsText())
        // Reading the body is the whole point: the status alone would have
        // passed even when the response could not be serialized at all.
        val body = json(r.bodyAsText())
        assertEquals(false, body["delivered"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(body["reason"]!!.jsonPrimitive.content.isNotBlank(),
            "202 without a reason leaves the app guessing why nothing happened")
    }

    @Test
    fun `a delivered setpoint answers 200 and says so`() = testApplication {
        installTestApp(sendToDevice = { _, _ -> true })
        val (token, dev) = account("jules")
        client.createSignal(token, dev, """{"label":"Consigne","type":"float","direction":"setpoint"}""")

        val r = client.writeValue(token, dev, 0, """{"value":21.5}""")

        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(true, json(r.bodyAsText())["delivered"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `the frame really reaches the outbox, addressed and valued`() = testApplication {
        var sent: ByteArray? = null
        installTestApp(sendToDevice = { _, frame -> sent = frame; true })
        val (token, dev) = account("kenza")
        client.createSignal(token, dev,
            """{"label":"Consigne","type":"float","address":5,"direction":"setpoint"}""")

        client.writeValue(token, dev, 5, """{"value":21.5}""")

        assertEquals(5, SignalFrame.address(sent!!))
        assertEquals(21.5, SignalFrame.numericValue(sent!!)!!, 0.001)
    }

    @Test
    fun `writing a measure is refused, and the answer says why`() = testApplication {
        installTestApp()
        val (token, dev) = account("lina")
        client.createSignal(token, dev, """{"label":"Température","type":"float"}""")   // measure

        val r = client.writeValue(token, dev, 0, """{"value":42}""")

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue("measure" in json(r.bodyAsText())["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an undeclared address is refused and names itself`() = testApplication {
        installTestApp()
        val (token, dev) = account("marc")

        val r = client.writeValue(token, dev, 9, """{"value":1}""")

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue("I9" in json(r.bodyAsText())["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `another tenant's board is a 404, even to write a value`() = testApplication {
        installTestApp()
        val (tokenA, _) = account("nadia")
        val (_, devB)   = account("omar")

        val r = client.writeValue(tokenA, devB, 0, """{"value":1}""")

        assertEquals(HttpStatusCode.NotFound, r.status,
            "403 would confirm the board exists — 404 confirms nothing")
    }

    // ── La nature : une valeur, ou une action ─────────────────────────────
    //
    // Une action n'a ni sens de lecture, ni historique, ni rejeu. Le serveur
    // NORMALISE au lieu de refuser : il doit etre impossible de creer une
    // action incoherente, pas seulement decourage.

    @Test
    fun `an action comes out written by the app, with no history and no replay`() = testApplication {
        installTestApp()
        val (token, dev) = account("nora")

        val r = client.createSignal(token, dev,
            """{"label":"Portail","type":"bool","nature":"action",
                "historised":true,"replayOnConnect":true,"direction":"measure"}""")

        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        val dto = json(r.bodyAsText())
        assertEquals("action", dto.getValue("nature").jsonPrimitive.content)
        assertEquals("setpoint", dto.getValue("direction").jsonPrimitive.content,
            "une action va toujours de l'app vers la carte")
        assertEquals(false, dto.getValue("historised").jsonPrimitive.content.toBoolean(),
            "un fait n'a pas de courbe")
        assertEquals(false, dto.getValue("replayOnConnect").jsonPrimitive.content.toBoolean(),
            "et surtout : un portail ne se rouvre pas tout seul apres une coupure")
    }

    @Test
    fun `a value keeps what it was given`() = testApplication {
        installTestApp()
        val (token, dev) = account("omar2")

        val r = client.createSignal(token, dev,
            """{"label":"Consigne","type":"float","nature":"value",
                "historised":true,"replayOnConnect":true,"direction":"setpoint"}""")

        val dto = json(r.bodyAsText())
        assertEquals("value", dto.getValue("nature").jsonPrimitive.content)
        assertEquals(true, dto.getValue("replayOnConnect").jsonPrimitive.content.toBoolean())
        assertEquals(true, dto.getValue("historised").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `the nature defaults to value, so nothing changed for what existed`() = testApplication {
        installTestApp()
        val (token, dev) = account("pia")

        val r = client.createSignal(token, dev, """{"label":"x","type":"float"}""")

        assertEquals("value", json(r.bodyAsText()).getValue("nature").jsonPrimitive.content)
    }

    @Test
    fun `an unknown nature is refused with the list of the real ones`() = testApplication {
        installTestApp()
        val (token, dev) = account("quentin")

        val r = client.createSignal(token, dev,
            """{"label":"x","type":"float","nature":"gesture"}""")

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue("action" in json(r.bodyAsText()).getValue("error").jsonPrimitive.content)
    }
}
