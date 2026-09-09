package com.jeanloickdt.automation.v2

/**
 * Le scellé du schéma v2 — les trois blocs de logique d'une règle.
 *
 * Voir `docs/automations/SEMANTIQUE_AUTOMATION.md`. Ce fichier ne décide de
 * rien : il donne une forme à ce que ce document a tranché. Toute divergence
 * entre les deux est un bug ici, pas là-bas.
 *
 * ## Ce qui n'est PAS ici
 *
 * Le nom, `enabled`, le fuseau, le cooldown, la sévérité, `invalidReason` et
 * la version de schéma. Ce sont des COLONNES — révision ① du document. La
 * raison tient en une phrase : une app qui ne sait pas décoder une règle doit
 * quand même pouvoir la désactiver, et si `enabled` vivait dans le JSON, la
 * désactiver imposerait de réécrire ce JSON, donc d'en effacer les branches
 * qu'on n'a pas comprises.
 *
 * Ce fichier décrit donc EXACTEMENT le contenu de la colonne `definition` :
 * un déclencheur, une condition optionnelle, des actions ordonnées.
 */

// ── Les sujets ───────────────────────────────────────────────────────────

/**
 * Un signal, désigné de façon absolue.
 *
 * Le `projectId` est là bien que `deviceId` suffise à router : une règle
 * traverse librement les projets, et l'app a besoin de savoir dans quel
 * classeur ranger visuellement chaque référence sans refaire une lecture.
 *
 * [cachedLabel] est un repli d'affichage hors ligne, jamais une source de
 * vérité. Le serveur le RETIRE avant de persister et le repose à la lecture
 * depuis la jointure vivante — sinon un renommage laisserait des phrases
 * périmées en base pour toujours.
 */
data class SignalRef(
    val projectId: String,
    val deviceId: String,
    val address: Int,
    val cachedLabel: String? = null
) {
    /** `"deviceId:adresse"` — la clé utilisée partout ailleurs dans le relais. */
    val signalKey: String get() = "$deviceId:$address"

    /** Sans le libellé : la forme qui part en base. */
    fun stripped(): SignalRef = if (cachedLabel == null) this else copy(cachedLabel = null)
}

/** Une carte, désignée de façon absolue. Même politique de libellé. */
data class DeviceRef(
    val projectId: String,
    val deviceId: String,
    val cachedLabel: String? = null
) {
    fun stripped(): DeviceRef = if (cachedLabel == null) this else copy(cachedLabel = null)
}

/**
 * La clé d'indexation du chemin chaud.
 *
 * Un signal se réveille sur `(ownerId, deviceId, address)`, une carte sur
 * `(ownerId, deviceId)`. Le `projectId` n'y figure PAS : un signal ne change
 * pas de projet sans changer de carte, et l'inclure ferait rater l'index si
 * l'app envoyait un `projectId` périmé.
 */
sealed interface SubjectKey {
    val ownerId: String

    data class Signal(
        override val ownerId: String,
        val deviceId: String,
        val address: Int
    ) : SubjectKey

    data class Device(
        override val ownerId: String,
        val deviceId: String
    ) : SubjectKey
}

// ── Les valeurs ──────────────────────────────────────────────────────────

/**
 * Une valeur typée — `int`, `float`, ou `string`. Trois types, et pas un de
 * plus.
 *
 * Le protocole 2.0 a supprimé `bool` et `enum` du fil : une carte n'envoie pas
 * « un booléen », elle envoie un entier. Un interrupteur est donc un `int`
 * valant 0 ou 1, et « quand le bouton passe à l'état pressé » s'écrit
 * `SignalTransition(to = Int(1))`.
 */
sealed interface TypedValue {
    data class Int(val value: Long) : TypedValue
    data class Float(val value: Double) : TypedValue
    data class Text(val value: String) : TypedValue

    /** Le nom du type sur le fil ET dans les messages d'erreur. */
    val typeName: kotlin.String
        get() = when (this) {
            is Int -> "int"
            is Float -> "float"
            is Text -> "string"
        }

    /** Vrai pour `int` et `float` — ce que la promotion du §2 autorise. */
    val isNumeric: Boolean get() = this is Int || this is Float

    /**
     * La valeur comme double, pour comparer un `int` à un `float`.
     *
     * `null` sur du texte : la promotion numérique est exacte et sans perte,
     * mais fabriquer un nombre depuis une chaîne serait exactement la
     * coercition que la politique refuse.
     */
    val asDouble: Double?
        get() = when (this) {
            is Int -> value.toDouble()
            is Float -> value
            is Text -> null
        }
}

/** Le type déclaré d'un signal, tel que la table le porte. */
enum class SignalType(val wire: String) {
    INT("int"), FLOAT("float"), TEXT("string");

    val isNumeric: Boolean get() = this == INT || this == FLOAT

    companion object {
        fun ofWire(wire: String): SignalType? = entries.firstOrNull { it.wire == wire }
    }
}

// ── Le déclencheur ───────────────────────────────────────────────────────

/**
 * Ce qui réveille le moteur. Un événement pur, sans comparaison — c'est
 * précisément la séparation que la v1 n'avait pas, et sans laquelle
 * « quand la température change, SI l'humidité est basse » est impossible.
 *
 * Exactement un déclencheur par règle. `automation_state` porte une ligne par
 * règle ; plusieurs déclencheurs demanderaient de savoir lequel a tiré pour
 * rendre le message avec sa valeur d'origine.
 */
sealed interface Trigger {

    /** Toute nouvelle trame sur ce signal, quelle que soit sa valeur. */
    data class SignalChanged(val signal: SignalRef) : Trigger

    /**
     * Le FRONT — la valeur devient [to], en venant de [from] si précisé.
     *
     * C'est ce qui évite deux cents notifications pour un bouton maintenu
     * enfoncé : `SignalChanged` tire à chaque trame, celui-ci ne tire qu'au
     * passage. La valeur précédente vit dans `automation_state`, jamais dans
     * la définition — la leçon de `ValueChanged` chez Blynk, qui écrivait
     * l'état d'exécution dans la règle elle-même.
     */
    data class SignalTransition(
        val signal: SignalRef,
        val to: TypedValue,
        /**
         * COMMENT la valeur atteint [to].
         *
         * Le front n'est pas propre à l'égalité : c'est un prédicat qui passe
         * de FAUX à VRAI. « devient 30 » et « dépasse 30 » sont le même
         * mécanisme avec deux prédicats, et seul le premier existait.
         *
         * L'absence des autres n'était pas une simplification mais une
         * impasse : sur une sonde analogique, la température va de 29,8 à
         * 30,2 sans jamais valoir 30,000, et « devient 30 » ne tire JAMAIS.
         * Il ne restait que « à chaque trame », qui tire toujours.
         *
         * `EQ` par défaut : les règles écrites avant ce champ gardent leur
         * sens, et rien ne migre.
         */
        val op: Op = Op.EQ,
        val from: TypedValue? = null
    ) : Trigger

    /**
     * La carte est revenue, CONFIRMÉ après [afterMs].
     *
     * ## Pourquoi une confirmation ici aussi
     *
     * Le symétrique de [DeviceDisconnected] : un Wi-Fi faible fait battre une
     * carte entre les deux états, et « elle est revenue » à chaque battement
     * vaut autant de push qu'un rebond de déconnexion. Vouloir « revenue ET
     * stable » est la même demande, prise par l'autre bout.
     *
     * `null` — le cas de toutes les règles écrites avant ce champ — tire dès
     * la reconnexion, exactement comme avant. C'est ce qui rend le champ
     * rétrocompatible sans migration.
     *
     * ## Ce que l'attente annule
     *
     * Une déconnexion pendant l'attente la vide : la carte n'est pas restée.
     * Sans cela, un aller-retour de trois secondes finirait par déclencher une
     * règle qui dit « connectée depuis cinq minutes », ce qui serait faux.
     */
    data class DeviceConnected(val device: DeviceRef, val afterMs: Long? = null) : Trigger

    /**
     * La carte s'est tue, CONFIRMÉ après [afterMs] — l'anti-rebond d'un
     * Wi-Fi faible, sans lequel chaque hoquet est un push.
     *
     * `afterMs` non nul exige un travail à échéance : rien ne publie « ça fait
     * trente secondes que ». Pas d'UI au MVP — voir le §1 du document.
     */
    data class DeviceDisconnected(val device: DeviceRef, val afterMs: Long? = null) : Trigger

    /**
     * Le signal ne parle plus depuis [afterMs].
     *
     * L'absence d'événement n'est pas un événement : rien ne publie ceci
     * aujourd'hui. Dans le scellé, pas dans l'UI du MVP.
     */
    data class SignalStale(val signal: SignalRef, val afterMs: Long) : Trigger

    /**
     * Une heure murale, dans le fuseau DE LA RÈGLE.
     *
     * Ne porte pas de fuseau : deux fuseaux dans une même règle rendraient
     * légale une règle programmée à Toronto qui teste les heures de Téhéran.
     * [days] vide = tous les jours.
     */
    data class Schedule(val minuteOfDay: Int, val days: Set<Day>) : Trigger

    enum class Day(val wire: String) {
        MON("mon"), TUE("tue"), WED("wed"), THU("thu"),
        FRI("fri"), SAT("sat"), SUN("sun");

        companion object {
            fun ofWire(wire: String): Day? = entries.firstOrNull { it.wire == wire }
        }
    }
}

// ── La condition ─────────────────────────────────────────────────────────

/** Ce qui se compare, à droite d'un [Condition.Compare]. */
sealed interface Operand {
    data class Literal(val value: TypedValue) : Operand
    data class Signal(val ref: SignalRef) : Operand
}

enum class Op(val wire: String) {
    GT("gt"), GTE("gte"), LT("lt"), LTE("lte"), EQ("eq"), NEQ("neq");

    /**
     * Vrai pour les quatre opérateurs d'ORDRE.
     *
     * La distinction sert au §2 : sur du texte, l'ordre est refusé — `"9" >
     * "10"` est vrai en lexicographique et faux pour n'importe quel humain —
     * alors que l'égalité a un sens que l'utilisateur devine juste.
     */
    val isOrdering: Boolean get() = this == GT || this == GTE || this == LT || this == LTE

    companion object {
        fun ofWire(wire: String): Op? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Le test qui décide de tirer, évalué AU MOMENT du déclencheur.
 *
 * Composable, contrairement à Blynk qui n'a qu'une condition unique — d'où
 * leurs primitives `BETWEEN` et `NOT_BETWEEN`. Avec [All], `Between` n'a plus
 * de raison d'être : `All(GTE 20, LTE 30)` le couvre. Un jeu de primitives
 * petit et composable bat un catalogue.
 *
 * `null` (pas de condition) signifie TOUJOURS VRAI.
 */
sealed interface Condition {

    data class Compare(val left: SignalRef, val op: Op, val right: Operand) : Condition

    data class DeviceState(val device: DeviceRef, val state: Presence) : Condition

    /**
     * Une plage horaire, dans le fuseau de la règle. Ne porte pas de fuseau,
     * même raison que [Trigger.Schedule].
     *
     * Le franchissement de minuit (22 h → 6 h) est le bug classique : quand
     * `from > to`, la plage enjambe le jour suivant.
     */
    data class TimeOfDay(val fromMinute: Int, val toMinute: Int) : Condition

    /** ET. Au moins deux enfants — un `All` à un enfant est cet enfant. */
    data class All(val children: List<Condition>) : Condition

    /** OU. Au moins deux enfants, même raison. */
    data class Any(val children: List<Condition>) : Condition

    data class Not(val child: Condition) : Condition

    enum class Presence(val wire: String) {
        ONLINE("online"), OFFLINE("offline");

        companion object {
            fun ofWire(wire: String): Presence? = entries.firstOrNull { it.wire == wire }
        }
    }
}

// ── L'action ─────────────────────────────────────────────────────────────

/**
 * L'effet de bord. Le moteur ne les EXÉCUTE pas : il les écrit dans
 * `pending_actions`, dans l'ordre de la liste, et s'arrête là. La livraison
 * appartient au `DeliveryWorker`, et cette frontière ne bouge pas.
 */
sealed interface Action {

    data class Push(val title: String, val body: String) : Action

    /**
     * Un email.
     *
     * @param to a QUI. `null` = l'adresse du compte, ce qui etait le seul
     *   comportement possible jusqu'ici : la regle n'avait pas de quoi dire
     *   autre chose, et le proprietaire recevait donc tout. Une alerte de gel
     *   qui doit partir au voisin qui a les cles n'avait aucun chemin.
     *
     *   `null` par defaut, donc les regles deja ecrites gardent exactement le
     *   comportement qu'elles avaient.
     */
    data class Email(
        val subject: String,
        val body: String,
        val to: String? = null
    ) : Action

    /**
     * Une commande vers une carte.
     *
     * Sémantique « au plus une fois ». Si la carte est hors ligne au moment du
     * tir, l'action est ABANDONNÉE — une vanne qui s'ouvre quand la carte se
     * reconnecte deux heures plus tard est le scénario qu'on refuse.
     */
    data class SetSignal(val target: SignalRef, val value: TypedValue) : Action

    /**
     * Dans le scellé, PAS dans l'UI, et pas par oubli : trois décisions de
     * sécurité manquent — refus des adresses privées et de lien-local (SSRF),
     * délai et taille de réponse bornés, et une sémantique de livraison qu'on
     * ne peut pas promettre à un destinataire qu'on ne contrôle pas.
     */
    data class Webhook(val url: String, val method: String, val body: String? = null) : Action

    /**
     * Chaîner une autre règle. Une seule lecture est cohérente : on SAUTE le
     * déclencheur de la règle appelée, on évalue sa condition, on exécute ses
     * actions. Toute autre interprétation est un bug.
     *
     * Dans le scellé, pas dans l'UI du MVP.
     */
    data class StartRule(val ruleId: String) : Action

    /**
     * Attendre avant la suite. La seule action qui ne fait rien.
     *
     * ## Ce qu'elle rend possible
     *
     * « Allume, attends trente secondes, éteins. » Sans elle, il faut deux
     * règles et un signal intermédiaire pour dire une chose que tout le monde
     * formule en une phrase.
     *
     * ## Ce n'est JAMAIS un `delay()`
     *
     * L'échéance et les actions restantes sont PERSISTÉES, et le moteur rend
     * la main. Un redéploiement à 21 h ne doit pas laisser le chauffage
     * allumé toute la nuit — et c'est exactement ce qu'un fil endormi ferait.
     *
     * ## Aucune politique de chevauchement
     *
     * Deux déclenchements qui se chevauchent font deux séquences
     * indépendantes, et sur un signal la dernière écriture gagne — comme
     * partout ailleurs.
     *
     * Ce n'est pas une question laissée ouverte : le chevauchement est une
     * propriété du DÉCLENCHEUR, pas des actions. Un horaire tire une fois, un
     * front peut re-franchir, « à chaque trame » tire en permanence.
     * L'utilisateur choisit son déclencheur, et ce choix EST déjà le choix du
     * comportement. Inventer un arbitrage reviendrait à décider à sa place.
     */
    data class Wait(val delay: Delay) : Action

    /**
     * Les deux formes d'une attente.
     *
     * Deux variantes plutôt que deux champs dont un seul serait rempli :
     * l'état illégal — les deux, ou aucun — n'est alors pas représentable, et
     * il n'y a rien à vérifier au moment de lire.
     */
    sealed interface Delay {
        /**
         * « pendant 30 secondes » — relative, sans fuseau.
         *
         * Elle n'en a pas besoin : une durée est la même partout, et lui en
         * donner un serait la deuxième vérité de fuseau que le modèle refuse.
         */
        data class For(val seconds: Long) : Delay

        /**
         * « jusqu'à 23:00 » — absolue, dans le fuseau de la RÈGLE.
         *
         * Celui de la règle, jamais un autre : un `schedule` et une attente
         * portant chacun le leur rendraient légale une règle programmée à
         * Toronto qui se réveille à l'heure de Téhéran.
         *
         * Quand l'heure est déjà passée, c'est la PROCHAINE occurrence. « Jusqu'à
         * 23 h » écrit à 23 h 30 ne peut pas vouloir dire « il y a trente
         * minutes » : la seule lecture défendable est demain.
         */
        data class Until(val minuteOfDay: Int) : Delay
    }
}

// ── L'assemblage ─────────────────────────────────────────────────────────

/**
 * Le contenu EXACT de la colonne `definition`. Rien d'autre n'y entre.
 */
data class RuleLogic(
    val trigger: Trigger,
    val condition: Condition? = null,
    val actions: List<Action>
)

// ── Ce que le moteur et la maintenance demandent au modèle ───────────────

/**
 * Le sujet du DÉCLENCHEUR — ce qui entre dans l'index chaud.
 *
 * Uniquement le déclencheur : indexer aussi les références de condition
 * réveillerait des règles qui ne peuvent pas tirer.
 */
fun Trigger.subjectKey(ownerId: String): SubjectKey? = when (this) {
    is Trigger.SignalChanged ->
        SubjectKey.Signal(ownerId, signal.deviceId, signal.address)
    is Trigger.SignalTransition ->
        SubjectKey.Signal(ownerId, signal.deviceId, signal.address)
    is Trigger.SignalStale ->
        SubjectKey.Signal(ownerId, signal.deviceId, signal.address)
    is Trigger.DeviceConnected ->
        SubjectKey.Device(ownerId, device.deviceId)
    is Trigger.DeviceDisconnected ->
        SubjectKey.Device(ownerId, device.deviceId)
    // Un horaire n'a pas de sujet : c'est l'ordonnanceur qui le reveille.
    is Trigger.Schedule -> null
}

/**
 * TOUTES les références de signal de la règle — déclencheur, condition,
 * actions.
 *
 * Alimente l'index de MAINTENANCE, jamais le chemin chaud : c'est ce qui
 * permet de retrouver les règles touchées par une suppression, un changement
 * de type ou une bascule `automationVisible`.
 */
fun RuleLogic.allSignalRefs(): Set<SignalRef> = buildSet {
    addAll(trigger.signalRefs())
    condition?.let { addAll(it.signalRefs()) }
    actions.forEach { addAll(it.signalRefs()) }
}

/** Idem pour les cartes. */
fun RuleLogic.allDeviceRefs(): Set<DeviceRef> = buildSet {
    addAll(trigger.deviceRefs())
    condition?.let { addAll(it.deviceRefs()) }
}

fun Trigger.signalRefs(): Set<SignalRef> = when (this) {
    is Trigger.SignalChanged -> setOf(signal)
    is Trigger.SignalTransition -> setOf(signal)
    is Trigger.SignalStale -> setOf(signal)
    is Trigger.DeviceConnected, is Trigger.DeviceDisconnected, is Trigger.Schedule -> emptySet()
}

fun Trigger.deviceRefs(): Set<DeviceRef> = when (this) {
    is Trigger.DeviceConnected -> setOf(device)
    is Trigger.DeviceDisconnected -> setOf(device)
    else -> emptySet()
}

fun Condition.signalRefs(): Set<SignalRef> = when (this) {
    is Condition.Compare -> buildSet {
        add(left)
        (right as? Operand.Signal)?.let { add(it.ref) }
    }
    is Condition.All -> children.flatMap { it.signalRefs() }.toSet()
    is Condition.Any -> children.flatMap { it.signalRefs() }.toSet()
    is Condition.Not -> child.signalRefs()
    is Condition.DeviceState, is Condition.TimeOfDay -> emptySet()
}

fun Condition.deviceRefs(): Set<DeviceRef> = when (this) {
    is Condition.DeviceState -> setOf(device)
    is Condition.All -> children.flatMap { it.deviceRefs() }.toSet()
    is Condition.Any -> children.flatMap { it.deviceRefs() }.toSet()
    is Condition.Not -> child.deviceRefs()
    is Condition.Compare, is Condition.TimeOfDay -> emptySet()
}

fun Action.signalRefs(): Set<SignalRef> = when (this) {
    is Action.SetSignal -> setOf(target)
    // `Wait` ne lit ni n'ecrit aucun signal : elle ne peut donc participer a
    // aucune boucle, et elle ne resout rien qui puisse devenir invalide.
    is Action.Push, is Action.Email, is Action.Webhook,
    is Action.StartRule, is Action.Wait -> emptySet()
}

/**
 * La détection de boucle de la couche ① — celle de l'enregistrement.
 *
 * Non vide, la règle peut se déclencher elle-même. On AVERTIT, on ne bloque
 * pas : un asservissement volontaire est un usage légitime.
 *
 * Comparaison sur `signalKey` et non sur la référence entière : deux `SignalRef`
 * du même signal peuvent différer par leur `projectId` si l'app en envoie un
 * périmé, et une boucle réelle passerait alors inaperçue.
 */
fun RuleLogic.selfTriggeringSignals(): Set<String> {
    val written = actions.filterIsInstance<Action.SetSignal>()
        .map { it.target.signalKey }.toSet()
    if (written.isEmpty()) return emptySet()
    val read = (trigger.signalRefs() + (condition?.signalRefs() ?: emptySet()))
        .map { it.signalKey }.toSet()
    return written intersect read
}
