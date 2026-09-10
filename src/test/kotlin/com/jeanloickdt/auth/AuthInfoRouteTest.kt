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

    /**
     * DEUX QUESTIONS QUI SE RESSEMBLAIENT, ET QUE ntfy A SEPAREES.
     *
     * Ce serveur sait desormais livrer une action PUSH : ntfy s'en charge.
     * Mais `push` dans cette reponse ne demande pas ca. L'app n'appelle
     * `/api/auth/info` que pour decider d'envoyer son jeton FCM a
     * `/api/push-tokens` — une route que ce serveur n'a pas et ne veut pas,
     * puisque ntfy ne passe pas par l'app mais par l'app ntfy.
     *
     * Repondre `true` ferait envoyer un jeton inutilisable a chaque
     * connexion, et demanderait la permission d'afficher des notifications
     * qui n'arriveraient jamais par ce chemin.
     */
    @Test
    fun `elle dit NON au jeton d'appareil, meme depuis que ntfy livre le push`() = testApplication {
        monterLeVraiModule(baseJetable("authinfo-push"))

        val corps = client.get("/api/auth/info").bodyAsText()
        assertTrue(
            "\"push\":false" in corps,
            "ce serveur ne prend pas de jeton FCM — recu : $corps"
        )
    }

    /**
     * L'annonce ne parle pas de ce qu'on croit.
     *
     * `push` a longtemps voulu dire « je sais notifier », parce que FCM etait
     * le seul canal et qu'il passe par l'app. Avec ntfy, le serveur sait
     * notifier SANS rien vouloir de l'app : les deux faits se separent.
     */
    @Test
    fun `l'annonce parle du jeton, pas de la capacite a notifier`() = testApplication {
        monterLeVraiModule(baseJetable("authinfo-coherence"))

        val annonce = client.get("/api/auth/info").bodyAsText()

        // `push` ici ne parle QUE du jeton d'appareil. La livraison d'une
        // action PUSH, elle, est ouverte par ntfy — et c'est
        // `allowedActionTypes = actionSenders.keys` qui en decide.
        //
        // Les deux ne disent donc plus la meme chose, et c'est voulu : ce sont
        // deux questions differentes que FCM confondait tant qu'il etait le
        // seul canal.
        assertTrue("\"push\":false" in annonce, "aucun jeton d'appareil ici")
    }
}
