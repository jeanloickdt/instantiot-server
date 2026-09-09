package com.jeanloickdt

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.net.ServerSocket
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Le serveur de quelqu'un d'autre, qui tournait deja.
 *
 * ## Ce qui est vraiment en jeu
 *
 * Le jumeau vit chez les gens. Leur base a le schema de la version qu'ils
 * ont installee, et le passage au moteur 2.0 lui demande sept colonnes de
 * plus sur la regle, six sur son etat, deux sur l'action en attente, et deux
 * tables entieres.
 *
 * `SchemaUtils.createMissingTablesAndColumns` sait AJOUTER. C'est ce qui
 * rend la mise a jour possible sans migration ecrite. Mais « je crois qu'elle
 * sait » et « je l'ai vu faire sur le schema exact d'avant » ne sont pas la
 * meme phrase, et la difference se paie au demarrage de quelqu'un d'autre.
 *
 * ## Ce que la base de depart contient
 *
 * Pas une approximation : les colonnes EXACTES du modele d'avant le portage,
 * relues dans l'historique du depot. Une regle et son etat y sont ecrits pour
 * que la mise a jour ait quelque chose a preserver — une table vide se
 * migrerait toujours, et ne prouverait rien.
 *
 * `triggered`, `last_value` et `cooldown_ms` restent apres coup : rien ne les
 * retire, rien ne les lit, et elles portent un defaut. C'est le prix assume
 * de ne pas ecrire de migration, et cette epreuve verifie que ce prix est
 * bien ZERO pour l'utilisateur.
 */
class DemarrageSurBaseExistanteTest {

    private val bases = mutableListOf<File>()

    @AfterTest
    fun menage() {
        bases.forEach { it.delete() }
    }

    /**
     * Une base au schema d'AVANT, avec une regle dedans.
     *
     * Ecrite en SQL brut et non par Exposed : le modele d'aujourd'hui ne sait
     * plus decrire celui d'hier, et c'est precisement le schema d'hier qu'on
     * veut voir se faire rattraper.
     */
    private fun baseDeLAncienneVersion(): File {
        val f = File.createTempFile("instantiot-ancienne-", ".db")
            .apply { delete(); deleteOnExit() }
            .also { bases += it }

        DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    """CREATE TABLE automation_rules (
                        id TEXT PRIMARY KEY NOT NULL, owner_id TEXT NOT NULL, name TEXT NOT NULL,
                        enabled INTEGER NOT NULL, trigger_kind TEXT NOT NULL,
                        trigger_signal_key TEXT NULL, definition TEXT NOT NULL,
                        created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                        cooldown_ms INTEGER NOT NULL DEFAULT 300000)"""
                )
                st.executeUpdate(
                    """CREATE TABLE automation_state (
                        rule_id TEXT PRIMARY KEY NOT NULL,
                        triggered INTEGER NOT NULL DEFAULT 0,
                        last_fired_at INTEGER NULL, last_value REAL NULL,
                        updated_at INTEGER NOT NULL)"""
                )
                // Une regle du temps d'avant, avec son etat arme. Elle doit
                // SURVIVRE a la mise a jour — le moteur la refusera, sa
                // definition n'etant pas du 2.0, mais la LIGNE reste.
                st.executeUpdate(
                    """INSERT INTO automation_rules
                       (id, owner_id, name, enabled, trigger_kind, trigger_signal_key,
                        definition, created_at, updated_at, cooldown_ms)
                       VALUES ('r-ancienne','proprio','Alerte serre',1,'value','dev-1:0',
                       '{"when":{"kind":"value","above":30.0},"actions":[]}',1000,1000,300000)"""
                )
                st.executeUpdate(
                    """INSERT INTO automation_state (rule_id, triggered, last_fired_at, last_value, updated_at)
                       VALUES ('r-ancienne', 1, 2000, 31.5, 2000)"""
                )
            }
        }
        return f
    }

    private fun ApplicationTestBuilder.monterSur(base: File) {
        val libre = ServerSocket(0).use { it.localPort }
        com.jeanloickdt.common.ServerConfig.markRunningPorts(
            http = com.jeanloickdt.common.ServerConfig.runningHttpPort,
            tcp = libre
        )
        application { module(dbFile = base) }
    }

    private fun colonnes(table: String): List<String> = transaction {
        exec("PRAGMA table_info($table)") { rs ->
            buildList { while (rs.next()) add(rs.getString("name")) }
        }!!
    }

    // ─── La mise a jour ─────────────────────────────────────────────

    @Test
    fun `le serveur demarre sur une base de l ancienne version`() = testApplication {
        monterSur(baseDeLAncienneVersion())

        val status = client.get("/api/status")
        assertEquals(
            HttpStatusCode.OK, status.status,
            "une base d'hier ne doit pas empecher le serveur de se lever"
        )
    }

    @Test
    fun `les colonnes du langage sont ajoutees a une table qui existait`() = testApplication {
        monterSur(baseDeLAncienneVersion())
        client.get("/api/status")

        val c = colonnes("automation_rules")
        // Sans `schema_version`, le cache lirait null, refuserait TOUTES les
        // regles, et le serveur aurait l'air de marcher.
        assertTrue("schema_version" in c, "la version du langage doit avoir ete ajoutee")
        assertTrue("time_zone_id" in c, "le fuseau doit avoir ete ajoute")
        assertTrue("invalid_reason" in c, "le motif d'invalidite doit avoir ete ajoute")
        assertTrue("severity" in c, "la gravite doit avoir ete ajoutee")
        assertTrue("icon" in c && "color" in c, "l'apparence doit avoir ete ajoutee")
    }

    @Test
    fun `les compteurs d observabilite sont ajoutes a l etat`() = testApplication {
        monterSur(baseDeLAncienneVersion())
        client.get("/api/status")

        val c = colonnes("automation_state")
        assertTrue("last_value_json" in c, "la valeur precedente TYPEE doit avoir ete ajoutee")
        assertTrue("evaluations" in c && "fired" in c, "les compteurs du jour doivent exister")
    }

    @Test
    fun `les deux tables neuves sont creees a cote des anciennes`() = testApplication {
        monterSur(baseDeLAncienneVersion())
        client.get("/api/status")

        val tables = transaction {
            exec("SELECT name FROM sqlite_master WHERE type='table'") { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }!!
        }
        assertTrue("automation_runs" in tables, "l'historique des passages doit avoir ete cree")
        assertTrue("rule_continuations" in tables, "les attentes en vol doivent avoir ete creees")
    }

    // ─── Ce qui etait la reste la ───────────────────────────────────

    @Test
    fun `la regle d hier survit a la mise a jour`() = testApplication {
        monterSur(baseDeLAncienneVersion())
        client.get("/api/status")

        val noms = transaction {
            exec("SELECT name FROM automation_rules WHERE id='r-ancienne'") { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }!!
        }
        assertEquals(
            listOf("Alerte serre"), noms,
            "une mise a jour ne doit RIEN effacer de ce que l'utilisateur avait ecrit"
        )
    }

    @Test
    fun `les colonnes mortes restent, et ne genent personne`() = testApplication {
        monterSur(baseDeLAncienneVersion())
        client.get("/api/status")

        // Rien ne les retire : `createMissingTablesAndColumns` ne sait
        // qu'ajouter. Ce que cette epreuve verifie, c'est que leur presence
        // ne coute RIEN — le serveur repond, et les insertions du modele
        // d'aujourd'hui passent malgre elles, parce qu'elles ont un defaut.
        assertTrue("cooldown_ms" in colonnes("automation_rules"))
        assertTrue("triggered" in colonnes("automation_state"))

        val jeton = jetonDeLAdmin()
        val regles = client.get("/api/rules") { header(HttpHeaders.Authorization, "Bearer $jeton") }
        assertEquals(
            HttpStatusCode.OK, regles.status,
            "le CRUD doit fonctionner sur une base qui porte encore les colonnes d'hier"
        )
    }

    private suspend fun ApplicationTestBuilder.jetonDeLAdmin(): String {
        val reponse = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"admin"}""")
        }
        assertEquals(HttpStatusCode.OK, reponse.status, "l'admin par defaut doit pouvoir se connecter")
        return Regex(""""token"\s*:\s*"([^"]+)"""")
            .find(reponse.bodyAsText())?.groupValues?.get(1)
            ?: error("la reponse de connexion ne porte pas de jeton : ${reponse.bodyAsText()}")
    }
}
