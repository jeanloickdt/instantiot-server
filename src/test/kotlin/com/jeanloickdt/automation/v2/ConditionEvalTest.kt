package com.jeanloickdt.automation.v2

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Les tables de vérité du §4, et le franchissement de minuit du §2.
 *
 * Aucune base, aucune horloge réelle : la sémantique se prouve sur des valeurs
 * fabriquées, sinon on ne prouve que la plomberie.
 */
class ConditionEvalTest {

    private val TORONTO: ZoneId = ZoneId.of("America/Toronto")
    private val TEMP = SignalRef("p", "d", 0)
    private val HUM = SignalRef("p", "d", 1)
    private val VENTILO = DeviceRef("p", "d2")

    private class Ctx(
        val values: Map<String, TypedValue> = emptyMap(),
        val online: Map<String, Boolean> = emptyMap(),
        val now: Long = 0L
    ) : EvalContext {
        override fun valueOf(ref: SignalRef): SignalValue =
            values[ref.signalKey]?.let { SignalValue.Present(it) } ?: SignalValue.Absent
        override fun isOnline(ref: DeviceRef): Boolean? = online[ref.deviceId]
        override fun nowMs(): Long = now
    }

    private fun at(h: Int, m: Int): Long =
        ZonedDateTime.of(2026, 9, 3, h, m, 0, 0, TORONTO).toInstant().toEpochMilli()

    private fun gt(ref: SignalRef, v: Double) =
        Condition.Compare(ref, Op.GT, Operand.Literal(TypedValue.Float(v)))

    // ── Absent ───────────────────────────────────────────────────────────

    @Test
    fun `une condition nulle vaut toujours vrai`() {
        assertEquals(Truth.TRUE, ConditionEval.evaluate(null, Ctx(), TORONTO))
    }

    @Test
    fun `un signal sans valeur rend indetermine et jamais faux`() {
        // La distinction compte : « la condition etait fausse » et « valeur
        // absente » ne disent pas la meme chose a quelqu'un qui cherche
        // pourquoi sa serre s'est tue.
        assertEquals(
            Truth.UNKNOWN,
            ConditionEval.evaluate(gt(TEMP, 30.0), Ctx(), TORONTO)
        )
    }

    @Test
    fun `absent ne devient jamais zero`() {
        // « temperature > -1 » serait VRAI si Absent valait 0. Il ne le vaut
        // pas : un chiffre faux qui a l'air legitime est pire qu'une donnee
        // absente.
        val c = Condition.Compare(TEMP, Op.GT, Operand.Literal(TypedValue.Float(-1.0)))
        assertEquals(Truth.UNKNOWN, ConditionEval.evaluate(c, Ctx(), TORONTO))
    }

    @Test
    fun `not sur une sonde morte ne declenche pas`() {
        // LE cas qui justifie la logique a trois valeurs. En binaire,
        // Not(faux) = vrai : « SI NOT temperature > 30 ALORS couper le
        // chauffage » couperait le chauffage parce que la sonde ne repond plus.
        val c = Condition.Not(gt(TEMP, 30.0))
        assertEquals(Truth.UNKNOWN, ConditionEval.evaluate(c, Ctx(), TORONTO))
    }

    // ── Les tables de Kleene ─────────────────────────────────────────────

    @Test
    fun `la table du ET`() {
        val V = Truth.TRUE; val F = Truth.FALSE; val I = Truth.UNKNOWN
        assertEquals(V, Truth.all(listOf(V, V)))
        assertEquals(F, Truth.all(listOf(V, F)))
        assertEquals(I, Truth.all(listOf(V, I)))
        assertEquals(F, Truth.all(listOf(F, F)))
        // Un seul FAUX conclut, meme avec un indetermine a cote : une sonde
        // muette n'empeche pas de savoir qu'un ET est faux.
        assertEquals(F, Truth.all(listOf(F, I)))
        assertEquals(I, Truth.all(listOf(I, I)))
    }

    @Test
    fun `la table du OU`() {
        val V = Truth.TRUE; val F = Truth.FALSE; val I = Truth.UNKNOWN
        assertEquals(V, Truth.any(listOf(V, V)))
        assertEquals(V, Truth.any(listOf(V, F)))
        assertEquals(V, Truth.any(listOf(V, I)))
        assertEquals(F, Truth.any(listOf(F, F)))
        assertEquals(I, Truth.any(listOf(F, I)))
        assertEquals(I, Truth.any(listOf(I, I)))
    }

    @Test
    fun `la table du NON`() {
        assertEquals(Truth.FALSE, Truth.TRUE.negate())
        assertEquals(Truth.TRUE, Truth.FALSE.negate())
        assertEquals(Truth.UNKNOWN, Truth.UNKNOWN.negate())
    }

    @Test
    fun `un ET faux conclut meme si l autre branche est muette`() {
        val c = Condition.All(listOf(gt(TEMP, 30.0), gt(HUM, 50.0)))
        // TEMP a 20 rend la premiere branche FAUSSE, HUM est absente.
        val ctx = Ctx(values = mapOf("d:0" to TypedValue.Float(20.0)))
        assertEquals(Truth.FALSE, ConditionEval.evaluate(c, ctx, TORONTO))
    }

    // ── La promotion numerique ───────────────────────────────────────────

    @Test
    fun `un compteur entier se compare a un seuil decimal`() {
        // La promotion int -> float est exacte et sans perte. Sans elle, le
        // cas d'usage le plus courant du produit serait refuse.
        val c = Condition.Compare(TEMP, Op.GTE, Operand.Literal(TypedValue.Float(1000.5)))
        assertEquals(
            Truth.TRUE,
            ConditionEval.evaluate(c, Ctx(mapOf("d:0" to TypedValue.Int(1001))), TORONTO)
        )
        assertEquals(
            Truth.FALSE,
            ConditionEval.evaluate(c, Ctx(mapOf("d:0" to TypedValue.Int(1000))), TORONTO)
        )
    }

    @Test
    fun `deux signaux se comparent entre eux`() {
        val c = Condition.Compare(TEMP, Op.GT, Operand.Signal(HUM))
        val ctx = Ctx(mapOf("d:0" to TypedValue.Float(31.0), "d:1" to TypedValue.Float(30.0)))
        assertEquals(Truth.TRUE, ConditionEval.evaluate(c, ctx, TORONTO))
    }

    @Test
    fun `un texte compare avec un ordre rend indetermine plutot qu un resultat lexicographique`() {
        // L'ordre sur du texte est refuse a l'enregistrement. S'il arrive ici,
        // c'est qu'un type a change sans revalidation : on rend INDETERMINE
        // plutot que « "9" > "10" », qui est vrai en lexicographique et faux
        // pour n'importe quel humain.
        val c = Condition.Compare(TEMP, Op.GT, Operand.Literal(TypedValue.Text("10")))
        val ctx = Ctx(mapOf("d:0" to TypedValue.Text("9")))
        assertEquals(Truth.UNKNOWN, ConditionEval.evaluate(c, ctx, TORONTO))
    }

    @Test
    fun `deux textes se comparent en egalite`() {
        val c = Condition.Compare(TEMP, Op.EQ, Operand.Literal(TypedValue.Text("auto")))
        assertEquals(
            Truth.TRUE,
            ConditionEval.evaluate(c, Ctx(mapOf("d:0" to TypedValue.Text("auto"))), TORONTO)
        )
        assertEquals(
            Truth.FALSE,
            ConditionEval.evaluate(c, Ctx(mapOf("d:0" to TypedValue.Text("manuel"))), TORONTO)
        )
    }

    // ── La presence ──────────────────────────────────────────────────────

    @Test
    fun `une presence inconnue rend indetermine et pas hors ligne`() {
        val c = Condition.DeviceState(VENTILO, Condition.Presence.OFFLINE)
        assertEquals(Truth.UNKNOWN, ConditionEval.evaluate(c, Ctx(), TORONTO))
    }

    @Test
    fun `une carte en ligne satisfait online et pas offline`() {
        val ctx = Ctx(online = mapOf("d2" to true))
        assertEquals(
            Truth.TRUE,
            ConditionEval.evaluate(Condition.DeviceState(VENTILO, Condition.Presence.ONLINE), ctx, TORONTO)
        )
        assertEquals(
            Truth.FALSE,
            ConditionEval.evaluate(Condition.DeviceState(VENTILO, Condition.Presence.OFFLINE), ctx, TORONTO)
        )
    }

    // ── La plage horaire, et minuit ──────────────────────────────────────

    @Test
    fun `une plage ordinaire est fermee a gauche et ouverte a droite`() {
        val c = Condition.TimeOfDay(8 * 60, 18 * 60)   // 08:00 -> 18:00
        assertEquals(Truth.TRUE, ConditionEval.evaluate(c, Ctx(now = at(8, 0)), TORONTO))
        assertEquals(Truth.TRUE, ConditionEval.evaluate(c, Ctx(now = at(12, 0)), TORONTO))
        assertEquals(Truth.TRUE, ConditionEval.evaluate(c, Ctx(now = at(17, 59)), TORONTO))
        // 18:00 est DEHORS : une borne fermee des deux cotes ferait qu'une
        // plage 8-18 et une plage 18-22 se chevauchent d'une minute.
        assertEquals(Truth.FALSE, ConditionEval.evaluate(c, Ctx(now = at(18, 0)), TORONTO))
        assertEquals(Truth.FALSE, ConditionEval.evaluate(c, Ctx(now = at(7, 59)), TORONTO))
    }

    @Test
    fun `une plage qui franchit minuit enjambe le jour suivant`() {
        val c = Condition.TimeOfDay(22 * 60, 6 * 60)   // 22:00 -> 06:00
        assertEquals(Truth.TRUE, ConditionEval.evaluate(c, Ctx(now = at(22, 0)), TORONTO))
        assertEquals(Truth.TRUE, ConditionEval.evaluate(c, Ctx(now = at(23, 59)), TORONTO))
        assertEquals(Truth.TRUE, ConditionEval.evaluate(c, Ctx(now = at(0, 0)), TORONTO))
        assertEquals(Truth.TRUE, ConditionEval.evaluate(c, Ctx(now = at(5, 59)), TORONTO))
        assertEquals(Truth.FALSE, ConditionEval.evaluate(c, Ctx(now = at(6, 0)), TORONTO))
        assertEquals(Truth.FALSE, ConditionEval.evaluate(c, Ctx(now = at(12, 0)), TORONTO))
        assertEquals(Truth.FALSE, ConditionEval.evaluate(c, Ctx(now = at(21, 59)), TORONTO))
    }

    @Test
    fun `la plage se lit dans le fuseau de la regle`() {
        // Le meme instant, deux fuseaux, deux reponses. C'est tout l'interet
        // d'un fuseau porte par la REGLE : « entre 8 h et 18 h » veut dire
        // 8 h chez celui qui a ecrit la regle.
        val c = Condition.TimeOfDay(8 * 60, 18 * 60)
        val midiToronto = at(12, 0)
        assertEquals(Truth.TRUE, ConditionEval.evaluate(c, Ctx(now = midiToronto), TORONTO))
        // Midi a Toronto, c'est 18 h a Paris — hors plage.
        assertEquals(
            Truth.FALSE,
            ConditionEval.evaluate(c, Ctx(now = midiToronto), ZoneId.of("Europe/Paris"))
        )
    }
}
