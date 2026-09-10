package com.jeanloickdt.automation

import com.jeanloickdt.auth.configureAuth
import com.jeanloickdt.automation.v2.AutomationEngine
import com.jeanloickdt.automation.v2.AutomationRuns
import com.jeanloickdt.automation.v2.InventoryResolver
import com.jeanloickdt.automation.v2.RuleCache
import com.jeanloickdt.automation.v2.SignalValueCache
import com.jeanloickdt.deviceRepository
import com.jeanloickdt.event.EventSinks
import com.jeanloickdt.projectRepository
import com.jeanloickdt.signalRepository
import com.jeanloickdt.userRepository
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
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * L'apparence d'une règle : une icône et une couleur, rangées sur le serveur.
 *
 * ## Ce qui la distingue de celle d'un projet
 *
 * Elle voyage avec la RÈGLE, dans la création et dans le `PATCH`, au lieu
 * d'avoir sa propre route. C'est le geste qui commande : une règle se compose
 * dans un éditeur où tout est déjà sous la main, alors qu'un projet se crée
 * d'un nom et se décore plus tard, depuis sa carte.
 *
 * ## Le piège que garde `renommer ne touche pas a l'apparence`
 *
 * `PATCH` est partiel : `null` veut dire « ne touche pas ». Sans cette
 * distinction, renommer une règle effacerait son icône au passage, et
 * personne ne ferait le lien entre les deux.
 */
class RuleAppearanceTest {

    private lateinit var cache: RuleCache
    private lateinit var resolver: InventoryResolver
    private lateinit var runs: AutomationRuns

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.connectAndClean()
        resolver = InventoryResolver(signalRepository, deviceRepository)
        cache = RuleCache(resolver)
    }

    private fun ApplicationTestBuilder.installTestApp() {
        runs = AutomationRuns()
        val engine = AutomationEngine(
            sinks = EventSinks(),
            cache = cache,
            values = SignalValueCache(signalRepository),
            actions = ExposedPendingActionRepository(),
            runs = runs,
            devices = deviceRepository,
            isDeviceOnline = { _, _ -> true }
        )
        application {
            install(ContentNegotiation) { json() }
            configureAuth(userRepository, com.jeanloickdt.auth.LocalTestAuth.service)
            routing {
                ruleRoutes(
                    cache, resolver, runs,
                    RulePolicies(allowedActionTypes = setOf(DeliveryWorker.TYPE_PUSH)),
                    engine
                )
            }
        }
    }

    private class Account(
        val ownerId: String, val token: String,
        val projectId: String, val deviceId: String
    ) {
        fun signal(address: Int = 0) =
            """{"projectId":"$projectId","deviceId":"$deviceId","address":$address}"""
    }

    private fun account(username: String): Account {
        val id = userRepository.create(username, BCrypt.hashpw("secret123", BCrypt.gensalt()), "user", true)
        val projectId = projectRepository.create(id, "p-$username").id
        val deviceId = deviceRepository.create(
            name = "board", projectId = projectId, ownerId = id, tokenHash = "h-$username",
            deviceType = com.jeanloickdt.device.domain.DeviceType.ESP32,
            connectivity = com.jeanloickdt.device.domain.DeviceConnectivity.WIFI
        ).id
        signalRepository.create(id, deviceId, 0, "Temp", "float", nowMs = 0L)
        return Account(id, com.jeanloickdt.auth.LocalTestAuth.token(id, tokenVersion = 0), projectId, deviceId)
    }

    /** « Quand Temp change, préviens-moi. » */
    private fun pushDef(a: Account) =
        """{"trigger":{"kind":"signalChanged","signal":${a.signal()}},""" +
            """"condition":null,"actions":[{"kind":"push","title":"t","body":"b"}]}"""

    private suspend fun io.ktor.client.HttpClient.creer(
        a: Account, extra: String = "", name: String = "Serre"
    ): HttpResponse = post("/api/rules") {
        header(HttpHeaders.Authorization, "Bearer ${a.token}")
        contentType(ContentType.Application.Json)
        val def = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(pushDef(a)))
        setBody("""{"name":"$name","definition":$def$extra}""")
    }

    private suspend fun io.ktor.client.HttpClient.seule(a: Account) =
        Json.parseToJsonElement(
            get("/api/rules") { header(HttpHeaders.Authorization, "Bearer ${a.token}") }.bodyAsText()
        ).jsonArray.single().jsonObject

    private suspend fun HttpResponse.id(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content

    @Test
    fun `une regle nait sans apparence`() = testApplication {
        installTestApp()
        val a = account("alice")

        val creee = client.creer(a)
        assertEquals(HttpStatusCode.Created, creee.status, creee.bodyAsText())
        // `null` dit « jamais choisie », ce qui n'est pas « choisie puis
        // retiree » — l'app peut proposer un repli sans ecraser un choix.
        val regle = client.seule(a)
        assertEquals(JsonNull, regle.getValue("icon"))
        assertEquals(JsonNull, regle.getValue("color"))
    }

    @Test
    fun `l'apparence se pose a la creation et se relit dans la liste`() = testApplication {
        installTestApp()
        val a = account("bob")

        val creee = client.creer(a, extra = ""","icon":"bolt","color":"amber"""")
        assertEquals(HttpStatusCode.Created, creee.status, creee.bodyAsText())

        // Elle survit a une RELECTURE, pas seulement au retour de l'ecriture :
        // c'est la liste qui dessine les cartes.
        val regle = client.seule(a)
        assertEquals("bolt", regle.getValue("icon").jsonPrimitive.content)
        assertEquals("amber", regle.getValue("color").jsonPrimitive.content)
    }

    @Test
    fun `une cle mal formee est refusee`() = testApplication {
        installTestApp()
        val a = account("carol")

        // Le serveur ne connait PAS le catalogue, et c'est voulu : l'app en
        // ajoute quand elle veut. Il refuse seulement ce qui n'est
        // manifestement pas une cle — sans cette borne, ce champ devient un
        // endroit ou ranger n'importe quoi dans la base de quelqu'un d'autre.
        val creee = client.creer(a, extra = ""","icon":"BOLT!!""""")
        assertEquals(HttpStatusCode.BadRequest, creee.status, creee.bodyAsText())
    }

    @Test
    fun `renommer ne touche pas a l'apparence`() = testApplication {
        installTestApp()
        val a = account("dave")
        val id = client.creer(a, extra = ""","icon":"bolt","color":"amber"""").id()

        val renommee = client.patch("/api/rules/$id") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Serre du fond"}""")
        }
        assertEquals(HttpStatusCode.OK, renommee.status, renommee.bodyAsText())

        val regle = client.seule(a)
        assertEquals("Serre du fond", regle.getValue("name").jsonPrimitive.content)
        assertEquals(
            "bolt", regle.getValue("icon").jsonPrimitive.content,
            "un PATCH qui ne parle que du nom a efface l'icône"
        )
        assertEquals("amber", regle.getValue("color").jsonPrimitive.content)
    }

    @Test
    fun `une chaine vide retire l'apparence`() = testApplication {
        installTestApp()
        val a = account("erin")
        val id = client.creer(a, extra = ""","icon":"bolt","color":"amber"""").id()

        client.patch("/api/rules/$id") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"icon":"","color":""}""")
        }

        val regle = client.seule(a)
        assertEquals(JsonNull, regle.getValue("icon"))
        assertEquals(JsonNull, regle.getValue("color"))
    }
}
