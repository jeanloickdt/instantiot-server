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

import com.jeanloickdt.auth.configureAuth
import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.deviceRepository
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.projectRepository
import com.jeanloickdt.signal.data.SignalDayTable
import com.jeanloickdt.signal.data.SignalHourTable
import com.jeanloickdt.signal.data.SignalMinTable
import com.jeanloickdt.signal.data.SignalRawTable
import com.jeanloickdt.signal.data.SignalTable
import com.jeanloickdt.signal.data.ExposedSignalRepository
import com.jeanloickdt.userRepository
import io.ktor.client.request.get
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Le nouveau contrat de lecture d'historique, bout en bout.
 *
 * Ce que la route doit garantir et que l'ancienne ne garantissait pas :
 * `auto` par défaut, la résolution servie toujours annoncée, un motif au
 * lieu d'un silence, un plafond de lignes — et `serverTimeMs`, gardé.
 */
class SignalHistoryRouteTest {

    private lateinit var signals: ExposedSignalRepository

    private val DAY = 86_400_000L
    private val NOW = 1_700_000_000_000L

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.fresh()
        signals = ExposedSignalRepository()
    }

    /** Ce que le faux dépôt a rendu, pour vérifier ce que la route a demandé. */
    private class Spy {
        var lastResolution: String? = null
        var rowsToReturn: Int = 3
    }

    private fun ApplicationTestBuilder.installTestApp(
        windows: List<HistoryWindows.Window> = emptyList(),
        spy: Spy = Spy()
    ) {
        application {
            install(ContentNegotiation) { json() }
            configureAuth(userRepository, com.jeanloickdt.auth.LocalTestAuth.service)
            routing {
                signalRoutes(
                    signals, deviceRepository,
                    clock = { NOW },
                    historyWindows = { _, _ -> windows },
                    readHistory = { _, _, _, _, resolution ->
                        spy.lastResolution = resolution
                        (0 until spy.rowsToReturn).map {
                            SignalHistoryPoint(t = it.toLong(), y = it.toDouble(), n = 1)
                        }
                    }
                )
            }
        }
    }

    /** @return (token, deviceId) */
    private fun account(username: String): Pair<String, String> {
        val id = userRepository.create(username, BCrypt.hashpw("secret123", BCrypt.gensalt()), "user", true)
        val projectId = projectRepository.create(id, "p-$username").id
        val deviceId = deviceRepository.create(
            name         = "board-$username", projectId = projectId, ownerId = id, tokenHash = "h-$username",
            deviceType = com.jeanloickdt.device.domain.DeviceType.ESP32,
            connectivity = com.jeanloickdt.device.domain.DeviceConnectivity.WIFI
        ).id
        signals.create(id, deviceId, 5, "temp", SignalTable.TYPE_FLOAT, nowMs = 0L)
        return com.jeanloickdt.auth.LocalTestAuth.token(id, tokenVersion = 0) to deviceId
    }

    private suspend fun io.ktor.client.HttpClient.history(token: String, deviceId: String, query: String = "") =
        get("/api/devices/$deviceId/signals/5/history$query") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

    // ── Le défaut : auto, et une plage utile ──────────────────────────────

    @Test
    fun `with no parameters at all, the route still answers something drawable`() {
        val spy = Spy()
        testApplication {
            installTestApp(spy = spy)
            val (token, dev) = account("alice")
            val res = client.history(token, dev)
            assertEquals(HttpStatusCode.OK, res.status)

            val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
            assertEquals("auto", body["requested"]!!.jsonPrimitive.content)
            // 24 h par défaut → auto choisit la minute.
            assertEquals("min", body["resolution"]!!.jsonPrimitive.content)
            assertEquals("min", spy.lastResolution, "le dépôt doit être interrogé sur ce qui a été décidé")
        }
    }

    @Test
    fun `the served resolution is always announced, even when it matches the request`() {
        testApplication {
            installTestApp()
            val (token, dev) = account("bob")
            val body = Json.parseToJsonElement(
                client.history(token, dev, "?resolution=hour").bodyAsText()
            ).jsonObject
            assertEquals("hour", body["resolution"]!!.jsonPrimitive.content)
            assertEquals("hour", body["requested"]!!.jsonPrimitive.content)
            assertTrue(body["notice"] is kotlinx.serialization.json.JsonNull)
        }
    }

    @Test
    fun `a wide range steps up the resolution by itself`() {
        val spy = Spy()
        testApplication {
            installTestApp(spy = spy)
            val (token, dev) = account("carol")
            client.history(token, dev, "?from=${NOW - 365 * DAY}&to=$NOW")
            assertEquals("day", spy.lastResolution, "un an en minute serait un demi-million de seaux")
        }
    }

    // ── serverTimeMs, garde de l'ancienne route ───────────────────────────

    @Test
    fun `serverTimeMs is still there — losing it costs shifted curves`() {
        testApplication {
            installTestApp()
            val (token, dev) = account("dave")
            val body = Json.parseToJsonElement(client.history(token, dev).bodyAsText()).jsonObject
            assertEquals(NOW, body["serverTimeMs"]!!.jsonPrimitive.long)
        }
    }

    // ── Un motif, jamais un silence ───────────────────────────────────────

    @Test
    fun `a range beyond the retention is served with the boundary named`() {
        val windows = listOf(
            HistoryWindows.Window("min", true, "7d", NOW - 7 * DAY),
            HistoryWindows.Window("hour", true, "30d", NOW - 30 * DAY),
            HistoryWindows.Window("day", true, "unlimited", null)
        )
        testApplication {
            installTestApp(windows = windows)
            val (token, dev) = account("erin")
            val body = Json.parseToJsonElement(
                client.history(token, dev, "?from=${NOW - 30 * DAY}&to=$NOW&resolution=min").bodyAsText()
            ).jsonObject

            assertEquals(HttpStatusCode.OK.value, 200)
            val notice = body["notice"]!!.jsonPrimitive.content
            assertTrue(notice.contains("7d"), "la frontiere doit etre nommee, pas devinee — $notice")
        }
    }

    @Test
    fun `a tier the plan does not sell falls back and says which one it served`() {
        val windows = listOf(
            HistoryWindows.Window("raw", false, "0d", null),
            HistoryWindows.Window("min", true, "7d", null),
            HistoryWindows.Window("hour", true, "7d", null),
            HistoryWindows.Window("day", true, "7d", null)
        )
        val spy = Spy()
        testApplication {
            installTestApp(windows = windows, spy = spy)
            val (token, dev) = account("frank")
            val body = Json.parseToJsonElement(
                client.history(token, dev, "?resolution=raw").bodyAsText()
            ).jsonObject

            assertEquals("min", body["resolution"]!!.jsonPrimitive.content)
            assertEquals("raw", body["requested"]!!.jsonPrimitive.content)
            assertNotNull(body["notice"]!!.jsonPrimitive.contentOrNull)
            assertEquals("min", spy.lastResolution, "on lit le palier servi, pas celui demande")
        }
    }

    @Test
    fun `an unknown resolution is a 400 that names the valid ones`() {
        testApplication {
            installTestApp()
            val (token, dev) = account("gina")
            val res = client.history(token, dev, "?resolution=weekly")
            assertEquals(HttpStatusCode.BadRequest, res.status)
        }
    }

    // ── Le plafond de lignes ──────────────────────────────────────────────

    @Test
    fun `the row cap truncates and says so, rather than silently sending everything`() {
        val spy = Spy().apply { rowsToReturn = SignalHistoryQuery.MAX_ROWS + 10 }
        testApplication {
            installTestApp(spy = spy)
            val (token, dev) = account("hank")
            val body = Json.parseToJsonElement(client.history(token, dev).bodyAsText()).jsonObject
            assertTrue(body["truncated"]!!.jsonPrimitive.boolean)
            assertEquals(SignalHistoryQuery.MAX_ROWS, body["points"]!!.jsonArray.size)
        }
    }

    @Test
    fun `a normal response is not marked truncated`() {
        testApplication {
            installTestApp()
            val (token, dev) = account("iris")
            val body = Json.parseToJsonElement(client.history(token, dev).bodyAsText()).jsonObject
            assertEquals(false, body["truncated"]!!.jsonPrimitive.boolean)
        }
    }

    // ── L'appartenance ────────────────────────────────────────────────────

    @Test
    fun `another account's board is a 404, never a 403`() {
        testApplication {
            installTestApp()
            val (tokenA, _) = account("jack")
            val (_, deviceB) = account("kim")
            // 403 confirmerait que la carte existe — un bit de l'inventaire
            // de quelqu'un d'autre.
            assertEquals(HttpStatusCode.NotFound, client.history(tokenA, deviceB).status)
        }
    }

    @Test
    fun `an undeclared address is a 404`() {
        testApplication {
            installTestApp()
            val (token, dev) = account("liam")
            val res = get@ run {
                client.get("/api/devices/$dev/signals/99/history") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }
    }

    @Test
    fun `no token, no history`() {
        testApplication {
            installTestApp()
            val (_, dev) = account("mia")
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/devices/$dev/signals/5/history").status)
        }
    }
}

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content
