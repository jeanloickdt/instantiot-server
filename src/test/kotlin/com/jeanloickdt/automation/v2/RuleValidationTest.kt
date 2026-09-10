package com.jeanloickdt.automation.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * La politique de type du §2, et les motifs d'invalidité du §5.
 *
 * Le resolveur est fabriqué : ces règles-là ne dépendent pas d'une base, elles
 * dépendent de ce que l'inventaire DIT, et c'est exactement ce qu'on injecte.
 */
class RuleValidationTest {

    private val TEMP = SignalRef("p", "d", 0)       // float
    private val COMPTEUR = SignalRef("p", "d", 1)   // int
    private val MODE = SignalRef("p", "d", 2)       // string
    private val CONSIGNE = SignalRef("p", "d", 3)   // int
    private val CACHE = SignalRef("p", "d", 4)      // float, hors automatisation
    private val VENTILO = DeviceRef("p", "d2")

    private class Fake(
        val types: Map<String, SignalType> = emptyMap(),
        val hidden: Set<String> = emptySet(),
        val devices: Map<String, String> = mapOf("d" to "Capteur", "d2" to "Ventilo")
    ) : RuleValidation.Resolver {
        override fun signal(ownerId: String, ref: SignalRef): RuleValidation.ResolvedSignal? =
            types[ref.signalKey]?.let {
                RuleValidation.ResolvedSignal(it, ref.signalKey !in hidden, "sig-${ref.address}")
            }
        override fun deviceLabel(ownerId: String, ref: DeviceRef): String? = devices[ref.deviceId]
    }

    private val inventaire = Fake(types = mapOf(
        "d:0" to SignalType.FLOAT,
        "d:1" to SignalType.INT,
        "d:2" to SignalType.TEXT,
        "d:3" to SignalType.INT,
        "d:4" to SignalType.FLOAT
    ))

    private fun rule(condition: Condition? = null, actions: List<Action> = listOf(Action.Push("t", "b"))) =
        RuleLogic(Trigger.SignalChanged(TEMP), condition, actions)

    private fun check(logic: RuleLogic, resolver: RuleValidation.Resolver = inventaire) =
        RuleValidation.check(logic, "owner", resolver)

    // ── Ce qui passe ─────────────────────────────────────────────────────

    @Test
    fun `numerique contre numerique accepte les six operateurs`() {
        for (op in Op.entries) {
            assertNull(
                check(rule(Condition.Compare(TEMP, op, Operand.Literal(TypedValue.Float(30.0))))),
                "'${op.wire}' devrait passer sur du numerique"
            )
        }
    }

    @Test
    fun `un int se compare a un float sans perte`() {
        // La promotion est exacte : ce n'est PAS la coercition que le §4
        // interdit, qui fabriquerait une valeur inexistante.
        assertNull(check(rule(
            Condition.Compare(COMPTEUR, Op.GTE, Operand.Literal(TypedValue.Float(1000.5)))
        )))
        assertNull(check(rule(
            Condition.Compare(TEMP, Op.GT, Operand.Signal(CONSIGNE))
        )))
    }

    @Test
    fun `du texte accepte l egalite`() {
        assertNull(check(rule(Condition.Compare(MODE, Op.EQ, Operand.Literal(TypedValue.Text("auto"))))))
        assertNull(check(rule(Condition.Compare(MODE, Op.NEQ, Operand.Literal(TypedValue.Text("auto"))))))
    }

    // ── Ce qui casse ─────────────────────────────────────────────────────

    @Test
    fun `l ordre sur du texte est refuse`() {
        // « "9" > "10" » est VRAI en lexicographique et faux pour n'importe
        // quel humain — le chiffre faux qui a l'air legitime.
        for (op in Op.entries.filter { it.isOrdering }) {
            val v = check(rule(Condition.Compare(MODE, op, Operand.Literal(TypedValue.Text("auto")))))
            assertNotNull(v, "'${op.wire}' sur du texte devrait etre refuse")
            assertEquals(RuleValidation.E_TYPE_MISMATCH, v.code)
        }
    }

    @Test
    fun `numerique contre texte est refuse dans les deux sens`() {
        val a = check(rule(Condition.Compare(TEMP, Op.EQ, Operand.Literal(TypedValue.Text("chaud")))))
        assertEquals(RuleValidation.E_TYPE_MISMATCH, a?.code)
        val b = check(rule(Condition.Compare(MODE, Op.EQ, Operand.Literal(TypedValue.Float(1.0)))))
        assertEquals(RuleValidation.E_TYPE_MISMATCH, b?.code)
        val c = check(rule(Condition.Compare(MODE, Op.EQ, Operand.Signal(TEMP))))
        assertEquals(RuleValidation.E_TYPE_MISMATCH, c?.code)
    }

    @Test
    fun `ecrire un float dans un int est refuse`() {
        // 21.7 deviendrait 21 ou 22 sans que personne ne l'ait demande, et une
        // consigne qui part differente de celle affichee est indefendable.
        val v = check(rule(actions = listOf(Action.SetSignal(CONSIGNE, TypedValue.Float(21.7)))))
        assertEquals(RuleValidation.E_TYPE_MISMATCH, v?.code)
    }

    @Test
    fun `ecrire un int dans un float passe`() {
        assertNull(check(rule(actions = listOf(Action.SetSignal(TEMP, TypedValue.Int(21))))))
    }

    @Test
    fun `ecrire du texte dans un signal numerique est refuse`() {
        val v = check(rule(actions = listOf(Action.SetSignal(TEMP, TypedValue.Text("chaud")))))
        assertEquals(RuleValidation.E_TYPE_MISMATCH, v?.code)
    }

    @Test
    fun `la profondeur d un arbre ne cache pas un type errone`() {
        // Un mismatch enterre sous six niveaux doit remonter : la validation
        // descend l'arbre entier, elle ne s'arrete pas a la racine.
        var c: Condition = Condition.Compare(MODE, Op.GT, Operand.Literal(TypedValue.Text("x")))
        repeat(6) { c = Condition.All(listOf(c, Condition.TimeOfDay(0, 720))) }
        assertEquals(RuleValidation.E_TYPE_MISMATCH, check(rule(c))?.code)
    }

    // ── Les references qui ne resolvent plus ─────────────────────────────

    @Test
    fun `un signal supprime invalide la regle`() {
        val v = check(rule(), Fake(types = emptyMap()))
        assertEquals(RuleValidation.E_SIGNAL_DELETED, v?.code)
    }

    @Test
    fun `une carte supprimee invalide la regle`() {
        val logic = RuleLogic(
            Trigger.DeviceConnected(VENTILO), null, listOf(Action.Push("t", "b"))
        )
        val v = RuleValidation.check(logic, "owner", Fake(devices = emptyMap()))
        assertEquals(RuleValidation.E_DEVICE_DELETED, v?.code)
    }

    @Test
    fun `un signal retire de l automatisation invalide la regle`() {
        // La bascule est AUTORISEE : refuser creerait un couplage odieux —
        // impossible de retirer un signal sans demonter d'abord ses regles.
        val hidden = Fake(types = inventaire.types, hidden = setOf("d:4"))
        val logic = RuleLogic(Trigger.SignalChanged(CACHE), null, listOf(Action.Push("t", "b")))
        val v = RuleValidation.check(logic, "owner", hidden)
        assertEquals(RuleValidation.E_SIGNAL_OFF, v?.code)
    }

    @Test
    fun `la reparation est automatique quand la bascule repasse`() {
        // Sans ce chemin de retour, une bascule accidentelle casserait les
        // regles definitivement et personne ne comprendrait pourquoi.
        val logic = RuleLogic(Trigger.SignalChanged(CACHE), null, listOf(Action.Push("t", "b")))
        assertEquals(
            RuleValidation.E_SIGNAL_OFF,
            RuleValidation.check(logic, "owner", Fake(inventaire.types, hidden = setOf("d:4")))?.code
        )
        assertNull(RuleValidation.check(logic, "owner", Fake(inventaire.types)))
    }

    @Test
    fun `un declencheur de transition subit la meme politique de type`() {
        val bon = RuleLogic(
            Trigger.SignalTransition(COMPTEUR, TypedValue.Int(1), from = TypedValue.Int(0)),
            null, listOf(Action.Push("t", "b"))
        )
        assertNull(check(bon))

        val mauvais = RuleLogic(
            Trigger.SignalTransition(COMPTEUR, TypedValue.Text("on")),
            null, listOf(Action.Push("t", "b"))
        )
        assertEquals(RuleValidation.E_TYPE_MISMATCH, check(mauvais)?.code)
    }

    @Test
    fun `un seuil sur du texte est refuse au declencheur comme en condition`() {
        // « depasse » sur du texte comparerait des lettres : « "9" > "10" »
        // est VRAI en lexicographique. Le declencheur ne doit pas etre la
        // porte de derriere par laquelle la politique se contourne.
        val mauvais = RuleLogic(
            Trigger.SignalTransition(MODE, TypedValue.Text("on"), op = Op.GT),
            null, listOf(Action.Push("t", "b"))
        )
        assertEquals(RuleValidation.E_TYPE_MISMATCH, check(mauvais)?.code)

        // L'egalite, elle, reste permise sur du texte.
        val bon = RuleLogic(
            Trigger.SignalTransition(MODE, TypedValue.Text("on")),
            null, listOf(Action.Push("t", "b"))
        )
        assertNull(check(bon))
    }

    @Test
    fun `une attente en derniere position est refusee`() {
        // Attendre puis ne rien faire ne fait rien : ca coute une ligne en base
        // et une echeance pour reveiller une sequence vide. Ce n'est jamais ce
        // que quelqu'un voulait ecrire, et il doit le decouvrir a l'ecriture
        // plutot qu'en constatant que rien ne se passe.
        val mauvais = RuleLogic(
            Trigger.SignalChanged(COMPTEUR),
            null,
            listOf(Action.Push("t", "b"), Action.Wait(Action.Delay.For(30)))
        )
        assertEquals(RuleValidation.E_TRAILING_WAIT, check(mauvais)?.code)

        // Suivie de quelque chose, elle passe.
        val bon = RuleLogic(
            Trigger.SignalChanged(COMPTEUR),
            null,
            listOf(Action.Push("t", "b"), Action.Wait(Action.Delay.For(30)), Action.Push("t2", "b"))
        )
        assertNull(check(bon))
    }

    @Test
    fun `un seuil sur du numerique passe`() {
        for (op in listOf(Op.GT, Op.GTE, Op.LT, Op.LTE, Op.NEQ)) {
            val logic = RuleLogic(
                Trigger.SignalTransition(COMPTEUR, TypedValue.Int(30), op = op),
                null, listOf(Action.Push("t", "b"))
            )
            assertNull(check(logic), "${op.wire} refuse sur du numerique")
        }
    }

    // ── Les fixtures ko de type ──────────────────────────────────────────

    @Test
    fun `les fixtures ko de type sont refusees par la validation`() {
        // Elles passent le codec — leur JSON est bien forme — et c'est ici
        // qu'elles doivent tomber. Sinon la frontiere entre les deux
        // validations aurait glisse sans que rien ne le dise.
        val dir = File("openapi/fixtures/automation-rules-v2/ko")
        val resolver = Fake(types = mapOf(
            "dev-capteur:0" to SignalType.FLOAT,
            "dev-ventilo:0" to SignalType.INT,
            "dev-panneau:9" to SignalType.TEXT
        ), devices = mapOf("dev-capteur" to "Capteur", "dev-ventilo" to "Ventilo", "dev-panneau" to "Panneau"))

        val concernees = dir.listFiles()!!.filter {
            it.extension == "json" &&
                Json.parseToJsonElement(it.readText()).jsonObject["expectedError"]
                    ?.jsonPrimitive?.content == RuleValidation.E_TYPE_MISMATCH
        }
        assertTrue(concernees.size >= 3, "attendu au moins 3 fixtures type-mismatch")

        for (file in concernees) {
            val decoded = RuleCodec.decode(file.readText())
            if (decoded !is RuleCodec.Outcome.Ok) fail("${file.name} devrait passer le codec")
            val v = RuleValidation.check(decoded.logic, "owner", resolver)
            assertEquals(RuleValidation.E_TYPE_MISMATCH, v?.code, "${file.name} : ${v?.detail}")
        }
    }

    // ── Les libelles ─────────────────────────────────────────────────────

    @Test
    fun `les libelles sont reposes a la lecture`() {
        val nu = RuleLogic(
            Trigger.SignalChanged(TEMP),
            Condition.DeviceState(VENTILO, Condition.Presence.ONLINE),
            listOf(Action.SetSignal(CONSIGNE, TypedValue.Int(1)))
        )
        val plein = RuleValidation.rehydrate(nu, "owner", inventaire)

        assertEquals("sig-0", (plein.trigger as Trigger.SignalChanged).signal.cachedLabel)
        assertEquals("Ventilo", (plein.condition as Condition.DeviceState).device.cachedLabel)
        assertEquals("sig-3", (plein.actions[0] as Action.SetSignal).target.cachedLabel)
    }

    @Test
    fun `une reference disparue garde un libelle nul plutot qu un nom invente`() {
        // Inventer un nom masquerait precisement ce que l'utilisateur doit
        // voir : la regle porte deja `signal-deleted`.
        val nu = RuleLogic(Trigger.SignalChanged(TEMP), null, listOf(Action.Push("t", "b")))
        val plein = RuleValidation.rehydrate(nu, "owner", Fake(types = emptyMap()))
        assertNull((plein.trigger as Trigger.SignalChanged).signal.cachedLabel)
    }

    // ── La detection de boucle, couche ① ─────────────────────────────────

    @Test
    fun `une regle qui ecrit ce qu elle lit est signalee`() {
        val logic = RuleLogic(
            Trigger.SignalChanged(TEMP),
            null,
            listOf(Action.SetSignal(TEMP, TypedValue.Float(0.0)))
        )
        assertEquals(setOf("d:0"), logic.selfTriggeringSignals())
    }

    @Test
    fun `une boucle passant par la condition est signalee aussi`() {
        val logic = RuleLogic(
            Trigger.SignalChanged(COMPTEUR),
            Condition.Compare(TEMP, Op.GT, Operand.Literal(TypedValue.Float(30.0))),
            listOf(Action.SetSignal(TEMP, TypedValue.Float(0.0)))
        )
        assertEquals(setOf("d:0"), logic.selfTriggeringSignals())
    }

    @Test
    fun `une regle ordinaire n est pas signalee`() {
        val logic = RuleLogic(
            Trigger.SignalChanged(TEMP), null,
            listOf(Action.SetSignal(CONSIGNE, TypedValue.Int(1)))
        )
        assertTrue(logic.selfTriggeringSignals().isEmpty())
    }
}
