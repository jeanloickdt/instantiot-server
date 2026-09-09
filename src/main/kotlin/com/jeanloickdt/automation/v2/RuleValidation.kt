package com.jeanloickdt.automation.v2

/**
 * La validation qui a besoin du MONDE — les types déclarés des signaux,
 * l'existence des cartes, la bascule `automationVisible`.
 *
 * Séparée du codec parce que les deux répondent à des questions différentes :
 * [RuleCodec] dit « ce JSON a-t-il une forme », ceci dit « cette règle a-t-elle
 * un sens contre l'inventaire actuel ». La première est pure et se teste avec
 * des fixtures ; la seconde a besoin d'une base.
 *
 * ## Quand ça tourne, et quand ça ne tourne PAS
 *
 * À l'enregistrement de la règle, et au moment où le type d'un signal change
 * (le même chemin que la purge d'historique). **Jamais par trame** : le chemin
 * d'ingestion est le goulot CPU n°1, il ne résout pas de types.
 */
object RuleValidation {

    const val E_TYPE_MISMATCH = "type-mismatch"
    const val E_SIGNAL_DELETED = "signal-deleted"
    const val E_DEVICE_DELETED = "device-deleted"
    const val E_SIGNAL_OFF = "signal-off-for-automation"

    /** Une attente en derniere position ne fait rien — voir [checkTrailingWait]. */
    const val E_TRAILING_WAIT = "trailing-wait"

    /** Un signal tel que l'inventaire le connaît aujourd'hui. */
    data class ResolvedSignal(
        val type: SignalType,
        val automationVisible: Boolean,
        val label: String
    )

    /**
     * Ce que la validation demande au monde. Une interface, pour que les
     * épreuves de logique n'aient pas à monter une base — et parce que la
     * validation n'a aucune raison de savoir d'où viennent les types.
     */
    interface Resolver {
        /** `null` quand le signal n'existe plus, ou n'a jamais existé. */
        fun signal(ownerId: String, ref: SignalRef): ResolvedSignal?

        /** `null` quand la carte n'existe plus. Le libellé sert à réhydrater. */
        fun deviceLabel(ownerId: String, ref: DeviceRef): String?
    }

    data class Invalid(val code: String, val detail: String)

    /**
     * `null` = la règle tient. Sinon le motif, tel qu'il sera écrit dans
     * `invalid_reason` et affiché en clair sur la carte.
     *
     * **Premier motif rencontré gagne.** Empiler les motifs donnerait une
     * phrase que personne ne lit ; « le signal a été supprimé » suffit à agir,
     * et le suivant apparaîtra quand celui-là sera réglé.
     */
    fun check(logic: RuleLogic, ownerId: String, resolver: Resolver): Invalid? {
        // ── Toutes les references resolvent-elles encore ? ────────────────
        for (ref in logic.allSignalRefs()) {
            val resolved = resolver.signal(ownerId, ref)
                ?: return Invalid(E_SIGNAL_DELETED, "signal ${ref.signalKey} no longer exists")
            // La bascule est autorisee — refuser la bascule creerait un
            // couplage odieux : impossible de retirer un signal de
            // l'automatisation sans demonter d'abord toutes les regles qui le
            // touchent. La regle devient invalide, visiblement, et redevient
            // valide si la bascule repasse a true.
            if (!resolved.automationVisible) {
                return Invalid(E_SIGNAL_OFF, "${resolved.label} was removed from automation signals")
            }
        }
        for (ref in logic.allDeviceRefs()) {
            resolver.deviceLabel(ownerId, ref)
                ?: return Invalid(E_DEVICE_DELETED, "device ${ref.deviceId} no longer exists")
        }

        // ── La forme de la sequence ───────────────────────────────────────
        checkTrailingWait(logic)?.let { return it }

        // ── La politique de type ──────────────────────────────────────────
        logic.condition?.let { c -> checkCondition(c, ownerId, resolver)?.let { return it } }
        for (action in logic.actions) {
            checkAction(action, ownerId, resolver)?.let { return it }
        }
        // Un declencheur de transition compare une valeur a un signal : la
        // MEME politique de type que `Compare`, avec son operateur — c'est
        // ainsi que « depasse » se fait refuser sur du texte, sans que la
        // regle soit ecrite deux fois.
        //
        // `from` reste en EQ : « en venant de » est une valeur precise, pas
        // un seuil.
        (logic.trigger as? Trigger.SignalTransition)?.let { t ->
            val type = resolver.signal(ownerId, t.signal)!!.type
            comparable(type, t.to, t.op)?.let { return it }
            t.from?.let { f -> comparable(type, f, Op.EQ)?.let { return it } }
        }
        return null
    }

    private fun checkCondition(c: Condition, ownerId: String, r: Resolver): Invalid? = when (c) {
        is Condition.Compare -> {
            val leftType = r.signal(ownerId, c.left)!!.type
            when (val right = c.right) {
                is Operand.Literal -> comparable(leftType, right.value, c.op)
                is Operand.Signal -> comparableTypes(leftType, r.signal(ownerId, right.ref)!!.type, c.op)
            }
        }
        is Condition.All -> c.children.firstNotNullOfOrNull { checkCondition(it, ownerId, r) }
        is Condition.Any -> c.children.firstNotNullOfOrNull { checkCondition(it, ownerId, r) }
        is Condition.Not -> checkCondition(c.child, ownerId, r)
        is Condition.DeviceState, is Condition.TimeOfDay -> null
    }

    private fun checkAction(a: Action, ownerId: String, r: Resolver): Invalid? = when (a) {
        is Action.SetSignal -> {
            val target = r.signal(ownerId, a.target)!!.type
            writable(target, a.value)
        }
        else -> null
    }

    /**
     * Une attente doit être suivie de quelque chose.
     *
     * « Attends trente secondes » en dernière position ne fait rien : elle
     * coûte une ligne en base et une échéance, pour réveiller une séquence
     * vide. Ce n'est jamais ce que quelqu'un voulait écrire, et c'est refusé à
     * l'écriture pour qu'il le découvre tout de suite plutôt qu'en constatant
     * que rien ne se passe.
     *
     * Vérifié ICI et pas dans le codec : le codec dit si une action est BIEN
     * FORMÉE, la validation si la règle a un SENS. Une attente finale est
     * parfaitement bien formée.
     */
    private fun checkTrailingWait(logic: RuleLogic): Invalid? =
        if (logic.actions.lastOrNull() is Action.Wait) {
            Invalid(E_TRAILING_WAIT, "a wait must be followed by something to do")
        } else null

    /**
     * Un signal de type [signalType] peut-il être comparé à [literal] avec [op] ?
     *
     * Voir le §2 du document de sémantique — la table y est écrite en toutes
     * lettres, ceci en est la transcription.
     */
    fun comparable(signalType: SignalType, literal: TypedValue, op: Op): Invalid? {
        val literalType = when (literal) {
            is TypedValue.Int -> SignalType.INT
            is TypedValue.Float -> SignalType.FLOAT
            is TypedValue.Text -> SignalType.TEXT
        }
        return comparableTypes(signalType, literalType, op)
    }

    fun comparableTypes(left: SignalType, right: SignalType, op: Op): Invalid? {
        // Numerique contre numerique : les six operateurs. La promotion
        // int -> float est exacte et sans perte — ce n'est PAS la coercition
        // que la politique interdit, qui fabriquerait une valeur inexistante.
        if (left.isNumeric && right.isNumeric) return null

        // Texte contre texte : l'egalite seulement. Une comparaison
        // lexicographique est un piege — « "9" > "10" » est VRAI et faux pour
        // n'importe quel humain, exactement le chiffre faux qui a l'air
        // legitime que la politique refuse ailleurs.
        if (left == SignalType.TEXT && right == SignalType.TEXT) {
            return if (op.isOrdering) {
                Invalid(E_TYPE_MISMATCH, "'${op.wire}' has no meaning on text — only eq and neq do")
            } else null
        }

        // Numerique contre texte, dans un sens ou dans l'autre : rien.
        return Invalid(
            E_TYPE_MISMATCH,
            "cannot compare ${left.wire} with ${right.wire}"
        )
    }

    /**
     * Peut-on écrire [value] dans un signal de type [target] ?
     *
     * `int` dans un `float` : oui, sans perte. `float` dans un `int` : non —
     * 21.7 deviendrait 21 ou 22 sans que personne ne l'ait demandé, et une
     * consigne qui part différente de celle affichée est indéfendable.
     */
    fun writable(target: SignalType, value: TypedValue): Invalid? = when {
        target == SignalType.TEXT && value is TypedValue.Text -> null
        target == SignalType.FLOAT && value.isNumeric -> null
        target == SignalType.INT && value is TypedValue.Int -> null
        target == SignalType.INT && value is TypedValue.Float -> Invalid(
            E_TYPE_MISMATCH,
            "writing a float into an int signal would round it silently"
        )
        else -> Invalid(
            E_TYPE_MISMATCH,
            "cannot write ${value.typeName} into a ${target.wire} signal"
        )
    }

    // ── Reposer les libelles a la lecture ────────────────────────────────

    /**
     * Réhydrate chaque `cachedLabel` depuis la jointure vivante.
     *
     * Le pendant de [RuleCodec.stripLabels] : le serveur retire les libellés à
     * l'écriture et les repose ici, pour que l'app ait de quoi afficher une
     * phrase même hors ligne, sans qu'un libellé périmé survive jamais en base.
     *
     * Une référence qui ne résout plus garde un libellé nul — la règle porte
     * déjà `signal-deleted` ou `device-deleted`, et inventer un nom masquerait
     * précisément ce que l'utilisateur doit voir.
     */
    fun rehydrate(logic: RuleLogic, ownerId: String, resolver: Resolver): RuleLogic {
        fun s(ref: SignalRef) = ref.copy(cachedLabel = resolver.signal(ownerId, ref)?.label)
        fun d(ref: DeviceRef) = ref.copy(cachedLabel = resolver.deviceLabel(ownerId, ref))

        fun cond(c: Condition): Condition = when (c) {
            is Condition.Compare -> c.copy(
                left = s(c.left),
                right = when (val r = c.right) {
                    is Operand.Signal -> Operand.Signal(s(r.ref))
                    is Operand.Literal -> r
                }
            )
            is Condition.DeviceState -> c.copy(device = d(c.device))
            is Condition.TimeOfDay -> c
            is Condition.All -> Condition.All(c.children.map { cond(it) })
            is Condition.Any -> Condition.Any(c.children.map { cond(it) })
            is Condition.Not -> Condition.Not(cond(c.child))
        }

        return RuleLogic(
            trigger = when (val t = logic.trigger) {
                is Trigger.SignalChanged -> t.copy(signal = s(t.signal))
                is Trigger.SignalTransition -> t.copy(signal = s(t.signal))
                is Trigger.SignalStale -> t.copy(signal = s(t.signal))
                is Trigger.DeviceConnected -> t.copy(device = d(t.device))
                is Trigger.DeviceDisconnected -> t.copy(device = d(t.device))
                is Trigger.Schedule -> t
            },
            condition = logic.condition?.let { cond(it) },
            actions = logic.actions.map { a ->
                if (a is Action.SetSignal) a.copy(target = s(a.target)) else a
            }
        )
    }
}
