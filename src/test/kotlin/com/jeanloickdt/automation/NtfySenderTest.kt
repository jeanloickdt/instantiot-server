package com.jeanloickdt.automation

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La notification de l'auto-heberge.
 *
 * FCM lie la notification au BINAIRE de l'app : elle n'atteint cet APK que si
 * elle part d'un compte de service du projet Firebase avec lequel il a ete
 * compile — celui de l'editeur. Un serveur chez soi n'a donc que de mauvaises
 * portes. ntfy n'a pas ce lien : du HTTP, aucun compte, et l'utilisateur
 * installe l'app ntfy.
 */
class NtfySenderTest {

    private fun action(payload: String, owner: String = "u1") = PendingAction(
        id = 1, idempotencyKey = "k", ownerId = owner, ruleId = "r1",
        type = DeliveryWorker.TYPE_PUSH, payload = payload,
        status = PendingAction.PENDING, attempts = 0, nextAttemptAt = 0, occurredAt = 0
    )

    private val configuree = NtfyConfig(
        server = "https://ntfy.sh",
        topic = "instantiot-9f3a2b7c1d",
        token = ""
    )

    // ─── L'adresse ──────────────────────────────────────────────────

    @Test
    fun `l'adresse se compose du serveur et du sujet`() {
        assertEquals("https://ntfy.sh/instantiot-9f3a2b7c1d", configuree.url)
    }

    @Test
    fun `une barre finale sur l'instance ne fait pas une double barre`() {
        // Un exploitant colle « https://ntfy.chez-moi.fr/ » du navigateur, qui
        // l'affiche ainsi. Une double barre viserait une autre adresse.
        val c = configuree.copy(server = "https://ntfy.chez-moi.fr/")
        assertEquals("https://ntfy.chez-moi.fr/alertes", c.copy(topic = "alertes").url)
    }

    // ─── Ce qui part sur le fil ─────────────────────────────────────

    @Test
    fun `le titre voyage en en-tete, et le corps EST le message`() = runBlocking {
        // C'est le protocole de ntfy. Poster un JSON mettrait le JSON lui-meme
        // dans la notification que l'utilisateur lit.
        var vu: List<String>? = null
        val sender = NtfyActionSender(
            config = { configuree },
            transport = { url, token, title, body -> vu = listOf(url, token, title, body); 200 }
        )

        val r = sender.send(action("""{"title":"Serre","body":"Il fait 33 C"}"""))

        assertTrue(r is SendResult.Ok, "recu : $r")
        assertEquals("https://ntfy.sh/instantiot-9f3a2b7c1d", vu!![0])
        assertEquals("Serre", vu!![2])
        assertEquals("Il fait 33 C", vu!![3])
    }

    @Test
    fun `un jeton vide ne pose aucune autorisation`() = runBlocking {
        var jeton: String? = null
        val sender = NtfyActionSender(
            config = { configuree },
            transport = { _, t, _, _ -> jeton = t; 200 }
        )
        sender.send(action("""{"title":"T","body":"B"}"""))
        assertEquals("", jeton, "ntfy.sh public n'en demande pas")
    }

    // ─── Ce qui manque ──────────────────────────────────────────────

    @Test
    fun `sans sujet, l'echec dit quoi faire`() = runBlocking {
        val sender = NtfyActionSender(config = { configuree.copy(topic = "") })
        val r = sender.send(action("""{"title":"T","body":"B"}"""))

        assertTrue(r is SendResult.Fatal, "recu : $r")
        assertTrue(
            "admin panel" in (r as SendResult.Fatal).reason,
            "un message qui ne dit pas ou aller est un ticket de support — recu : ${r.reason}"
        )
    }

    // ─── Ce qui se retente, et ce qui meurt ─────────────────────────

    @Test
    fun `un jeton refuse est FATAL, jamais retente`() = runBlocking {
        // Reessayer une configuration fausse ne la corrige pas, et occupe le
        // livreur jusqu'a ce que la ligne meure quand meme.
        val sender = NtfyActionSender({ configuree }, { _, _, _, _ -> 401 })
        val r = sender.send(action("""{"title":"T","body":"B"}"""))
        assertTrue(r is SendResult.Fatal && "token" in r.reason, "recu : $r")
    }

    @Test
    fun `un sujet refuse est FATAL aussi`() = runBlocking {
        val sender = NtfyActionSender({ configuree }, { _, _, _, _ -> 404 })
        val r = sender.send(action("""{"title":"T","body":"B"}"""))
        assertTrue(r is SendResult.Fatal && "topic" in r.reason, "recu : $r")
    }

    @Test
    fun `ntfy injoignable se RETENTE — l'alerte reste envoyable`() = runBlocking {
        val sender = NtfyActionSender({ configuree }, { _, _, _, _ -> 503 })
        val r = sender.send(action("""{"title":"T","body":"B"}"""))
        assertTrue(r is SendResult.Retry, "un 5xx est une panne d'en face, pas une alerte perdue — recu : $r")
    }

    @Test
    fun `une panne reseau se RETENTE`() = runBlocking {
        val sender = NtfyActionSender({ configuree }, { _, _, _, _ -> throw java.io.IOException("connexion refusee") })
        val r = sender.send(action("""{"title":"T","body":"B"}"""))
        assertTrue(r is SendResult.Retry, "recu : $r")
    }

    @Test
    fun `une charge illisible meurt proprement`() = runBlocking {
        val sender = NtfyActionSender({ configuree }, { _, _, _, _ -> 200 })
        assertTrue(sender.send(action("pas du json")) is SendResult.Fatal)
    }

    @Test
    fun `sans titre, la notification en porte un quand meme`() = runBlocking {
        // Une notification sans titre s'affiche sous le nom du sujet, qui est
        // une chaine imprevisible : illisible sur un ecran verrouille.
        var titre: String? = null
        val sender = NtfyActionSender({ configuree }, { _, _, t, _ -> titre = t; 200 })
        sender.send(action("""{"body":"seulement un corps"}"""))
        assertEquals("InstantIoT", titre)
    }
}
