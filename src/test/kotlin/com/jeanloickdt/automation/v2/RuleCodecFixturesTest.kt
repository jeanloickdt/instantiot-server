package com.jeanloickdt.automation.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Les fixtures canoniques, en aller-retour.
 *
 * C'est ce qui empêche l'app et le serveur de diverger dans six mois : les
 * MÊMES fichiers sont lus par les deux codecs. Un champ renommé d'un côté
 * casse ici avant d'être déployé.
 *
 * Trois épreuves par fixture de `ok/` :
 *
 * 1. elle se décode ;
 * 2. la ré-encoder rend la MÊME structure JSON — c'est la forme canonique ;
 * 3. décoder ce ré-encodage rend le même objet — l'aller-retour boucle.
 *
 * La comparaison porte sur l'arbre JSON, pas sur les octets bruts :
 * l'indentation d'un fichier écrit à la main n'a jamais dit quoi que ce soit
 * sur le contrat, et exiger l'égalité octet à octet aurait rendu les fixtures
 * illisibles pour préserver une propriété qui n'intéresse personne.
 */
class RuleCodecFixturesTest {

    private val root = File("openapi/fixtures/automation-rules-v2")

    private fun okFiles() = File(root, "ok").listFiles()!!.filter { it.extension == "json" }.sorted()
    private fun koFiles() = File(root, "ko").listFiles()!!.filter { it.extension == "json" }.sorted()

    @Test
    fun `les fixtures existent`() {
        // Une suite qui passe sur zero fixture est une suite qui ne prouve
        // rien — et c'est exactement ce qui arriverait si le dossier bougeait.
        assertTrue(okFiles().size >= 15, "attendu au moins 15 fixtures ok, vu ${okFiles().size}")
        assertTrue(koFiles().size >= 9, "attendu au moins 9 fixtures ko, vu ${koFiles().size}")
    }

    @Test
    fun `chaque fixture ok se decode et boucle en aller-retour`() {
        for (file in okFiles()) {
            val raw = file.readText()

            val first = RuleCodec.decode(raw)
            if (first !is RuleCodec.Outcome.Ok) {
                fail("${file.name} refusee : ${(first as RuleCodec.Outcome.Invalid).code} — ${first.detail}")
            }

            // Forme canonique : re-encoder doit rendre le meme arbre.
            val reencoded = RuleCodec.encodeToJson(first.logic)
            assertEquals(
                Json.parseToJsonElement(raw).jsonObject,
                reencoded,
                "${file.name} n'est pas sous forme canonique"
            )

            // Et l'aller-retour boucle sur l'objet, pas seulement sur le texte.
            val second = RuleCodec.decode(RuleCodec.encode(first.logic))
            assertTrue(second is RuleCodec.Outcome.Ok, "${file.name} ne se redecode pas")
            assertEquals(first.logic, second.logic, "${file.name} ne boucle pas")
        }
    }

    @Test
    fun `chaque fixture ko est refusee avec le code attendu`() {
        for (file in koFiles()) {
            val raw = file.readText()
            val expected = Json.parseToJsonElement(raw).jsonObject["expectedError"]!!.jsonPrimitive.content

            val outcome = RuleCodec.decode(raw)

            // Les trois `type-mismatch` ne sont PAS l'affaire du codec : leur
            // JSON est bien forme, et seul l'inventaire des signaux peut dire
            // qu'on compare un texte avec « > ». Ils sont eprouves contre un
            // resolveur dans RuleValidationTest — ici on verifie seulement
            // qu'ils passent le codec, sinon la frontiere entre les deux
            // validations aurait glisse sans que rien ne le dise.
            if (expected == RuleValidation.E_TYPE_MISMATCH) {
                assertTrue(
                    outcome is RuleCodec.Outcome.Ok,
                    "${file.name} doit passer le codec — c'est la validation de type qui la refuse"
                )
                continue
            }

            if (outcome !is RuleCodec.Outcome.Invalid) {
                fail("${file.name} aurait du etre refusee avec '$expected'")
            }
            assertEquals(expected, outcome.code, "${file.name} : mauvais code — ${outcome.detail}")
        }
    }

    @Test
    fun `la profondeur limite passe et la suivante casse`() {
        // La fixture 13 est a exactement huit niveaux, la 51 a neuf. Les deux
        // encadrent la borne, et c'est la seule facon de prouver qu'elle est
        // ou on croit qu'elle est.
        val huit = File(root, "ok/13-profondeur-8-limite-acceptee.json").readText()
        val neuf = File(root, "ko/51-profondeur-9.json").readText()

        assertTrue(RuleCodec.decode(huit) is RuleCodec.Outcome.Ok, "8 niveaux doivent passer")
        val refus = RuleCodec.decode(neuf)
        assertTrue(refus is RuleCodec.Outcome.Invalid)
        assertEquals(RuleCodec.E_DEPTH, refus.code)
    }

    @Test
    fun `un arbre absurdement profond ne fait pas sauter la pile`() {
        // Le vrai risque : la definition est du JSON fourni par le client. Dix
        // mille niveaux doivent rendre un 400, pas un StackOverflowError qui
        // emporte le thread de la requete.
        val sb = StringBuilder("""{"trigger":{"kind":"signalChanged","signal":{"projectId":"p","deviceId":"d","address":0}},"condition":""")
        val n = 10_000
        repeat(n) { sb.append("""{"kind":"not","child":""") }
        sb.append("""{"kind":"timeOfDay","from":0,"to":60}""")
        repeat(n) { sb.append("}") }
        sb.append(""","actions":[{"kind":"push","title":"x","body":"y"}]}""")

        val outcome = RuleCodec.decode(sb.toString())
        assertTrue(outcome is RuleCodec.Outcome.Invalid, "dix mille niveaux doivent etre refuses")
        // Le code exact importe peu — `depth-exceeded` si notre compteur gagne,
        // `malformed` si l'analyseur JSON abandonne le premier, `too-large`
        // depuis que la definition a une borne de taille (dix mille niveaux
        // font bien plus de seize kilo-octets). Ce qui compte est qu'AUCUNE
        // des voies ne laisse passer, et qu'on rende un refus plutot qu'une
        // pile explosee.
        assertTrue(
            (outcome as RuleCodec.Outcome.Invalid).code in
                setOf(RuleCodec.E_DEPTH, RuleCodec.E_MALFORMED, RuleCodec.E_TOO_LARGE),
            "code inattendu : ${outcome.code}"
        )
    }

    @Test
    fun `les libelles ne survivent pas a l ecriture`() {
        val avec = RuleLogic(
            trigger = Trigger.SignalChanged(SignalRef("p", "d", 0, cachedLabel = "Temperature")),
            condition = Condition.All(listOf(
                Condition.Compare(
                    SignalRef("p", "d", 0, cachedLabel = "Temperature"),
                    Op.GT,
                    Operand.Signal(SignalRef("p", "d", 7, cachedLabel = "Consigne"))
                ),
                Condition.DeviceState(DeviceRef("p", "d2", cachedLabel = "Ventilo"), Condition.Presence.ONLINE)
            )),
            actions = listOf(Action.SetSignal(SignalRef("p", "d2", 0, cachedLabel = "Cmd"), TypedValue.Int(1)))
        )

        val json = RuleCodec.encode(RuleCodec.stripLabels(avec))

        // Un libelle perime en base est un libelle qui ment pour toujours :
        // un projet renomme laisserait des phrases fausses partout.
        assertTrue("label" !in json, "un cachedLabel a survecu : $json")
        assertTrue("Temperature" !in json && "Ventilo" !in json && "Consigne" !in json)
    }
}
