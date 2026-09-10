package com.jeanloickdt.automation

import com.jeanloickdt.auth.configureAuth
import com.jeanloickdt.automation.v2.AutomationEngine
import com.jeanloickdt.automation.v2.AutomationRuns
import com.jeanloickdt.automation.v2.RunOutcome
import com.jeanloickdt.automation.v2.SignalValueCache
import com.jeanloickdt.automation.v2.InventoryResolver
import com.jeanloickdt.automation.v2.RuleCache
import com.jeanloickdt.deviceRepository
import com.jeanloickdt.event.EventSinks
import com.jeanloickdt.projectRepository
import com.jeanloickdt.signalRepository
import com.jeanloickdt.userRepository
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.BeforeTest
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L'API qui débloque l'app. Ses gardes protègent trois personnes différentes :
 * le MOTEUR (une définition invalide n'atteint jamais la table qu'il charge),
 * l'AUTRE LOCATAIRE (404 d'appartenance, commandes croisées), et
 * l'UTILISATEUR LUI-MÊME (la boucle qui se nourrit, signalée à la porte).
 */
class RuleRoutesTest {

    private lateinit var cache: RuleCache
    private lateinit var resolver: InventoryResolver

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.connectAndClean()
        resolver = InventoryResolver(signalRepository, deviceRepository)
        cache = RuleCache(resolver)
    }

    /**
     * Le harnais câble LES TROIS canaux.
     *
     * La plupart de ces épreuves portent sur le CRUD, le cloisonnement entre
     * comptes et les quotas — pas sur la disponibilité d'un canal. Hériter du
     * défaut de production (qui exclut `PUSH`) les ferait échouer pour une
     * raison qui n'est pas la leur. Les deux épreuves qui portent VRAIMENT sur
     * la frontière passent leur propre politique.
     */
    private lateinit var runs: AutomationRuns
    private var now = 1_000_000L

    /** Le fragment JSON qui cree la regle eteinte — hors chaine pour la lisibilite. */
    private val ETEINTE = ",\"enabled\":false"

    private fun ApplicationTestBuilder.installTestApp(
        policies: RulePolicies = RulePolicies(
            allowedActionTypes = setOf(
                DeliveryWorker.TYPE_PUSH, DeliveryWorker.TYPE_EMAIL, DeliveryWorker.TYPE_COMMAND
            )
        ),
        withEngine: Boolean = true
    ) {
        runs = AutomationRuns()
        val engine = AutomationEngine(
            sinks = EventSinks(),
            cache = cache,
            values = SignalValueCache(signalRepository),
            actions = ExposedPendingActionRepository(),
            runs = runs,
            devices = deviceRepository,
            // La carte est joignable : sinon `setSignal` abandonnerait, ce qui
            // est le bon comportement mais pas le sujet de ces epreuves.
            isDeviceOnline = { _, _ -> true },
            clock = { now }
        )
        application {
            install(ContentNegotiation) { json() }
            configureAuth(userRepository, com.jeanloickdt.auth.LocalTestAuth.service)
            routing {
                ruleRoutes(cache, resolver, runs, policies, if (withEngine) engine else null)
            }
        }
    }

    private class Account(
        val ownerId: String, val token: String,
        val projectId: String, val deviceId: String
    ) {
        /** `float` a l'adresse 0 — le signal surveille par defaut. */
        fun signal(address: Int = 0) =
            """{"projectId":"$projectId","deviceId":"$deviceId","address":$address}"""
        fun device() = """{"projectId":"$projectId","deviceId":"$deviceId"}"""
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
        signalRepository.create(id, deviceId, 1, "Cmd", "int", nowMs = 0L)
        return Account(id, com.jeanloickdt.auth.LocalTestAuth.token(id, tokenVersion = 0), projectId, deviceId)
    }

    private suspend fun io.ktor.client.HttpClient.createRule(
        account: Account, definition: String, name: String = "regle", extra: String = ""
    ): HttpResponse = post("/api/rules") {
        header(HttpHeaders.Authorization, "Bearer ${account.token}")
        contentType(ContentType.Application.Json)
        val def = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(definition))
        setBody("""{"name":"$name","definition":$def$extra}""")
    }

    /** L'identifiant que le serveur vient d'attribuer. */
    private suspend fun HttpResponse.ruleId(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content

    private suspend fun io.ktor.client.HttpClient.replaceDefinition(
        account: Account, ruleId: String, definition: String, extra: String = ""
    ): HttpResponse = put("/api/rules/$ruleId/definition") {
        header(HttpHeaders.Authorization, "Bearer ${account.token}")
        contentType(ContentType.Application.Json)
        val def = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(definition))
        setBody("""{"definition":$def$extra}""")
    }

    /** « Quand Temp change, SI elle depasse 30, previens-moi. » */
    private fun pushDef(a: Account, above: Double = 30.0) =
        """{"trigger":{"kind":"signalChanged","signal":${a.signal()}},""" +
            """"condition":{"kind":"compare","left":${a.signal()},"op":"gt",""" +
            """"right":{"kind":"literal","type":"float","value":$above}},""" +
            """"actions":[{"kind":"push","title":"t","body":"b"}]}"""

    // ── Le cycle complet ──────────────────────────────────────────────────

    @Test
    fun `creer, lister, eteindre, supprimer — et le portail des producteurs suit`() = testApplication {
        installTestApp()
        val a = account("alice")

        // Zero regle : le portail est ferme, le relais ne publie rien.
        assertFalse(cache.watches(a.ownerId, a.deviceId, 0))

        val created = client.createRule(a, pushDef(a), name = "Serre")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            .getValue("id").jsonPrimitive.content

        // Le portail bascule DANS LE MEME INSTANT que la creation : c'est le
        // point de couplage unique entre le CRUD et le moteur.
        assertTrue(cache.watches(a.ownerId, a.deviceId, 0))

        val listed = Json.parseToJsonElement(
            client.get("/api/rules") {
                header(HttpHeaders.Authorization, "Bearer ${a.token}")
            }.bodyAsText()
        ).jsonArray.map { it.jsonObject }
        assertEquals(1, listed.size)
        assertEquals("Serre", listed[0].getValue("name").jsonPrimitive.content)
        assertEquals("ACTIVE", listed[0].getValue("state").jsonPrimitive.content)

        // L'interrupteur. Sans lui, faire taire une alerte ce soir obligerait
        // a supprimer la regle — ce qui reinitialise `automation_state`, donc
        // fait tirer immediatement une regle recreee alors que le seuil est
        // deja franchi.
        val off = client.patch("/api/rules/$id") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }
        assertEquals(HttpStatusCode.OK, off.status, off.bodyAsText())
        assertEquals(
            "DISABLED",
            Json.parseToJsonElement(off.bodyAsText()).jsonObject.getValue("state").jsonPrimitive.content
        )

        val deleted = client.delete("/api/rules/$id") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
        }
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertFalse(cache.watches(a.ownerId, a.deviceId, 0), "le portail doit se refermer")
    }

    @Test
    fun `supprimer une regle fait taire ce qu elle avait deja enfile`() = testApplication {
        // Sans ca, supprimer une regle ne la fait pas taire : le livreur
        // expedie ensuite ce qu'elle avait deja mis dans l'outbox, et
        // l'utilisateur recoit une alerte d'une regle qu'il vient de
        // supprimer — APRES avoir confirme. C'est le seul cas ou
        // l'application contredit un geste explicite.
        installTestApp()
        val a = account("celine")
        val id = client.createRule(a, pushDef(a)).ruleId()

        val repo = ExposedPendingActionRepository()
        repo.enqueue("k-pending", a.ownerId, id, "PUSH", "{}", occurredAt = now, nowMs = now)
        repo.enqueue("k-sent", a.ownerId, id, "PUSH", "{}", occurredAt = now, nowMs = now)
        val sentId = repo.lease(now, 1000, 10).first { it.idempotencyKey == "k-sent" }.id
        repo.markSent(sentId)

        val res = client.delete("/api/rules/$id") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())

        val restants = transaction {
            exec("SELECT idempotency_key FROM pending_actions ORDER BY idempotency_key") { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }!!
        }
        // Ce qui est PARTI reste : le fil des notifications est un historique,
        // et effacer le passe d'une regle supprimee ferait disparaitre
        // l'alerte qu'on a reellement recue hier.
        assertEquals(listOf("k-sent"), restants)
    }

    @Test
    fun `un lot supprime plusieurs regles et ignore ce qui n est pas a nous`() = testApplication {
        // Une regle inconnue ou appartenant a quelqu'un d'autre est IGNOREE,
        // pas refusee : le lot vient d'un ecran qui peut avoir vieilli d'une
        // seconde, et faire echouer dix-neuf suppressions legitimes parce
        // qu'une vingtieme n'existe plus serait absurde.
        installTestApp()
        val a = account("dora")
        val voisin = account("eve")
        val r1 = client.createRule(a, pushDef(a), name = "un").ruleId()
        val r2 = client.createRule(a, pushDef(a), name = "deux").ruleId()
        val sien = client.createRule(voisin, pushDef(voisin)).ruleId()

        val res = client.delete("/api/rules") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"ids":["$r1","$r2","$sien","inconnue"]}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals(
            2,
            Json.parseToJsonElement(res.bodyAsText()).jsonObject
                .getValue("deleted").jsonPrimitive.content.toInt()
        )

        val restantes = transaction {
            exec("SELECT id FROM automation_rules ORDER BY id") { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }!!
        }
        assertEquals(listOf(sien), restantes, "la regle du voisin ne bouge pas")
    }

    @Test
    fun `un lot vide est refuse`() = testApplication {
        installTestApp()
        val a = account("fabien")
        val res = client.delete("/api/rules") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"ids":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `le PATCH ne touche jamais la definition`() = testApplication {
        installTestApp()
        val a = account("bruno")
        val created = client.createRule(a, pushDef(a))
        val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        val id = body.getValue("id").jsonPrimitive.content
        val before = body.getValue("definition").jsonPrimitive.content

        // C'est la soupape de securite : une app qui ne sait pas decoder une
        // regle doit pouvoir l'eteindre SANS reecrire le JSON, donc sans en
        // effacer les branches qu'elle n'a pas comprises.
        val after = client.patch("/api/rules/$id") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false,"name":"autre","severity":"critical"}""")
        }.bodyAsText()

        val obj = Json.parseToJsonElement(after).jsonObject
        assertEquals(before, obj.getValue("definition").jsonPrimitive.content)
        assertEquals("autre", obj.getValue("name").jsonPrimitive.content)
        assertEquals("critical", obj.getValue("severity").jsonPrimitive.content)
    }

    // ── Ce que le moteur ne doit jamais avoir a charger ───────────────────

    @Test
    fun `une definition invalide est un 400 avec son code, jamais une ligne`() = testApplication {
        installTestApp()
        val a = account("carla")

        val r = client.createRule(a, """{"trigger":{"kind":"signalChanged"}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue("malformed" in r.bodyAsText(), r.bodyAsText())
        assertTrue(Json.parseToJsonElement(
            client.get("/api/rules") { header(HttpHeaders.Authorization, "Bearer ${a.token}") }.bodyAsText()
        ).jsonArray.isEmpty())
    }

    @Test
    fun `un arbre degenere est refuse avec son code`() = testApplication {
        installTestApp()
        val a = account("diane")
        // Un `all` a un seul enfant EST cet enfant : l'accepter laisserait
        // deux ecritures pour une meme regle, donc un aller-retour qui ne
        // boucle pas.
        val def = """{"trigger":{"kind":"signalChanged","signal":${a.signal()}},""" +
            """"condition":{"kind":"all","children":[{"kind":"timeOfDay","from":0,"to":60}]},""" +
            """"actions":[{"kind":"push","title":"t","body":"b"}]}"""
        val r = client.createRule(a, def)
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue("degenerate-tree" in r.bodyAsText(), r.bodyAsText())
    }

    @Test
    fun `un fuseau dans une feuille est refuse`() = testApplication {
        installTestApp()
        val a = account("eric")
        // Deux fuseaux dans une meme regle rendraient legale une regle
        // programmee a Toronto qui teste les heures de Teheran.
        val def = """{"trigger":{"kind":"schedule","minuteOfDay":420,"days":[],"tz":"America/Toronto"},""" +
            """"actions":[{"kind":"push","title":"t","body":"b"}]}"""
        val r = client.createRule(a, def)
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue("tz-in-leaf" in r.bodyAsText(), r.bodyAsText())
    }

    @Test
    fun `un fuseau inconnu sur la regle est refuse`() = testApplication {
        installTestApp()
        val a = account("fatou")
        val r = client.createRule(a, pushDef(a), extra = ""","timeZoneId":"Mars/Olympus"""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue("timezone" in r.bodyAsText().lowercase(), r.bodyAsText())
    }

    @Test
    fun `comparer du texte avec un ordre est refuse`() = testApplication {
        installTestApp()
        val a = account("gaspard")
        signalRepository.create(a.ownerId, a.deviceId, 5, "Mode", "string", nowMs = 0L)
        // « "9" > "10" » est VRAI en lexicographique et faux pour n'importe
        // quel humain — le chiffre faux qui a l'air legitime.
        val def = """{"trigger":{"kind":"signalChanged","signal":${a.signal()}},""" +
            """"condition":{"kind":"compare","left":${a.signal(5)},"op":"gt",""" +
            """"right":{"kind":"literal","type":"string","value":"auto"}},""" +
            """"actions":[{"kind":"push","title":"t","body":"b"}]}"""
        val r = client.createRule(a, def)
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue("type-mismatch" in r.bodyAsText(), r.bodyAsText())
    }

    // ── L'autre locataire ─────────────────────────────────────────────────

    @Test
    fun `une regle ne peut pas viser le signal d un autre compte`() = testApplication {
        installTestApp()
        val mine = account("hugo")
        val theirs = account("ines")

        // 404, jamais 403 : confirmer l'existence serait un bit de
        // l'inventaire de quelqu'un d'autre.
        val r = client.createRule(mine, pushDef(theirs))
        assertEquals(HttpStatusCode.NotFound, r.status, r.bodyAsText())
    }

    @Test
    fun `une commande visant la carte d un autre est refusee a la creation`() = testApplication {
        installTestApp()
        val mine = account("jean")
        val theirs = account("kim")

        val def = """{"trigger":{"kind":"signalChanged","signal":${mine.signal()}},""" +
            """"actions":[{"kind":"setSignal","target":${theirs.signal(1)},""" +
            """"value":{"type":"int","value":1}}]}"""
        val r = client.createRule(mine, def)
        assertEquals(HttpStatusCode.NotFound, r.status, r.bodyAsText())
    }

    @Test
    fun `les regles sont invisibles entre comptes — 404 sur PATCH et DELETE etrangers`() = testApplication {
        installTestApp()
        val mine = account("lea")
        val other = account("marc")

        val id = Json.parseToJsonElement(client.createRule(mine, pushDef(mine)).bodyAsText())
            .jsonObject.getValue("id").jsonPrimitive.content

        assertEquals(HttpStatusCode.NotFound, client.patch("/api/rules/$id") {
            header(HttpHeaders.Authorization, "Bearer ${other.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }.status)

        assertEquals(HttpStatusCode.NotFound, client.delete("/api/rules/$id") {
            header(HttpHeaders.Authorization, "Bearer ${other.token}")
        }.status)
    }

    // ── L'utilisateur contre lui-meme ─────────────────────────────────────

    @Test
    fun `une regle qui ecrit ce qu elle lit est signalee, puis acceptee si on insiste`() = testApplication {
        installTestApp()
        val a = account("nadia")

        // La boucle la plus frequente : une commande qui ecrit le signal meme
        // que la regle surveille — elle se nourrit elle-meme, en passant par
        // le materiel, la ou `chain_depth` ne peut pas suivre.
        val def = """{"trigger":{"kind":"signalChanged","signal":${a.signal(1)}},""" +
            """"actions":[{"kind":"setSignal","target":${a.signal(1)},""" +
            """"value":{"type":"int","value":1}}]}"""

        val warned = client.createRule(a, def)
        assertEquals(HttpStatusCode.Conflict, warned.status, warned.bodyAsText())
        assertTrue("self-triggering" in warned.bodyAsText())

        // On AVERTIT, on ne bloque pas : un asservissement volontaire avec
        // cooldown est un usage legitime.
        val accepted = client.createRule(a, def, extra = ""","acknowledgeLoop":true""")
        assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())
    }

    // ── La frontiere de ce qu'on sait livrer ──────────────────────────────

    @Test
    fun `un type sans expediteur est refuse A LA CREATION, avec le pourquoi`() = testApplication {
        installTestApp(RulePolicies(allowedActionTypes = setOf(DeliveryWorker.TYPE_COMMAND)))
        val a = account("olga")

        // Sans cette garde, la regle serait acceptee (201), declenchee, mise
        // en file — puis marquee DEAD par le livreur, sans que l'utilisateur
        // soit jamais prevenu. Une alerte qui meurt en silence est pire que
        // pas d'alerte du tout.
        val r = client.createRule(a, pushDef(a))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue("not deliverable" in r.bodyAsText(), r.bodyAsText())
    }

    @Test
    fun `le defaut EXCLUT push — un defaut permissif se paie toujours du meme cote`() = testApplication {
        installTestApp(RulePolicies())   // le defaut de production
        val a = account("pablo")
        val r = client.createRule(a, pushDef(a))
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
    }

    @Test
    fun `webhook n a pas de canal et le dit`() = testApplication {
        installTestApp()
        val a = account("quentin")
        // Dans le scelle pour que le format ne bouge plus, pas pour partir
        // aujourd'hui : trois decisions de securite manquent.
        val def = """{"trigger":{"kind":"signalChanged","signal":${a.signal()}},""" +
            """"actions":[{"kind":"webhook","url":"https://x.test","method":"POST"}]}"""
        val r = client.createRule(a, def)
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue("Webhook" in r.bodyAsText(), r.bodyAsText())
    }

    // ── Le quota ──────────────────────────────────────────────────────────

    @Test
    fun `la porte du quota peut refuser, et voit la bonne classification`() = testApplication {
        var sawAutomation: Boolean? = null
        installTestApp(RulePolicies(
            allowedActionTypes = setOf(
                DeliveryWorker.TYPE_PUSH, DeliveryWorker.TYPE_EMAIL, DeliveryWorker.TYPE_COMMAND
            ),
            quotaGate = { call, _, isAutomation, _ ->
                sawAutomation = isAutomation
                call.respond(HttpStatusCode.PaymentRequired, com.jeanloickdt.common.ApiError("quota"))
                false
            }
        ))
        val a = account("rosa")

        // Une regle qui COMMANDE compte comme automatisation ; une regle qui
        // ne fait que notifier compte sur l'autre ligne de la grille.
        val def = """{"trigger":{"kind":"signalChanged","signal":${a.signal()}},""" +
            """"actions":[{"kind":"setSignal","target":${a.signal(1)},""" +
            """"value":{"type":"int","value":1}}]}"""
        val r = client.createRule(a, def)
        assertEquals(HttpStatusCode.PaymentRequired, r.status)
        assertEquals(true, sawAutomation)
    }

    // ── LAST RUNS ─────────────────────────────────────────────────────────

    @Test
    fun `la trace se lit, les plus recents d abord`() = testApplication {
        installTestApp()
        val a = account("theo")
        val id = Json.parseToJsonElement(client.createRule(a, pushDef(a)).bodyAsText())
            .jsonObject.getValue("id").jsonPrimitive.content

        runs.record(a.ownerId, id, 1L, RunOutcome.SKIPPED, "premier")
        runs.record(a.ownerId, id, 2L, RunOutcome.FIRED, "dernier")

        val page = Json.parseToJsonElement(
            client.get("/api/rules/$id/runs") {
                header(HttpHeaders.Authorization, "Bearer ${a.token}")
            }.bodyAsText()
        ).jsonObject
        val list = page.getValue("runs").jsonArray.map { it.jsonObject }

        assertEquals(2, list.size)
        assertEquals("dernier", list[0].getValue("reason").jsonPrimitive.content)
        assertEquals("FIRED", list[0].getValue("outcome").jsonPrimitive.content)
    }

    @Test
    fun `une page courte ne rend pas de curseur`() = testApplication {
        installTestApp()
        val a = account("ursula")
        val id = Json.parseToJsonElement(client.createRule(a, pushDef(a)).bodyAsText())
            .jsonObject.getValue("id").jsonPrimitive.content
        runs.record(a.ownerId, id, 1L, RunOutcome.FIRED)

        // Une page pleine SUGGERE une suite ; une page courte prouve qu'il n'y
        // en a pas. Rendre un curseur ici ferait faire un aller-retour pour
        // apprendre qu'il n'y a rien.
        val page = Json.parseToJsonElement(
            client.get("/api/rules/$id/runs") {
                header(HttpHeaders.Authorization, "Bearer ${a.token}")
            }.bodyAsText()
        ).jsonObject
        assertTrue(page["nextBefore"] == null || page["nextBefore"] is JsonNull)
    }

    @Test
    fun `la trace d un autre compte est un 404`() = testApplication {
        installTestApp()
        val mine = account("victor")
        val other = account("wendy")
        val id = Json.parseToJsonElement(client.createRule(mine, pushDef(mine)).bodyAsText())
            .jsonObject.getValue("id").jsonPrimitive.content

        // Sans cette garde, un identifiant devine rendrait la trace de
        // quelqu'un d'autre.
        assertEquals(HttpStatusCode.NotFound, client.get("/api/rules/$id/runs") {
            header(HttpHeaders.Authorization, "Bearer ${other.token}")
        }.status)
    }

    // ── Run actions now ───────────────────────────────────────────────────

    @Test
    fun `un essai ecrit TESTED et ne consomme aucun compteur de securite`() = testApplication {
        installTestApp()
        val a = account("xavier")
        val id = Json.parseToJsonElement(client.createRule(a, pushDef(a)).bodyAsText())
            .jsonObject.getValue("id").jsonPrimitive.content
        cache.reload()

        val r = client.post("/api/rules/$id/run") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())

        // TESTED, et pas FIRED : sans cette distinction, la ligne « tiree 2
        // fois » deviendrait fausse des qu'on a appuye sur le bouton.
        assertEquals(RunOutcome.TESTED, runs.list(a.ownerId, id).first().outcome)

        // Et surtout : le cooldown n'a pas bouge. Quelqu'un qui verifie sa
        // regle ne doit pas la mettre en sourdine par son propre test.
        val listed = Json.parseToJsonElement(
            client.get("/api/rules") { header(HttpHeaders.Authorization, "Bearer ${a.token}") }.bodyAsText()
        ).jsonArray.first().jsonObject
        assertTrue(
            listed["lastFiredAt"] == null || listed["lastFiredAt"] is JsonNull,
            "un essai ne doit pas marquer lastFiredAt"
        )
    }

    @Test
    fun `un essai est autorise sur une regle eteinte`() = testApplication {
        installTestApp()
        val a = account("yasmine")
        val id = Json.parseToJsonElement(
            client.createRule(a, pushDef(a), extra = ETEINTE).bodyAsText()
        ).jsonObject.getValue("id").jsonPrimitive.content
        cache.reload()

        // C'est meme l'usage principal du bouton : on verifie avant d'armer.
        assertEquals(HttpStatusCode.OK, client.post("/api/rules/$id/run") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
        }.status)
    }

    @Test
    fun `un essai est borne a un toutes les dix secondes`() = testApplication {
        installTestApp()
        val a = account("zoe")
        val id = Json.parseToJsonElement(client.createRule(a, pushDef(a)).bodyAsText())
            .jsonObject.getValue("id").jsonPrimitive.content
        cache.reload()

        assertEquals(HttpStatusCode.OK, client.post("/api/rules/$id/run") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
        }.status)

        // Le bouton saute le cooldown ET le fusible : sans cette borne, il
        // serait le seul chemin du systeme capable de remplir l'outbox sans
        // limite.
        assertEquals(HttpStatusCode.TooManyRequests, client.post("/api/rules/$id/run") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
        }.status)

        now += 11_000
        assertEquals(HttpStatusCode.OK, client.post("/api/rules/$id/run") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
        }.status)
    }

    @Test
    fun `un noeud sans moteur le DIT plutot que de repondre 200`() = testApplication {
        installTestApp(withEngine = false)
        val a = account("adam")
        val id = Json.parseToJsonElement(client.createRule(a, pushDef(a)).bodyAsText())
            .jsonObject.getValue("id").jsonPrimitive.content

        // Repondre 200 sur une action qui n'est jamais partie est le mode de
        // panne que tout ce chantier existe pour eviter.
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/rules/$id/run") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
        }.status)
    }

    // ── Les libelles ──────────────────────────────────────────────────────

    @Test
    fun `les libelles sont retires a l ecriture et reposes a la lecture`() = testApplication {
        installTestApp()
        val a = account("sonia")
        // On envoie un libelle perime exprès : le serveur doit le jeter et
        // reposer le vrai. Un libelle perime en base ment pour toujours.
        val def = """{"trigger":{"kind":"signalChanged","signal":{"projectId":"${a.projectId}",""" +
            """"deviceId":"${a.deviceId}","address":0,"label":"ANCIEN NOM"}},""" +
            """"actions":[{"kind":"push","title":"t","body":"b"}]}"""

        val created = client.createRule(a, def)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val rendered = Json.parseToJsonElement(created.bodyAsText())
            .jsonObject.getValue("definition").jsonPrimitive.content

        assertTrue("ANCIEN NOM" !in rendered, "le libelle perime a survecu : $rendered")
        assertTrue("Temp" in rendered, "le vrai libelle n'a pas ete repose : $rendered")
    }

    // ── Remplacer la definition ───────────────────────────────────────────

    @Test
    fun `remplacer la definition reecrit la regle et deplace l echeance`() = testApplication {
        installTestApp()
        val a = account("rep1")
        val id = client.createRule(a, pushDef(a)).ruleId()

        // La regle surveillait Temp (adresse 0). On la fait surveiller Cmd
        // (adresse 1) : le portail des producteurs doit suivre, sinon le relais
        // continue de publier un signal que plus personne ne regarde et se tait
        // sur celui qu'on vient de choisir.
        val moved = """{"trigger":{"kind":"signalChanged","signal":${a.signal(1)}},""" +
            """"actions":[{"kind":"push","title":"t","body":"b"}]}"""
        val r = client.replaceDefinition(a, id, moved)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())

        assertFalse(cache.watches(a.ownerId, a.deviceId, 0), "l'ancien signal est encore surveille")
        assertTrue(cache.watches(a.ownerId, a.deviceId, 1), "le nouveau signal n'est pas surveille")
    }

    @Test
    fun `remplacer refuse ce qu une creation aurait refuse`() = testApplication {
        installTestApp()
        val a = account("rep2")
        val id = client.createRule(a, pushDef(a)).ruleId()

        // Une regle modifiee ne doit pas pouvoir devenir ce qu'une regle neuve
        // n'aurait pas eu le droit d'etre.
        val noActions = """{"trigger":{"kind":"signalChanged","signal":${a.signal()}},"actions":[]}"""
        assertEquals(HttpStatusCode.BadRequest, client.replaceDefinition(a, id, noActions).status)

        val garbage = """{"trigger":{"kind":"inventeParUnFutur"},"actions":[]}"""
        assertEquals(HttpStatusCode.BadRequest, client.replaceDefinition(a, id, garbage).status)
    }

    @Test
    fun `remplacer avertit d une boucle puis obeit`() = testApplication {
        installTestApp()
        val a = account("rep3")
        val id = client.createRule(a, pushDef(a)).ruleId()

        // Ecrire Cmd en surveillant Cmd : la regle peut se nourrir elle-meme.
        val loop = """{"trigger":{"kind":"signalChanged","signal":${a.signal(1)}},""" +
            """"actions":[{"kind":"setSignal","target":${a.signal(1)},""" +
            """"value":{"type":"int","value":1}}]}"""
        assertEquals(HttpStatusCode.Conflict, client.replaceDefinition(a, id, loop).status)
        assertEquals(
            HttpStatusCode.OK,
            client.replaceDefinition(a, id, loop, extra = ""","acknowledgeLoop":true""").status
        )
    }

    @Test
    fun `remplacer la regle d un autre rend 404`() = testApplication {
        installTestApp()
        val a = account("rep4")
        val b = account("rep5")
        val id = client.createRule(a, pushDef(a)).ruleId()

        // Motif 404 et non 403 : confirmer l'existence de la regle serait deja
        // un bit de l'inventaire de quelqu'un d'autre.
        assertEquals(HttpStatusCode.NotFound, client.replaceDefinition(b, id, pushDef(b)).status)
    }

    @Test
    fun `remplacer refuse une version de schema qui n est plus celle en base`() = testApplication {
        installTestApp()
        val a = account("rep6")
        val id = client.createRule(a, pushDef(a)).ruleId()

        val r = client.replaceDefinition(a, id, pushDef(a), extra = ""","schemaVersion":"v1"""")
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("schema-changed"), r.bodyAsText())

        // La version lue et celle en base concordent : le remplacement passe.
        assertEquals(
            HttpStatusCode.OK,
            client.replaceDefinition(a, id, pushDef(a), extra = ""","schemaVersion":"v2"""").status
        )
    }

}
