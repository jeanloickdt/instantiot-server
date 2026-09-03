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
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.deviceRepository
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.projectRepository
import com.jeanloickdt.relay.SignalRef
import com.jeanloickdt.userRepository
import com.jeanloickdt.signalRepository
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
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The API that unlocks the app. Its guards protect three different people:
 * the ENGINE (invalid definitions never reach the table it loads), the OTHER
 * TENANT (ownership 404s, cross-tenant commands), and the USER THEMSELVES
 * (the self-feeding loop refused at the door).
 */
class RuleRoutesTest {

    private lateinit var cache: RuleCache

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.fresh()
        cache = RuleCache()
    }

    /**
     * Le harnais cable LES TROIS canaux.
     *
     * La plupart de ces épreuves portent sur le CRUD, le cloisonnement entre
     * comptes et les quotas — pas sur la disponibilité d'un canal. Elles
     * utilisent `pushDef()` comme décor, et hériter du défaut (qui exclut
     * `PUSH`) les ferait échouer pour une raison qui n'est pas la leur. Les
     * épreuves qui portent VRAIMENT sur la frontière passent leur politique.
     */
    private fun ApplicationTestBuilder.installTestApp(
        policies: RulePolicies = RulePolicies(
            allowedActionTypes = setOf(
                RuleDefinition.TYPE_PUSH, RuleDefinition.TYPE_EMAIL, RuleDefinition.TYPE_COMMAND
            )
        )
    ) {
        application {
            install(ContentNegotiation) { json() }
            configureAuth(userRepository, com.jeanloickdt.auth.LocalTestAuth.service)
            routing { ruleRoutes(cache, signalRepository, deviceRepository, policies) }
        }
    }

    private fun account(username: String): Triple<String, String, Pair<String, String>> {
        val id = userRepository.create(username, BCrypt.hashpw("secret123", BCrypt.gensalt()), "user", true)
        val projectId = projectRepository.create(id, "p-$username").id
        val deviceId = deviceRepository.create(
            name         = "board", projectId = projectId, ownerId = id, tokenHash = "h-$username",
            deviceType = com.jeanloickdt.device.domain.DeviceType.ESP32,
            connectivity = com.jeanloickdt.device.domain.DeviceConnectivity.WIFI
        ).id
        // La cible d'une règle est une clé de SIGNAL — `deviceId:adresse` —
        // depuis le retrait du modèle widget.
        signalRepository.create(id, deviceId, 0, "s0", "float", nowMs = 0L)
        return Triple(id, com.jeanloickdt.auth.LocalTestAuth.token(id, tokenVersion = 0), deviceId to "$deviceId:0")
    }

    private suspend fun io.ktor.client.HttpClient.createRule(
        token: String, widgetId: String?, definition: String, name: String = "règle"
    ): HttpResponse = post("/api/rules") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        val signalKey = widgetId?.let { """"triggerSignalKey":"$it",""" } ?: ""
        setBody("""{"name":"$name",$signalKey"definition":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(definition))}}""")
    }

    private fun pushDef(above: Double = 90.0) =
        """{"when":{"kind":"value","above":$above},"actions":[{"type":"PUSH","title":"t","body":"b"}]}"""

    // ── Le cycle complet ──────────────────────────────────────────────────

    @Test
    fun `create, list, disable, delete — and the producers' gate follows`() = testApplication {
        installTestApp()
        val (ownerId, token, ids) = account("alice")
        val (_, widgetId) = ids

        assertFalse(cache.watches(SignalRef(ownerId, widgetId)), "before: nobody watches")

        val created = client.createRule(token, widgetId, pushDef())
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val ruleId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        assertTrue(cache.watches(SignalRef(ownerId, widgetId)),
            "the mutation reloaded the cache — the hot path starts publishing THIS instant")

        val list = client.get("/api/rules") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(1, Json.parseToJsonElement(list.bodyAsText()).jsonArray.size)

        val disabled = client.patch("/api/rules/$ruleId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }
        assertEquals(HttpStatusCode.OK, disabled.status)
        assertFalse(cache.watches(SignalRef(ownerId, widgetId)),
            "a disabled rule stops the producer too — the frame costs a predicate again")

        val deleted = client.delete("/api/rules/$ruleId") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertEquals("[]", client.get("/api/rules") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText())
    }

    // ── Les gardes pour le moteur ─────────────────────────────────────────

    @Test
    fun `an invalid definition is a 400 with the reason, never a row`() = testApplication {
        installTestApp()
        val (_, token, ids) = account("bob")

        val res = client.createRule(token, ids.second, """{"when":{"kind":"teleport"},"actions":[]}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue("unknown kind" in res.bodyAsText(), "the app needs the WHY: ${res.bodyAsText()}")
    }

    @Test
    fun `a value rule needs a widget the caller owns`() = testApplication {
        installTestApp()
        val (_, token, _) = account("carol")
        val (_, _, other) = account("victim")

        assertEquals(HttpStatusCode.BadRequest, client.createRule(token, null, pushDef()).status,
            "no widget at all")
        assertEquals(HttpStatusCode.NotFound, client.createRule(token, other.second, pushDef()).status,
            "someone else's widget — 404, never reveal it exists")
    }

    // ── Les gardes pour l'autre locataire ─────────────────────────────────

    @Test
    fun `an offline rule cannot watch another tenant's device`() = testApplication {
        installTestApp()
        val (_, token, _) = account("dave")
        val (_, _, victim) = account("victim2")

        val res = client.createRule(token, null,
            """{"when":{"kind":"offline","deviceId":"${victim.first}"},"actions":[{"type":"PUSH","title":"t","body":"b"}]}""")
        assertEquals(HttpStatusCode.NotFound, res.status,
            "watching someone's presence is surveillance — 404")
    }

    @Test
    fun `a COMMAND aimed at another tenant's board is refused at creation`() = testApplication {
        installTestApp()
        val (_, token, ids) = account("erin")
        val (_, _, victim) = account("victim3")

        val res = client.createRule(token, ids.second,
            """{"when":{"kind":"value","above":90},"actions":[{"type":"COMMAND","deviceId":"${victim.first}","payloadB64":"AA=="}]}""")
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `rules are invisible across tenants — 404 on foreign PATCH and DELETE`() = testApplication {
        installTestApp()
        val (_, aliceToken, aliceIds) = account("alice2")
        val created = client.createRule(aliceToken, aliceIds.second, pushDef())
        val ruleId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val (_, malloryToken, _) = account("mallory")
        assertEquals(HttpStatusCode.NotFound, client.patch("/api/rules/$ruleId") {
            header(HttpHeaders.Authorization, "Bearer $malloryToken")
            contentType(ContentType.Application.Json); setBody("""{"enabled":false}""")
        }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/rules/$ruleId") {
            header(HttpHeaders.Authorization, "Bearer $malloryToken")
        }.status)
    }

    // ── La garde pour l'utilisateur lui-même ──────────────────────────────

    @Test
    fun `a COMMAND writing the watched signal is refused — the rule would feed itself`() {
        // La boucle passe par le MATERIEL : la regle ecrit le signal qu'elle
        // surveille, la carte renvoie la valeur, la regle se redeclenche. Ni
        // `depth` ni le refroidissement ne peuvent la voir — elle doit etre
        // refusee a la creation.
        //
        // La comparaison porte sur (carte, adresse). Le modele widget
        // comparait deux chaines : le nom que portait la trame et le
        // declencheur de la regle. Depuis que le declencheur est une cle
        // `deviceId:adresse`, ces deux chaines ne peuvent plus etre egales —
        // la garde aurait laisse passer toutes les boucles sans qu'un seul
        // test le remarque.
        testApplication {
        installTestApp()
        val (_, token, ids) = account("frank")
        val (deviceId, signalKey) = ids

        // Une vraie trame SIGNAL sur l'adresse 0 — celle que la regle surveille.
        val b64 = java.util.Base64.getEncoder().encodeToString(
            com.jeanloickdt.signal.SignalFrame.build(
                0, com.jeanloickdt.signal.SignalFrame.TAG_FLOAT,
                com.jeanloickdt.signal.SignalFrame.floatBytes(1f)
            )
        )

        val res = client.createRule(token, signalKey,
            """{"when":{"kind":"value","above":90},"actions":[{"type":"COMMAND","deviceId":"$deviceId","payloadB64":"$b64"}]}""")
        assertEquals(HttpStatusCode.BadRequest, res.status, res.bodyAsText())
        assertTrue("trigger itself" in res.bodyAsText())

        // La MEME trame, mais la regle surveille une autre adresse : pas de
        // boucle, la creation passe.
        val frankId = userRepository.findByUsername("frank")!!.id
        signalRepository.create(frankId, deviceId, 1, "s1", "float", nowMs = 0L)
        val ok = client.createRule(token, "$deviceId:1",
            """{"when":{"kind":"value","above":90},"actions":[{"type":"COMMAND","deviceId":"$deviceId","payloadB64":"$b64"}]}""")
        assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
        }
    }

    // ── La frontière de l'OFFRE ───────────────────────────────────────────

    @Test
    fun `PUSH is refused where no sender will ever exist — at creation, with the why`() = testApplication {
        installTestApp(RulePolicies(allowedActionTypes = setOf(
            RuleDefinition.TYPE_EMAIL, RuleDefinition.TYPE_COMMAND
        )))
        val (_, token, ids) = account("grace")

        val res = client.createRule(token, ids.second, pushDef())
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue("InstantIoT Cloud" in res.bodyAsText(),
            "the refusal must say WHY — not enqueue rows the worker can only mark DEAD")
    }

    @Test
    fun `le defaut EXCLUT push — un defaut permissif se paie toujours du meme cote`() = testApplication {
        // La faute que le depot du nuage a reellement commise en recopiant ce
        // cablage : il avait perdu le parametre, herite du defaut, et une regle
        // de notification y mourait en silence chez le livreur.
        installTestApp(RulePolicies())   // LE defaut, celui dont on peut heriter
        val (_, token, ids) = account("defaut")

        val res = client.createRule(token, ids.second, pushDef())
        assertEquals(HttpStatusCode.BadRequest, res.status,
            "sans expediteur declare, PUSH ne doit pas etre creable")
    }

    // ── Le portail de quota (câblé côté cloud sur enforceStock) ───────────

    @Test
    fun `the quota gate can refuse, and sees the right classification`() = testApplication {
        var asked: Pair<Boolean, Int>? = null
        // Les trois canaux : cette épreuve porte sur le portail de quota, et sa
        // règle est volontairement PUSH-seule — c'est ce qui la classe en
        // NOTIFICATION plutôt qu'en automatisation.
        installTestApp(RulePolicies(
            allowedActionTypes = setOf(
                RuleDefinition.TYPE_PUSH, RuleDefinition.TYPE_EMAIL, RuleDefinition.TYPE_COMMAND
            ),
            quotaGate = { call, _, isAutomation, current ->
            asked = isAutomation to current()
            call.respondQuota()
            false
        }))
        val (_, token, ids) = account("henry")

        val res = client.createRule(token, ids.second, pushDef())
        assertEquals(HttpStatusCode.PaymentRequired, res.status)
        assertEquals(false to 0, asked, "a PUSH-only rule counts as a NOTIFICATION, zero existing")
    }
}


private suspend fun io.ktor.server.application.ApplicationCall.respondQuota() =
    respondText(
        """{"error":"quota"}""",
        io.ktor.http.ContentType.Application.Json,
        io.ktor.http.HttpStatusCode.PaymentRequired
    )
