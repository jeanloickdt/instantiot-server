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

import com.jeanloickdt.auth.authRoutes
import com.jeanloickdt.auth.configureAuth
import com.jeanloickdt.common.systemRoutes
import com.jeanloickdt.device.deviceRoutes
import com.jeanloickdt.project.projectRoutes
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.delete
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
 * Harness: chaque test part d'une base vide sur le moteur de production
 * (`PostgresTestBase`), et d'une app Ktor mince qui ne cable que les plugins,
 * l'authentification et les routes — pas de relais TCP ni de boucles de fond,
 * qui ouvriraient de vrais ports et rendraient le test instable. Les routes
 * tournent sur les memes depots globaux que la production.
 */
class RoutesIntegrationTest {

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.fresh()
    }

    // Ce serveur ne vend rien : aucun quota a monter, la couture
    // `quotaGate` de `deviceRoutes` laisse passer par defaut.

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
            configureAuth(userRepository, com.jeanloickdt.auth.LocalTestAuth.service)
            // per-test relay seams (DI) — isolated, no global singleton
            val connections = com.jeanloickdt.relay.ConnectionRegistry()
            val buffers     = com.jeanloickdt.relay.HistoryBuffers()
            val lastValues  = com.jeanloickdt.relay.InMemoryLastValueCache()
            val events      = com.jeanloickdt.relay.ControlEventBroadcaster(connections)
            // same composition as production: routes get the cache-aware repo so
            // the cascade purge path is exercised by the tests.
            routing {
                systemRoutes()
                val accountPurge = com.jeanloickdt.auth.AccountPurge(
                    userRepository, projectRepository, deviceRepository, 
                signalRepository, signalHistoryRepository,
                    connections, events
                )
                authRoutes(
                    userRepository, projectRepository, deviceRepository, connections,
                    com.jeanloickdt.auth.LocalTestAuth.service, accountPurge
                )
                projectRoutes(
                    projectRepository, deviceRepository, 
                    signalRepository, signalHistoryRepository,
                    connections, events
                )
                deviceRoutes(deviceRepository, projectRepository, connections, events)
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
        return id to com.jeanloickdt.auth.LocalTestAuth.token(id, tokenVersion = 0)
    }

    private fun jsonOf(body: String) = Json.parseToJsonElement(body).jsonObject

    // ── tests ───────────────────────────────────────────────────





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
    fun `creating a project with a blank name is rejected`() = testApplication {
        installTestApp()
        val (_, token) = createUser("alice", "password1")
        val res = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"  "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `renaming a project to a blank name is rejected`() = testApplication {
        installTestApp()
        val (_, token) = createUser("alice", "password1")
        val project = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Alice project"}""")
        }
        val projectId = jsonOf(project.bodyAsText())["id"]!!.jsonPrimitive.content

        val res = client.patch("/api/projects/$projectId/name") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"x"}""")  // 1 char < 2 → rejected
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `creating a project trims the name`() = testApplication {
        installTestApp()
        val (_, token) = createUser("alice", "password1")
        val res = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"  Living room  "}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        assertEquals("Living room", jsonOf(res.bodyAsText())["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deleting a project cascades its devices away`() = testApplication {
        installTestApp()
        val (_, token) = createUser("alice", "password1")

        val project = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Alice project"}""")
        }
        val projectId = jsonOf(project.bodyAsText())["id"]!!.jsonPrimitive.content

        client.post("/api/devices") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"esp-to-purge","projectId":"$projectId","deviceType":"ESP32","connectivity":"WIFI"}""")
        }
        // device exists before the delete
        assertTrue(client.get("/api/devices") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.bodyAsText().contains("esp-to-purge"))

        val del = client.delete("/api/projects/$projectId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, del.status)

        // project gone (404) and the device cascaded away (atomic Step-3 delete)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/projects/$projectId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.status)
        assertFalse(client.get("/api/devices") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.bodyAsText().contains("esp-to-purge"), "the project's device must be cascade-deleted")
    }

    @Test
    fun `a user cannot create a device in another user's project`() = testApplication {
        installTestApp()
        val (_, tokenA) = createUser("alice", "password1")
        val (_, tokenB) = createUser("bob", "password2")

        // Bob owns a project
        val bobProject = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Bob project"}""")
        }
        assertEquals(HttpStatusCode.Created, bobProject.status)
        val bobProjectId = jsonOf(bobProject.bodyAsText())["id"]!!.jsonPrimitive.content

        // Alice tries to drop a device into Bob's project → 404, nothing created
        val intrude = client.post("/api/devices") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"evil","projectId":"$bobProjectId","deviceType":"ESP32","connectivity":"WIFI"}""")
        }
        assertEquals(HttpStatusCode.NotFound, intrude.status)

        // The device must not exist in Bob's project (no phantom injected)
        val bobDevices = client.get("/api/projects/$bobProjectId/devices") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }
        assertEquals(HttpStatusCode.OK, bobDevices.status)
        assertFalse(bobDevices.bodyAsText().contains("evil"), "no device may be injected into another user's project")
    }

    @Test
    fun `a user can create a device in their own project`() = testApplication {
        installTestApp()
        val (_, tokenA) = createUser("alice", "password1")

        val project = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Alice project"}""")
        }
        val projectId = jsonOf(project.bodyAsText())["id"]!!.jsonPrimitive.content

        val created = client.post("/api/devices") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-esp","projectId":"$projectId","deviceType":"ESP32","connectivity":"WIFI"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val json = jsonOf(created.bodyAsText())
        assertEquals(projectId, json["projectId"]!!.jsonPrimitive.content)
        assertEquals("my-esp", json["name"]!!.jsonPrimitive.content)
        assertTrue(json["token"]!!.jsonPrimitive.content.isNotBlank(), "plaintext token returned once")

        // and it shows up in the owner's project device list
        val list = client.get("/api/projects/$projectId/devices") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertTrue(list.bodyAsText().contains("my-esp"))
    }

    @Test
    fun `listing devices of another user's project is refused with 404`() = testApplication {
        installTestApp()
        val (_, tokenA) = createUser("alice", "password1")
        val (_, tokenB) = createUser("bob", "password2")

        val bobProject = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Bob project"}""")
        }
        val bobProjectId = jsonOf(bobProject.bodyAsText())["id"]!!.jsonPrimitive.content

        // Alice does not own the project → 404 (single ownership pattern), not an
        // empty 200 that would conflate "empty" with "not yours".
        val res = client.get("/api/projects/$bobProjectId/devices") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)

        // owner still lists fine (200)
        val ownerRes = client.get("/api/projects/$bobProjectId/devices") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }
        assertEquals(HttpStatusCode.OK, ownerRes.status)
    }

    @Test
    fun `creating a device with a blank name is rejected`() = testApplication {
        installTestApp()
        val (_, tokenA) = createUser("alice", "password1")
        val project = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Alice project"}""")
        }
        val projectId = jsonOf(project.bodyAsText())["id"]!!.jsonPrimitive.content

        val res = client.post("/api/devices") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"  ","projectId":"$projectId","deviceType":"ESP32","connectivity":"WIFI"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
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

    // ── La liste ne transporte pas les layouts ────────────────────────────

    @Test
    fun `the project list carries no layout at all`() = testApplication {
        // La liste sert à afficher des NOMS. Elle transportait le champ le plus
        // lourd de chaque projet, à chaque ouverture de l'app — et un layout
        // volumineux est stocké hors ligne par PostgreSQL (TOAST), donc le
        // servir veut dire aller le LIRE sur le disque, pas seulement le
        // sérialiser.
        //
        // Le test regarde la charge utile, pas le type : c'est ce que l'app
        // reçoit réellement, et c'est là que le coût se paie.
        installTestApp()
        val (_, token) = createUser("alice", "password1")

        val created = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Atelier"}""")
        }
        val projectId = jsonOf(created.bodyAsText())["id"]!!.jsonPrimitive.content

        // Un layout reconnaissable : s'il traverse, on le verra.
        val marker = "GROSSE-CHAINE-DE-LAYOUT"
        client.patch("/api/projects/$projectId/layout") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"layoutJson":"{\"marker\":\"$marker\"}"}""")
        }

        val list = client.get("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val body = list.bodyAsText()

        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue("Atelier" in body, "la liste doit toujours porter les noms")
        assertFalse("layoutJson" in body, "le champ n'a rien à faire dans une liste de noms")
        assertFalse(marker in body, "et sa valeur encore moins")

        // Le détail, lui, le porte toujours — c'est lui que l'app appelle en
        // ouvrant un projet.
        val detail = client.get("/api/projects/$projectId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertTrue(marker in detail.bodyAsText(), "le détail reste complet")
    }

    @Test
    fun `deleting a project with no device at all still works`() = testApplication {
        // La cascade passe une LISTE d'identifiants de cartes aux deux purges
        // par lot. Vide, elle deviendrait `IN ()` — une erreur de syntaxe sur
        // la plupart des moteurs, et un projet sans carte ne pourrait plus être
        // supprimé.
        //
        // Le cas le plus banal qui soit : un projet qu'on vient de créer.
        installTestApp()
        val (_, token) = createUser("alice", "password1")

        val created = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Vide"}""")
        }
        val projectId = jsonOf(created.bodyAsText())["id"]!!.jsonPrimitive.content

        val res = client.delete("/api/projects/$projectId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        val after = client.get("/api/projects") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertFalse("Vide" in after.bodyAsText(), "le projet doit avoir disparu")
    }

    // ── Le layout est borné ───────────────────────────────────────────────

    @Test
    fun `a layout beyond the limit is refused`() = testApplication {
        // Le nom est validé 2-64 caractères ; le layout ne l'était pas du tout.
        // Un client bogué ou malveillant pouvait pousser des dizaines de
        // mégaoctets — stockés, relus, et présents dans chaque sauvegarde.
        installTestApp()
        val (_, token) = createUser("alice", "password1")

        val created = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Atelier"}""")
        }
        val projectId = jsonOf(created.bodyAsText())["id"]!!.jsonPrimitive.content

        val enorme = "a".repeat(300 * 1024)
        val res = client.patch("/api/projects/$projectId/layout") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"layoutJson":"$enorme"}""")
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
        assertTrue(
            "256" in res.bodyAsText(),
            "la borne part dans le message, pour que l'app prévienne avant d'envoyer — " +
                "reçu : ${res.bodyAsText().take(120)}"
        )
    }

    @Test
    fun `the limit counts UTF-8 bytes, not characters`() = testApplication {
        // LE test subtil. `String.length` compte des unités de code : un layout
        // plein d'accents ou d'emoji passerait une vérification en caractères
        // et dépasserait la borne en base, là où elle compte.
        //
        // Chaque « é » pèse deux octets en UTF-8 : 200 000 caractères font
        // 400 000 octets. Sous la limite si on compte mal, au-dessus si on
        // compte juste.
        installTestApp()
        val (_, token) = createUser("alice", "password1")

        val created = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Atelier"}""")
        }
        val projectId = jsonOf(created.bodyAsText())["id"]!!.jsonPrimitive.content

        val accents = "é".repeat(200_000)
        assertTrue(accents.length < 256 * 1024, "précondition : sous la borne si on compte les caractères")
        assertTrue(
            accents.toByteArray(Charsets.UTF_8).size > 256 * 1024,
            "précondition : au-dessus si on compte les octets"
        )

        val res = client.patch("/api/projects/$projectId/layout") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"layoutJson":"$accents"}""")
        }

        assertEquals(
            HttpStatusCode.PayloadTooLarge, res.status,
            "compter les caractères laisserait passer deux fois la borne"
        )
    }

    @Test
    fun `a layout under the limit still goes through`() = testApplication {
        // Le pendant : une borne qui refuse tout serait aussi fausse. Un gros
        // tableau de bord réaliste pèse une dizaine de kilo-octets ; on vérifie
        // qu'on est très loin de le gêner.
        installTestApp()
        val (_, token) = createUser("alice", "password1")

        val created = client.post("/api/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Atelier"}""")
        }
        val projectId = jsonOf(created.bodyAsText())["id"]!!.jsonPrimitive.content

        val realiste = "w".repeat(64 * 1024)
        val res = client.patch("/api/projects/$projectId/layout") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"layoutJson":"$realiste"}""")
        }

        assertEquals(HttpStatusCode.OK, res.status)
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

        // Visait `/api/login` avec un mauvais mot de passe. Cette porte
        // n'existe plus : le relais ne verifie aucun mot de passe, et la
        // route delegue a iia — injoignable ici. L'intention du test tient
        // toujours, elle vise juste une autre erreur : une route
        // authentifiee, sans jeton.
        val res = client.get("/api/admin/stats")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
