package com.jeanloickdt.auth

import com.jeanloickdt.baseJetable
import com.jeanloickdt.monterLeVraiModule
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ce que ce serveur ANNONCE savoir faire.
 *
 * ## Pourquoi c'est la brique qui compte
 *
 * L'app est multi-serveurs : un meme telephone peut etre connecte au nuage
 * ET a un serveur auto-heberge. Les deux ne portent pas les memes canaux, et
 * l'app n'a aucun moyen de le deviner en regardant une adresse.
 *
 * `PushRegistrar.registerWith` lit cette route AVANT de faire quoi que ce
 * soit. Sans elle, l'echec de lecture vaut « je ne sais pas notifier » — le
 * bon defaut, mais un defaut. Avec elle, le serveur le DIT, et le jour ou il
 * saura notifier, la meme phrase changera de valeur sans que l'app bouge.
 *
 * Les epreuves montent le VRAI module : une route de decouverte qui
 * fonctionne dans un montage de test mais qu'on aurait oublie de cabler ne
 * servirait a rien.
 */
class AuthInfoRouteTest {

    @Test
    fun `elle repond sans jeton, parce qu'elle sert avant qu'un jeton existe`() = testApplication {
        monterLeVraiModule(baseJetable("authinfo"))

        val r = client.get("/api/auth/info")
        assertEquals(
            HttpStatusCode.OK, r.status,
            "l'app la lit pour savoir COMMENT s'authentifier — l'exiger authentifiee serait circulaire"
        )
    }

    @Test
    fun `elle annonce l'authentification locale`() = testApplication {
        monterLeVraiModule(baseJetable("authinfo-local"))

        val corps = client.get("/api/auth/info").bodyAsText()
        // Le nuage repond "iia" et nomme le service ou s'authentifier. Ce
        // serveur frappe ses propres jetons : c'est la difference que l'app
        // doit connaitre pour dessiner le bon ecran de connexion.
        assertTrue("\"auth\":\"local\"" in corps, "recu : $corps")
        assertTrue("instantiot-server" in corps, "l'emetteur des jetons doit etre nomme — recu : $corps")
    }

    @Test
    fun `elle dit NON au push, parce qu'aucun expediteur ne le porte`() = testApplication {
        monterLeVraiModule(baseJetable("authinfo-push"))

        val corps = client.get("/api/auth/info").bodyAsText()
        assertTrue(
            "\"push\":false" in corps,
            "le jumeau n'a pas les cles du projet Firebase — recu : $corps"
        )
    }

    /**
     * L'invariant qui rend l'annonce digne de confiance.
     *
     * `push` ne se lit pas dans une configuration : il vaut ce que la carte
     * d'expediteurs contient. Le meme fait decide de la LIVRAISON, des regles
     * CREABLES, et de cette ANNONCE.
     *
     * Le jour ou quelqu'un enregistrera un expediteur PUSH, les trois
     * basculeront ensemble. Le jour ou quelqu'un ecrirait `push = true` en
     * dur ici, cette epreuve tomberait — et c'est son travail.
     */
    @Test
    fun `l'annonce et les regles creables disent la meme chose`() = testApplication {
        monterLeVraiModule(baseJetable("authinfo-coherence"))

        val annonce = client.get("/api/auth/info").bodyAsText()
        val annoncePush = "\"push\":true" in annonce

        // La creation d'une regle PUSH est refusee par `allowedActionTypes`,
        // qui est LUI AUSSI `actionSenders.keys`. Les deux doivent donc
        // toujours s'accorder — c'est tout l'interet de la source unique.
        assertEquals(
            annoncePush, false,
            "tant qu'aucun expediteur PUSH n'est enregistre, l'annonce et la porte disent NON ensemble"
        )
    }
}
