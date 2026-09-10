package com.jeanloickdt.automation.v2

import java.time.Instant
import java.time.ZoneId

/**
 * L'évaluation d'une condition — logique à trois valeurs, repliée à la racine.
 *
 * Voir le §4 du document de sémantique. Ce fichier en est la transcription
 * exacte ; toute divergence est un bug ici.
 */

/**
 * Ce que vaut un signal à l'instant de l'évaluation.
 *
 * [Absent] n'est PAS une valeur par défaut : c'est l'absence de valeur, et
 * elle ne devient jamais `0` ni `""`. La même politique que la garde
 * d'ingestion — un chiffre faux qui a l'air légitime est pire qu'une donnée
 * absente.
 */
sealed interface SignalValue {
    data class Present(val value: TypedValue) : SignalValue
    data object Absent : SignalValue
}

/**
 * VRAI, FAUX, INDÉTERMINÉ.
 *
 * `UNKNOWN` naît d'un signal [SignalValue.Absent] et se propage selon Kleene.
 * À la racine, seul `TRUE` tire — mais `FALSE` et `UNKNOWN` se distinguent
 * dans la trace : « la condition était fausse » et « valeur absente » ne
 * disent pas la même chose à quelqu'un qui cherche pourquoi sa serre s'est
 * tue.
 */
enum class Truth {
    TRUE, FALSE, UNKNOWN;

    /**
     * La négation de Kleene : `VRAI → FAUX`, `FAUX → VRAI`,
     * `INDÉTERMINÉ → INDÉTERMINÉ`.
     *
     * C'est le seul endroit où le choix de la logique à trois valeurs se voit,
     * et c'est pour lui qu'il est fait. En binaire,
     * `Not(Compare(temp > 30))` sur un capteur mort vaudrait `Not(faux) = vrai`
     * — la règle « SI NOT température > 30 ALORS couper le chauffage » se
     * déclencherait précisément parce que la sonde ne répond plus. Ici, elle
     * ne se déclenche pas.
     */
    fun negate(): Truth = when (this) {
        TRUE -> FALSE
        FALSE -> TRUE
        UNKNOWN -> UNKNOWN
    }

    companion object {
        fun of(b: Boolean) = if (b) TRUE else FALSE

        /**
         * ET de Kleene. Un seul `FAUX` suffit à conclure — même en présence
         * d'indéterminés, ce qui est le point : une sonde muette n'empêche
         * pas de savoir qu'une condition ET est fausse.
         */
        fun all(values: List<Truth>): Truth = when {
            values.any { it == FALSE } -> FALSE
            values.any { it == UNKNOWN } -> UNKNOWN
            else -> TRUE
        }

        /** OU de Kleene. Symétrique : un seul `VRAI` suffit. */
        fun any(values: List<Truth>): Truth = when {
            values.any { it == TRUE } -> TRUE
            values.any { it == UNKNOWN } -> UNKNOWN
            else -> FALSE
        }
    }
}

/**
 * Ce que l'évaluation demande au monde.
 *
 * Une interface pour que les épreuves de logique n'aient pas à monter une
 * base ni une horloge : les tables de vérité se prouvent sur des valeurs
 * fabriquées, et c'est là qu'elles doivent se prouver.
 */
interface EvalContext {
    /** La dernière valeur connue — jamais lue depuis la base sur le chemin chaud. */
    fun valueOf(ref: SignalRef): SignalValue

    /** `null` quand la présence est inconnue → `UNKNOWN`, jamais « hors ligne ». */
    fun isOnline(ref: DeviceRef): Boolean?

    /** L'instant de l'évaluation, en millisecondes epoch. */
    fun nowMs(): Long
}

object ConditionEval {

    /**
     * Évalue [condition] dans le fuseau [zone] de la règle.
     *
     * `null` (pas de condition) vaut TOUJOURS VRAI.
     */
    fun evaluate(condition: Condition?, ctx: EvalContext, zone: ZoneId): Truth {
        if (condition == null) return Truth.TRUE
        return eval(condition, ctx, zone)
    }

    private fun eval(c: Condition, ctx: EvalContext, zone: ZoneId): Truth = when (c) {
        is Condition.Compare -> compare(c, ctx)
        is Condition.DeviceState -> when (ctx.isOnline(c.device)) {
            null -> Truth.UNKNOWN
            true -> Truth.of(c.state == Condition.Presence.ONLINE)
            false -> Truth.of(c.state == Condition.Presence.OFFLINE)
        }
        is Condition.TimeOfDay -> Truth.of(inRange(c, ctx.nowMs(), zone))
        is Condition.All -> Truth.all(c.children.map { eval(it, ctx, zone) })
        is Condition.Any -> Truth.any(c.children.map { eval(it, ctx, zone) })
        is Condition.Not -> eval(c.child, ctx, zone).negate()
    }

    private fun compare(c: Condition.Compare, ctx: EvalContext): Truth {
        val left = (ctx.valueOf(c.left) as? SignalValue.Present)?.value ?: return Truth.UNKNOWN
        val right = when (val r = c.right) {
            is Operand.Literal -> r.value
            is Operand.Signal -> (ctx.valueOf(r.ref) as? SignalValue.Present)?.value
                ?: return Truth.UNKNOWN
        }

        // Numerique des deux cotes : la promotion int -> float est exacte, donc
        // « compteur (int) > 30.0 » se compare sans rien fabriquer.
        val l = left.asDouble
        val r = right.asDouble
        if (l != null && r != null) {
            return Truth.of(when (c.op) {
                Op.GT -> l > r
                Op.GTE -> l >= r
                Op.LT -> l < r
                Op.LTE -> l <= r
                Op.EQ -> l == r
                Op.NEQ -> l != r
            })
        }

        // Texte des deux cotes : egalite seulement. L'ordre a deja ete refuse
        // a l'enregistrement par RuleValidation ; s'il arrive ici, c'est qu'un
        // type a change sans que la revalidation soit passee — on rend
        // INDETERMINE plutot qu'un resultat lexicographique qu'on sait faux.
        if (left is TypedValue.Text && right is TypedValue.Text) {
            return when (c.op) {
                Op.EQ -> Truth.of(left.value == right.value)
                Op.NEQ -> Truth.of(left.value != right.value)
                else -> Truth.UNKNOWN
            }
        }

        // Numerique contre texte : la regle aurait du etre marquee invalide.
        // INDETERMINE, jamais une conversion.
        return Truth.UNKNOWN
    }

    /**
     * L'instant courant tombe-t-il dans la plage, dans le fuseau de la règle ?
     *
     * Le franchissement de minuit est le bug classique. Quand `from <= to`,
     * la plage est ordinaire : `[from, to)`. Quand `from > to`, elle enjambe
     * minuit : on est dedans à partir de `from` OU avant `to`.
     *
     * `from == to` n'arrive jamais ici — le codec le refuse
     * (`degenerate-timerange`) parce qu'il ne désigne ni un instant ni une
     * journée entière.
     */
    fun inRange(c: Condition.TimeOfDay, nowMs: Long, zone: ZoneId): Boolean {
        val local = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime()
        val minute = local.hour * 60 + local.minute
        return if (c.fromMinute <= c.toMinute) {
            minute >= c.fromMinute && minute < c.toMinute
        } else {
            minute >= c.fromMinute || minute < c.toMinute
        }
    }
}
