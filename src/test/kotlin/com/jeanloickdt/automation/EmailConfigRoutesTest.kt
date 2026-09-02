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

import com.jeanloickdt.auth.configureAuth
import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.common.ServerConfig
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.userRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The config screen behind the "paste your key and prove it works" promise.
 *
 * The test button is the whole point: a key pasted without proof is a support
 * ticket deferred. So its recipient resolution is what these tests guard —
 * a cloud admin, who owns no panel field at all, must still be able to press
 * it, and the mail must land in their own inbox.
 *
 * ServerConfig is a process-wide singleton whose writer targets the real
 * ~/.instantiot/server.properties, so these tests exercise only the paths
 * that never persist — and restore what they touched.
 */
class EmailConfigRoutesTest {

    /** Everything the fake Brevo saw, in order. */
    private val sentBodies = mutableListOf<String>()
    private var brevoStatus = 201

    private val savedKey = ServerConfig.emailBrevoApiKey
    private val savedFrom = ServerConfig.emailFrom
    private val savedAlertTo = ServerConfig.emailAlertTo

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.fresh()
        sentBodies.clear()
        brevoStatus = 201
        ServerConfig.emailBrevoApiKey = "xkeysib-secret-tail1234"
        ServerConfig.emailFrom = "noreply@instantiot.io"
        ServerConfig.emailAlertTo = ""
    }

    @AfterTest
    fun restore() {
        ServerConfig.emailBrevoApiKey = savedKey
        ServerConfig.emailFrom = savedFrom
        ServerConfig.emailAlertTo = savedAlertTo
    }

    /** The REAL sender, faked only at the HTTP boundary — the route's wiring
     *  to it is part of what is under test. */
    private fun sender() = EmailActionSender(
        config = {
            EmailConfig(
                ServerConfig.emailBrevoApiKey, ServerConfig.emailFrom,
                ServerConfig.emailFromName, ServerConfig.emailAlertTo
            )
        },
        accountEmail = { ownerId -> userRepository.findById(ownerId)?.username?.takeIf { "@" in it } },
        transport = { _, _, body -> sentBodies += body; brevoStatus }
    )

    private fun ApplicationTestBuilder.installTestApp() {
        application {
            install(ContentNegotiation) { json() }
            configureAuth(userRepository, com.jeanloickdt.auth.LocalTestAuth.service)
            routing { emailConfigRoutes(userRepository, sender()) }
        }
    }

    /** @return the admin's bearer token. */
    private fun admin(username: String): String {
        val id = userRepository.create(username, BCrypt.hashpw("secret123", BCrypt.gensalt()), "admin", true)
        return com.jeanloickdt.auth.LocalTestAuth.token(id, tokenVersion = 0)
    }

    private fun plainUser(username: String): String {
        val id = userRepository.create(username, BCrypt.hashpw("secret123", BCrypt.gensalt()), "user", true)
        return com.jeanloickdt.auth.LocalTestAuth.token(id, tokenVersion = 0)
    }

    private suspend fun io.ktor.client.HttpClient.test(token: String, body: String = "{}"): HttpResponse =
        post("/api/admin/email-config/test") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** The recipient the fake Brevo actually received. */
    private fun recipient(): String =
        Json.parseToJsonElement(sentBodies.single()).jsonObject["to"]!!
            .jsonArray[0].jsonObject["email"]!!.jsonPrimitive.content

    // ── Le destinataire du test ───────────────────────────────────────────

    @Test
    fun `a cloud admin owns no field, and can still press the button`() = testApplication {
        // Cloud: the key comes from the environment, every panel field is
        // read-only, and alertTo can therefore never be set. The account
        // address — in cloud the JIT username IS the iia email — is what
        // makes the test button usable with nothing configured at all.
        installTestApp()
        val token = admin("loick@example.com")

        val res = client.test(token)

        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals("loick@example.com", recipient(),
            "with no default recipient anywhere, the test must go to the admin's own address")
    }

    @Test
    fun `a typed address wins over everything`() = testApplication {
        installTestApp()
        ServerConfig.emailAlertTo = "maison@example.fr"
        val token = admin("loick@example.com")

        assertEquals(HttpStatusCode.OK, client.test(token, """{"to":"ailleurs@example.fr"}""").status)
        assertEquals("ailleurs@example.fr", recipient())
    }

    @Test
    fun `self-host — the username is not an email, the panel's default catches it`() = testApplication {
        installTestApp()
        ServerConfig.emailAlertTo = "maison@example.fr"
        val token = admin("loick")   // self-host usernames are not addresses

        assertEquals(HttpStatusCode.OK, client.test(token).status)
        assertEquals("maison@example.fr", recipient())
    }

    @Test
    fun `nowhere to send — a 400 that says what to do, and no email attempted`() = testApplication {
        installTestApp()
        val token = admin("loick")   // no account email, and alertTo is blank

        val res = client.test(token)

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue("recipient" in res.bodyAsText(), res.bodyAsText())
        assertTrue(sentBodies.isEmpty(), "nothing must leave when there is nowhere to send")
    }

    @Test
    fun `an address holding a quote is carried, not spliced`() = testApplication {
        // The payload is BUILT as JSON. Spliced into a string template, this
        // address would produce a body the sender cannot even parse.
        installTestApp()
        val token = admin("loick@example.com")

        val res = client.test(token, """{"to":"o'brien\"x@example.fr"}""")

        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals("o'brien\"x@example.fr", recipient())
    }

    @Test
    fun `a blank 'to' is not a recipient — it falls through, it does not send to empty`() = testApplication {
        installTestApp()
        val token = admin("loick@example.com")

        assertEquals(HttpStatusCode.OK, client.test(token, """{"to":"   "}""").status)
        assertEquals("loick@example.com", recipient())
    }

    // ── Ce que Brevo répond remonte tel quel ──────────────────────────────

    @Test
    fun `Brevo's verdict reaches the admin — a refused key is not a generic failure`() = testApplication {
        installTestApp()
        brevoStatus = 401
        val token = admin("loick@example.com")

        val res = client.test(token)

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue("API key" in res.bodyAsText(),
            "the admin must learn it is the KEY — and that Brevo has an IP allow-list: ${res.bodyAsText()}")
    }

    @Test
    fun `Brevo down is a 502, not a 400 — nothing for the admin to fix`() = testApplication {
        installTestApp()
        brevoStatus = 503
        val token = admin("loick@example.com")

        assertEquals(HttpStatusCode.BadGateway, client.test(token).status)
    }

    // ── La lecture ────────────────────────────────────────────────────────

    @Test
    fun `GET never re-leaks the key — only its tail`() = testApplication {
        installTestApp()
        val token = admin("loick@example.com")

        val body = client.get("/api/admin/email-config") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.bodyAsText()

        assertFalse("xkeysib-secret-tail1234" in body, "a config screen must never hand the secret back")
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("1234", json["apiKeyLast4"]!!.jsonPrimitive.content)
        assertTrue(json["configured"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `no key configured — the tail is null, not an empty string to display`() = testApplication {
        installTestApp()
        ServerConfig.emailBrevoApiKey = ""
        val token = admin("loick@example.com")

        val json = Json.parseToJsonElement(
            client.get("/api/admin/email-config") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.bodyAsText()
        ).jsonObject

        assertTrue(json["apiKeyLast4"] is kotlinx.serialization.json.JsonNull,
            "null, so the placeholder logic has nothing to show — not '' which would render as dots")
        assertFalse(json["configured"]!!.jsonPrimitive.content.toBoolean())
    }

    // ── La porte ──────────────────────────────────────────────────────────

    @Test
    fun `a plain user reaches neither the config nor the button`() = testApplication {
        installTestApp()
        val token = plainUser("bob")

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/admin/email-config") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.status)
        assertEquals(HttpStatusCode.Forbidden, client.test(token).status)
        assertTrue(sentBodies.isEmpty(), "a non-admin must not be able to make the server send mail")
    }

    @Test
    fun `no token at all — 401`() = testApplication {
        installTestApp()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/email-config").status)
    }
}
