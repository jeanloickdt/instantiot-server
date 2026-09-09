package com.jeanloickdt

import com.jeanloickdt.database.TestDatabase
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
import java.net.ServerSocket
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Le serveur demarre-t-il VRAIMENT ?
 *
 * ## Le trou que ceci ferme
 *
 * Aucune epreuve ne montait `module()`. Les autres construisent une
 * application MINCE — « no relay / mDNS / loops » le dit en toutes lettres —
 * et cablent a la main les routes qu'elles eprouvent. C'est le bon choix pour
 * elles : une epreuve de route ne doit pas dependre d'une boucle de vidage.
 *
 * Mais personne n'eprouvait alors la RACINE DE COMPOSITION, et c'est
 * precisement ce que le passage au moteur 2.0 a reecrit. Un cablage qui ne
 * compile pas se voit ; un cablage qui compile et jette au demarrage — une
 * table absente du registre, une route enregistree deux fois, une dependance
 * construite dans le mauvais ordre — ne se voit qu'en lancant le serveur.
 *
 * Ce que ceci ne remplace pas : `main()`, qui choisit les ports, monte
 * l'icone de la barre des taches et peut appeler `System.exit`. Elle n'est
 * pas eprouvable dans un travailleur de test, et ce qu'elle fait de plus est
 * de l'environnement, pas de la composition.
 */
class DemarrageCompletTest {

    private val bases = mutableListOf<File>()

    private fun baseJetable(): File =
        File.createTempFile("instantiot-demarrage-", ".db")
            .apply { delete(); deleteOnExit() }
            .also { bases += it }

    /**
     * Un jeton que le serveur accepte VRAIMENT.
     *
     * Pas celui de `LocalTestAuth` : le module signe avec le secret qu'il
     * garde dans son dossier, et sa validation exige en plus que
     * l'utilisateur EXISTE en base. Un jeton fabrique a cote passe la
     * signature et echoue la validation — ce qui ferait echouer l'epreuve
     * pour une raison qui n'est pas celle qu'elle cherche.
     *
     * On passe donc par la porte : l'admin cree au demarrage se connecte,
     * exactement comme l'app le ferait.
     */
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

    @AfterTest
    fun menage() {
        bases.forEach { it.delete() }
    }

    @Test
    fun `le module se monte en entier sur une base neuve`() = testApplication {
        monterLeVraiModule(baseJetable("demarrage").also { bases += it })

        // La preuve la plus simple qu'il est debout : il repond.
        val status = client.get("/api/status")
        assertEquals(HttpStatusCode.OK, status.status, "le serveur doit repondre apres son montage")
    }

    @Test
    fun `le schema du moteur 2 est cree au demarrage`() = testApplication {
        monterLeVraiModule(baseJetable("demarrage").also { bases += it })
        client.get("/api/status")   // force le montage avant de lire la base

        // Les deux tables que le portage a ajoutees. Si le registre `ALL` les
        // oublie, tout compile, tout demarre, et la premiere attente en vol
        // meurt sur « no such table » — des heures apres le deploiement.
        val tables = transaction {
            exec("SELECT name FROM sqlite_master WHERE type='table'") { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }!!
        }
        assertTrue("automation_runs" in tables, "l'historique des passages doit exister")
        assertTrue("rule_continuations" in tables, "les attentes en vol doivent exister")
    }

    @Test
    fun `les colonnes du moteur 2 sont posees`() = testApplication {
        monterLeVraiModule(baseJetable("demarrage").also { bases += it })
        client.get("/api/status")

        val colonnes = transaction {
            exec("PRAGMA table_info(automation_rules)") { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }!!
        }
        // Le cache refuse une regle dont la version de langage n'est pas la
        // sienne. Sans cette colonne il lirait `null`, refuserait TOUTES les
        // regles, et le serveur aurait l'air de marcher.
        assertTrue("schema_version" in colonnes, "la version du langage doit etre lisible")
        assertTrue("time_zone_id" in colonnes, "le fuseau de la regle doit etre lisible")
        assertTrue("invalid_reason" in colonnes, "le motif d'invalidite doit etre inscriptible")
    }

    @Test
    fun `le fil des alertes est cable, et il est garde`() = testApplication {
        monterLeVraiModule(baseJetable("demarrage").also { bases += it })

        // Sans jeton : 401, et surtout PAS 404. La distinction est tout ce
        // que ce test verifie — un 404 signifierait que la route n'est pas
        // enregistree, et c'est exactement ce que l'app recevait avant.
        val sansJeton = client.get("/api/notifications")
        assertEquals(
            HttpStatusCode.Unauthorized, sansJeton.status,
            "la route doit exister et se defendre, au lieu d'etre absente"
        )
    }

    @Test
    fun `le fil des alertes rend une page vide sur un serveur neuf`() = testApplication {
        monterLeVraiModule(baseJetable("demarrage").also { bases += it })

        val jeton = jetonDeLAdmin()
        val reponse = client.get("/api/notifications") {
            header(HttpHeaders.Authorization, "Bearer $jeton")
        }

        assertEquals(HttpStatusCode.OK, reponse.status)

        // Vide, mais BIEN FORME. L'app deserialise `{fires, next}` — le nom
        // `fires` est le contrat, pas un detail : c'est ce que
        // `NotificationPage` attend cote app, et un champ renomme rendrait
        // une liste vide indistinguable d'une reponse incomprise.
        //
        // Une liste vide et une route absente se ressemblent a l'ecran. Elles
        // ne doivent pas se ressembler sur le fil.
        assertEquals(
            """{"fires":[],"next":null}""", reponse.bodyAsText(),
            "l'enveloppe doit porter sa liste et son curseur, meme vides"
        )
    }

    @Test
    fun `les routes de regles repondent, moteur cable`() = testApplication {
        monterLeVraiModule(baseJetable("demarrage").also { bases += it })

        val jeton = jetonDeLAdmin()
        val liste = client.get("/api/rules") {
            header(HttpHeaders.Authorization, "Bearer $jeton")
        }
        assertEquals(HttpStatusCode.OK, liste.status)
        assertEquals("[]", liste.bodyAsText(), "aucune regle sur un serveur neuf")
    }
}
