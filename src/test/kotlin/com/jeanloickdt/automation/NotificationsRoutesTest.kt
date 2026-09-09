package com.jeanloickdt.automation

import com.jeanloickdt.auth.configureAuth
import com.jeanloickdt.automation.data.AutomationRuleTable
import com.jeanloickdt.automation.data.PendingActionTable
import com.jeanloickdt.database.TestDatabase
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/notifications` — le fil de ce que les automatisations ont fait.
 *
 * Ce que les épreuves défendent, dans cet ordre :
 * le REGROUPEMENT (un tir aux deux canaux = une ligne aux deux canaux), le
 * cloisonnement (le fil d'un compte n'a jamais celui d'un autre), l'ORDRE et
 * la PAGINATION (le curseur reprend là où il s'arrête, sans doublon), et enfin
 * les cas limites qui font la solidité — une règle supprimée, un tir sans
 * règle, une borne de limite dépassée.
 */
class NotificationsRoutesTest {

    private val repo = ExposedNotificationRepository()
    private val actions = ExposedPendingActionRepository()

    @BeforeTest
    fun setup() {
        TestDatabase.connectAndClean()
    }

    private fun ApplicationTestBuilder.installTestApp() {
        application {
            install(ContentNegotiation) { json() }
            configureAuth(userRepository, com.jeanloickdt.auth.LocalTestAuth.service)
            routing { notificationsRoutes(repo) }
        }
    }

    private fun account(username: String): Pair<String, String> {
        val id = userRepository.create(username, BCrypt.hashpw("s", BCrypt.gensalt()), "user", true)
        return id to com.jeanloickdt.auth.LocalTestAuth.token(id, tokenVersion = 0)
    }

    private fun createRule(ownerId: String, ruleId: String, name: String, severity: String = "info") = transaction {
        AutomationRuleTable.insert {
            it[id] = ruleId
            it[AutomationRuleTable.ownerId] = ownerId
            it[AutomationRuleTable.name] = name
            it[enabled] = true
            it[triggerKind] = "value"
            it[triggerSignalKey] = "dev-x:0"
            it[definition] = """{"when":{"kind":"value","above":0.0},"actions":[]}"""
            it[AutomationRuleTable.severity] = severity
            it[createdAt] = 0L
            it[updatedAt] = 0L
        }
    }

    private val seq = AtomicInteger()
    private fun insertAction(
        ownerId: String, ruleId: String?, type: String,
        occurredAt: Long, status: String = PendingAction.SENT, attempts: Int = 1,
        payload: String = "{}",
        severity: String = "info"
    ) = transaction {
        PendingActionTable.insert {
            it[idempotencyKey] = "t-${seq.incrementAndGet()}"
            it[PendingActionTable.ownerId] = ownerId
            it[PendingActionTable.ruleId] = ruleId
            it[PendingActionTable.type] = type
            it[PendingActionTable.payload] = payload
            it[PendingActionTable.status] = status
            it[PendingActionTable.attempts] = attempts
            it[nextAttemptAt] = occurredAt
            it[PendingActionTable.occurredAt] = occurredAt
            it[PendingActionTable.severity] = severity
            it[createdAt] = occurredAt
        }
    }

    // ── Le regroupement — la vraie raison d'être de cette route ────────────

    @Test
    fun `deux notifications d'un meme tir font une seule ligne`() = testApplication {
        installTestApp()
        val (owner, jwt) = account("alice")
        createRule(owner, "r1", "Alerte gel")

        // MEME rule_id et MEME occurredAt — c'est un tir, pas deux.
        insertAction(owner, "r1", "PUSH", occurredAt = 1_000L)
        insertAction(owner, "r1", "PUSH", occurredAt = 1_000L, status = PendingAction.DEAD, attempts = 3)

        val fires = fires(jwt)
        assertEquals(1, fires.size, "deux envois d'un meme tir sont UN evenement")
        val fire = fires[0].jsonObject
        assertEquals("r1", fire["ruleId"]!!.jsonPrimitive.content)
        assertEquals("Alerte gel", fire["ruleName"]!!.jsonPrimitive.content)
        assertEquals(1_000L, fire["occurredAt"]!!.jsonPrimitive.content.toLong())

        val channels = fire["channels"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, channels.size)
        val mort = channels.first { it["status"]!!.jsonPrimitive.content == "DEAD" }
        assertEquals(3, mort["attempts"]!!.jsonPrimitive.int)
    }

    // ── Ce que le fil NE porte PAS ────────────────────────────────────────

    @Test
    fun `un email envoye par une regle ne fait aucune ligne`() = testApplication {
        installTestApp()
        val (owner, jwt) = account("mailer")
        createRule(owner, "r-mail", "Rapport")

        insertAction(owner, "r-mail", "EMAIL", occurredAt = 1_000L,
            payload = """{"subject":"Rapport quotidien","body":"Tout va bien"}""")

        // L'email est PARTI — c'est exactement ce qu'on avait demande. Le
        // signaler en plus dans le fil ferait croire qu'il s'est passe
        // quelque chose d'autre.
        assertEquals(emptyList(), fires(jwt))
    }

    @Test
    fun `une commande vers une carte ne fait aucune ligne`() = testApplication {
        installTestApp()
        val (owner, jwt) = account("commandeur")
        createRule(owner, "r-cmd", "Pompe")

        insertAction(owner, "r-cmd", "COMMAND", occurredAt = 1_000L)

        assertEquals(emptyList(), fires(jwt))
    }

    @Test
    fun `l'email d'un tir disparait, la notification du meme tir reste`() = testApplication {
        installTestApp()
        val (owner, jwt) = account("les-deux")
        createRule(owner, "r2", "Gel")

        insertAction(owner, "r2", "PUSH", occurredAt = 2_000L)
        insertAction(owner, "r2", "EMAIL", occurredAt = 2_000L)

        val channels = fires(jwt).single().jsonObject["channels"]!!.jsonArray
        assertEquals(1, channels.size, "seul le push se lit ici")
        assertEquals("PUSH", channels.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    // ── Le cloisonnement ──────────────────────────────────────────────────

    @Test
    fun `le fil d'un compte n'a jamais celui d'un autre`() = testApplication {
        installTestApp()
        val (a, jwtA) = account("a")
        val (b, _) = account("b")
        createRule(a, "ra", "sienne")
        createRule(b, "rb", "voisine")

        insertAction(a, "ra", "PUSH", occurredAt = 1_000L)
        insertAction(b, "rb", "PUSH", occurredAt = 1_500L)

        val fires = fires(jwtA)
        assertEquals(1, fires.size)
        assertEquals("ra", fires[0].jsonObject["ruleId"]!!.jsonPrimitive.content)
    }

    // ── L'ordre — le plus recent en tete ───────────────────────────────────

    @Test
    fun `l'ordre est le plus recent en tete`() = testApplication {
        installTestApp()
        val (owner, jwt) = account("c")
        createRule(owner, "r1", "vieux")
        createRule(owner, "r2", "recent")

        insertAction(owner, "r1", "PUSH", occurredAt = 1_000L)
        insertAction(owner, "r2", "PUSH", occurredAt = 2_000L)

        val fires = fires(jwt)
        assertEquals(listOf(2_000L, 1_000L), fires.map { it.jsonObject["occurredAt"]!!.jsonPrimitive.long })
    }

    // ── La pagination — le curseur reprend, sans doublon ni oubli ─────────

    @Test
    fun `le curseur reprend, sans doublon ni oubli`() = testApplication {
        installTestApp()
        val (owner, jwt) = account("d")
        createRule(owner, "r1", "seule")
        (1..5).forEach { insertAction(owner, "r1", "PUSH", occurredAt = it * 1000L) }

        // Page 1 : 2 tirs, curseur present
        val page1 = page(jwt, limit = 2)
        assertEquals(listOf(5_000L, 4_000L),
            page1.first.map { it.jsonObject["occurredAt"]!!.jsonPrimitive.long })
        assertEquals("4000", page1.second)

        // Page 2 : les deux suivants, curseur encore la
        val page2 = page(jwt, limit = 2, cursor = page1.second)
        assertEquals(listOf(3_000L, 2_000L),
            page2.first.map { it.jsonObject["occurredAt"]!!.jsonPrimitive.long })
        assertNotNull(page2.second)

        // Page 3 : le dernier, curseur null
        val page3 = page(jwt, limit = 2, cursor = page2.second)
        assertEquals(listOf(1_000L),
            page3.first.map { it.jsonObject["occurredAt"]!!.jsonPrimitive.long })
        assertNull(page3.second, "au bout du fil, plus de curseur")
    }

    // ── La regle supprimee : ruleName null, ruleId conserve ────────────────

    @Test
    fun `une regle supprimee garde son id, perd son nom`() = testApplication {
        installTestApp()
        val (owner, jwt) = account("e")
        // AUCUNE ligne dans automation_rules pour "orpheline" — la regle a ete supprimee
        insertAction(owner, "orpheline", "PUSH", occurredAt = 1_000L)

        val fire = fires(jwt).single().jsonObject
        assertEquals("orpheline", fire["ruleId"]!!.jsonPrimitive.content)
        assertEquals(true, fire["ruleName"]!!.jsonPrimitive.let { it.contentOrNull == null })
    }

    // ── Le tir sans regle : possible en base, doit rester lisible ─────────

    @Test
    fun `un tir sans rule_id se lit avec ruleId et ruleName nuls`() = testApplication {
        installTestApp()
        val (owner, jwt) = account("f")
        insertAction(owner, ruleId = null, type = "PUSH", occurredAt = 1_000L)

        val fire = fires(jwt).single().jsonObject
        assertTrue(fire["ruleId"]!!.jsonPrimitive.let { it.contentOrNull == null })
        assertTrue(fire["ruleName"]!!.jsonPrimitive.let { it.contentOrNull == null })
    }

    // ── Les bornes de limite ─────────────────────────────────────────────

    @Test
    fun `limit hors bornes rend 400`() = testApplication {
        installTestApp()
        val (_, jwt) = account("g")
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/notifications?limit=0") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/notifications?limit=101") {
            header(HttpHeaders.Authorization, "Bearer $jwt")
        }.status)
    }

    // ── Sans auth, rien ne sort ──────────────────────────────────────────

    @Test
    fun `sans jeton, aucun fil`() = testApplication {
        installTestApp()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/notifications").status)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private suspend fun ApplicationTestBuilder.fires(jwt: String) =
        Json.parseToJsonElement(
            client.get("/api/notifications") {
                header(HttpHeaders.Authorization, "Bearer $jwt")
            }.bodyAsText()
        ).jsonObject["fires"]!!.jsonArray

    private suspend fun ApplicationTestBuilder.page(
        jwt: String, limit: Int, cursor: String? = null
    ): Pair<List<kotlinx.serialization.json.JsonElement>, String?> {
        val q = buildString {
            append("/api/notifications?limit=").append(limit)
            cursor?.let { append("&cursor=").append(it) }
        }
        val body = Json.parseToJsonElement(
            client.get(q) { header(HttpHeaders.Authorization, "Bearer $jwt") }.bodyAsText()
        ).jsonObject
        val next = body["next"]?.jsonPrimitive?.contentOrNull
        return body["fires"]!!.jsonArray.toList() to next
    }
    // ── Le PAYLOAD : ce que la carte doit MONTRER ────────────────────────

    @Test
    fun `un PUSH remonte son title et son body`() = testApplication {
        installTestApp()
        val (owner, jwt) = account("payload-push")
        createRule(owner, "r-push", "Alerte gel")
        insertAction(owner, "r-push", "PUSH", 1_000L,
            payload = """{"title":"Frost — 1.4 °C","body":"Serre à 1.4"}""")

        val ch = fires(jwt).single().jsonObject["channels"]!!.jsonArray.single().jsonObject
        assertEquals("Frost — 1.4 °C", ch["title"]!!.jsonPrimitive.content)
        assertEquals("Serre à 1.4", ch["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun `un payload cabosse ne fait pas planter la reponse`() = testApplication {
        installTestApp()
        val (owner, jwt) = account("payload-corrompu")
        createRule(owner, "r-x", "X")
        insertAction(owner, "r-x", "PUSH", 1_000L, payload = "pas du JSON")

        // La reponse arrive quand meme ; title et body sont juste absents
        val ch = fires(jwt).single().jsonObject["channels"]!!.jsonArray.single().jsonObject
        assertEquals(true, ch["title"]?.jsonPrimitive?.contentOrNull == null)
        assertEquals(true, ch["body"]?.jsonPrimitive?.contentOrNull == null)
    }
    // ── Le NIVEAU voyage jusqu'au fil ──────────────────────────────────

    @Test
    fun `la severity du tir remonte dans la reponse`() = testApplication {
        installTestApp()
        val (owner, jwt) = account("severity-warning")
        createRule(owner, "r-w", "Alerte", severity = "warning")
        insertAction(owner, "r-w", "PUSH", 1_000L, severity = "warning")

        val fire = fires(jwt).single().jsonObject
        assertEquals("warning", fire["severity"]!!.jsonPrimitive.content)
    }

    @Test
    fun `un tir sans severity connue tombe sur info a l affichage`() = testApplication {
        installTestApp()
        val (owner, jwt) = account("severity-info")
        createRule(owner, "r-i", "Ordinaire")
        insertAction(owner, "r-i", "PUSH", 1_000L)

        val fire = fires(jwt).single().jsonObject
        assertEquals("info", fire["severity"]!!.jsonPrimitive.content)
    }

}

// Petites extensions pour lire les nombres — évite les casts partout
private val kotlinx.serialization.json.JsonPrimitive.int: Int get() = this.content.toInt()
private val kotlinx.serialization.json.JsonPrimitive.long: Long get() = this.content.toLong()
