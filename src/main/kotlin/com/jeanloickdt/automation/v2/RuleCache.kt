package com.jeanloickdt.automation.v2

import com.jeanloickdt.automation.data.AutomationRuleTable
import com.jeanloickdt.automation.data.AutomationStateTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.ZoneId

private val logger = LoggerFactory.getLogger("RuleCache")

/**
 * L'état mutable d'une règle, en RAM.
 *
 * Muté par le MOTEUR seul — une coroutine, donc aucune synchronisation.
 * Persisté aux transitions, pour qu'un redémarrage nocturne ne rejoue pas les
 * alertes de la veille au petit matin.
 *
 * ## Ce qui a disparu du v1, et pourquoi
 *
 * `triggered` et `lastValue` n'existent plus. Le v1 était à NIVEAU : une règle
 * se verrouillait en franchissant un seuil et ne se réarmait qu'en repassant
 * une ligne d'hystérésis. Le v2 est IMPULSIONNEL — chaque événement
 * ré-évalue entièrement.
 *
 * La raison de la bascule tient en une question sans réponse : sur
 * `All(a > 30, b < 40)`, quand la règle redevient-elle armée ? Quand les deux
 * redeviennent fausses ? Quand l'une l'est ? Aucune réponse n'est défendable,
 * donc on a supprimé la question.
 *
 * [lastValue] survit sous une autre forme et pour un seul usage :
 * `SignalTransition`, qui doit savoir d'où la valeur vient pour ne tirer qu'au
 * front.
 */
class RuleState(
    var lastFiredAt: Long? = null,
    /** La valeur précédente du signal déclencheur — pour `SignalTransition`. */
    var lastValue: TypedValue? = null,

    // ── Les compteurs du jour ─────────────────────────────────────────────
    //
    // Ce qui RESTE apres le retrait du fusible et de la sourdine. Ils
    // n'empechent rien — ils expliquent. C'est la contrepartie exacte du
    // retrait : on ne protege plus l'utilisateur de lui-meme, donc on lui
    // doit de pouvoir comprendre ce que ses regles font.
    var dayStartedAt: Long? = null,
    var evaluations: Int = 0,
    var fired: Int = 0,
    var conditionFalse: Int = 0,
    var conditionUnknown: Int = 0,

    /**
     * L'instant où la carte a disparu, en attente de confirmation.
     *
     * Volontairement en RAM SEULE : un redémarrage dans la fenêtre de
     * confirmation perd cette confirmation-là. Accepté — l'alternative est une
     * colonne de schéma pour un cas de bord de moins d'une minute que
     * personne ne rencontrera deux fois.
     */
    var pendingOfflineSince: Long? = null,

    /**
     * L'instant où la carte est revenue, en attente de confirmation.
     *
     * Le symétrique du précédent, et il lui est EXCLUSIF : une carte est
     * partie ou revenue, jamais les deux. Les tenir séparés évite quand même
     * de relire le déclencheur pour savoir ce que la date signifie.
     *
     * En RAM seule, pour la même raison : un redémarrage dans la fenêtre perd
     * cette confirmation-là, et l'alternative est une colonne de schéma pour
     * un cas de bord que personne ne rencontrera deux fois.
     */
    var pendingOnlineSince: Long? = null
) {
    /**
     * Remet les compteurs à zéro quand on a changé de jour LOCAL.
     *
     * Minuit dans le fuseau de la règle, pas minuit UTC : « aujourd'hui » sur
     * la fiche doit dire la même chose que sur l'horloge de celui qui lit.
     */
    fun rollDayIfNeeded(nowMs: Long, zone: ZoneId) {
        val midnight = java.time.Instant.ofEpochMilli(nowMs)
            .atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        if (dayStartedAt == midnight) return
        dayStartedAt = midnight
        evaluations = 0; fired = 0; conditionFalse = 0; conditionUnknown = 0
    }
}

/**
 * Une règle CHARGÉE : sa définition déjà décodée et validée, ses réglages, son
 * état.
 *
 * « Déjà décodée » est le point. Parser du JSON à chaque trame est hors de
 * question : le décodage a lieu une fois, au chargement du cache.
 *
 * [invalidReason] et [enabled] voyagent avec elle pour la même raison — sans
 * eux, la troisième étape de l'ordre canonique irait lire `automation_state`
 * à chaque trame, ce qui annulerait tout l'intérêt du cache.
 */
class LoadedRule(
    val id: String,
    val ownerId: String,
    val name: String,
    val enabled: Boolean,
    val severity: String,
    val zone: ZoneId,
    /** Non nul : la règle ne s'évalue jamais. */
    val invalidReason: String?,
    /** `null` quand la définition n'a pas pu être décodée du tout. */
    val logic: RuleLogic?,
    val state: RuleState
) {
    /**
     * L'état affiché, DÉRIVÉ et jamais stocké deux fois. Premier cas gagnant.
     *
     * Le stocker en colonne aurait créé deux vérités : celle du calcul et
     * celle de la ligne, qui divergent au premier oubli de mise à jour.
     */
    fun displayState(): String = when {
        invalidReason != null -> "INVALID"
        !enabled -> "DISABLED"
        else -> "ACTIVE"
    }

    /**
     * Ni invalide, ni éteinte — l'étape 3 de l'ordre canonique.
     *
     * `MUTED` est parti avec le fusible : trois états, et les trois ont une
     * cause que l'utilisateur peut nommer. Un quatrième que le serveur posait
     * tout seul laissait quelqu'un devant une règle éteinte qu'il n'avait pas
     * éteinte.
     */
    fun evaluable(): Boolean =
        invalidReason == null && enabled && logic != null
}

/**
 * Les règles en RAM, avec les DEUX index que le document distingue.
 *
 * ## Pourquoi deux, et pas un
 *
 * **L'index chaud** ne contient que le sujet du DÉCLENCHEUR. Indexer aussi les
 * références de condition réveillerait des règles qui ne peuvent pas tirer :
 * « quand la température change, SI l'humidité est basse » ne doit rien faire
 * quand l'humidité bouge — c'est la température qui réveille.
 *
 * **L'index de maintenance** contient TOUTES les références — déclencheur,
 * condition, actions. Il ne sert jamais dans le chemin chaud : uniquement à
 * retrouver les règles touchées par une suppression, un changement de type ou
 * une bascule `automationVisible`.
 *
 * Les confondre coûterait des réveils inutiles à chaque trame, ou des règles
 * qu'on oublierait d'invalider. Les deux erreurs sont silencieuses.
 *
 * ## Concurrence : un instantané immuable derrière un champ @Volatile
 *
 * Lu par chaque coroutine de lecture d'appareil (le portail `watches`, une
 * fois par trame), écrit par les mutations de règles. Les écrivains
 * reconstruisent un [Index] complet et échangent la référence ; les lecteurs
 * voient l'ancien monde ou le nouveau, jamais un monde déchiré.
 */
class RuleCache(
    private val resolver: RuleValidation.Resolver
) {

    class Index(
        /** Le sujet du DÉCLENCHEUR seulement — le chemin chaud. */
        val byTriggerSubject: Map<SubjectKey, List<LoadedRule>>,
        /** TOUTES les références — la maintenance, jamais le chemin chaud. */
        val byAnySignal: Map<String, List<LoadedRule>>,
        val byId: Map<String, LoadedRule>,
        /** Les règles horaires, que l'ordonnanceur réveille par identifiant. */
        val scheduleById: Map<String, LoadedRule>
    ) {
        val ruleCount: Int get() = byId.size
    }

    @Volatile
    var index: Index = Index(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        private set

    // ── Le chemin chaud ──────────────────────────────────────────────────

    /**
     * Le portail des producteurs : le relais ne publie que ce qu'une règle
     * regarde. Zéro règle = rien ne change dans le relais.
     */
    fun watches(ownerId: String, deviceId: String, address: Int): Boolean =
        index.byTriggerSubject.containsKey(SubjectKey.Signal(ownerId, deviceId, address))

    fun rulesTriggeredBy(key: SubjectKey): List<LoadedRule> =
        index.byTriggerSubject[key] ?: emptyList()

    fun byId(ruleId: String): LoadedRule? = index.byId[ruleId]

    fun scheduleRule(ruleId: String): LoadedRule? = index.scheduleById[ruleId]

    /** Les clés que le balayeur de silence doit surveiller. */
    fun watchedStaleKeys(): Set<SubjectKey.Signal> =
        index.byTriggerSubject.keys
            .filterIsInstance<SubjectKey.Signal>()
            .filterTo(mutableSetOf()) { key ->
                index.byTriggerSubject[key]?.any { it.logic?.trigger is Trigger.SignalStale } == true
            }

    // ── La maintenance ───────────────────────────────────────────────────

    /**
     * Les règles qui TOUCHENT ce signal, de quelque façon que ce soit.
     *
     * Le seul usage : retrouver ce qu'un changement de type, une suppression
     * ou une bascule `automationVisible` vient de casser. Jamais appelé par
     * trame.
     */
    fun rulesReferencing(signalKey: String): List<LoadedRule> =
        index.byAnySignal[signalKey] ?: emptyList()

    // ── Le chargement ────────────────────────────────────────────────────

    /**
     * Reconstruit tout depuis la base.
     *
     * Coût proportionnel au nombre de règles (des centaines), et les mutations
     * de règles arrivent à cadence humaine. Reconstruire entièrement plutôt
     * que corriger l'index en place : un index corrigé à la main finit
     * toujours par diverger de la base sur un chemin qu'on n'a pas prévu.
     */
    fun reload() {
        val byTrigger = HashMap<SubjectKey, MutableList<LoadedRule>>()
        val byAny = HashMap<String, MutableList<LoadedRule>>()
        val byId = HashMap<String, LoadedRule>()
        val schedule = HashMap<String, LoadedRule>()

        transaction {
            val states = AutomationStateTable.selectAll().associate { row ->
                row[AutomationStateTable.ruleId] to RuleState(
                    lastFiredAt = row[AutomationStateTable.lastFiredAt],
                    lastValue = row[AutomationStateTable.lastValueJson]?.let { decodeValue(it) },
                    dayStartedAt = row[AutomationStateTable.dayStartedAt],
                    evaluations = row[AutomationStateTable.evaluations],
                    fired = row[AutomationStateTable.fired],
                    conditionFalse = row[AutomationStateTable.conditionFalse],
                    conditionUnknown = row[AutomationStateTable.conditionUnknown]
                )
            }

            AutomationRuleTable.selectAll().forEach { row ->
                val id = row[AutomationRuleTable.id]
                val ownerId = row[AutomationRuleTable.ownerId]
                val schemaVersion = row[AutomationRuleTable.schemaVersion]

                // Une version inconnue n'est JAMAIS reinterpretee. La lecon de
                // `TwitAction` chez Blynk : une variante morte gravee dans le
                // JSON de chaque utilisateur pour toujours. On prefere une
                // regle marquee invalide et visible.
                var invalid: String? = row[AutomationRuleTable.invalidReason]
                var logic: RuleLogic? = null

                if (schemaVersion != RuleCodec.SCHEMA_VERSION) {
                    invalid = RuleCodec.unknownSchema(schemaVersion)
                } else {
                    when (val decoded = RuleCodec.decode(row[AutomationRuleTable.definition])) {
                        is RuleCodec.Outcome.Ok -> {
                            logic = decoded.logic
                            // La validite se RECALCULE au chargement : un
                            // signal supprime pendant que le serveur etait
                            // eteint doit se voir des le demarrage, pas au
                            // prochain enregistrement.
                            invalid = RuleValidation.check(decoded.logic, ownerId, resolver)?.code
                        }
                        is RuleCodec.Outcome.Invalid -> {
                            invalid = decoded.code
                            logger.error(
                                "Rule $id has an undecodable definition (${decoded.code}: " +
                                    "${decoded.detail}) — marked invalid, never evaluated"
                            )
                        }
                    }
                }

                val zone = runCatching { ZoneId.of(row[AutomationRuleTable.timeZoneId]) }
                    .getOrElse {
                        logger.error("Rule $id has an unknown timezone — falling back to UTC")
                        ZoneId.of("UTC")
                    }

                val rule = LoadedRule(
                    id = id,
                    ownerId = ownerId,
                    name = row[AutomationRuleTable.name],
                    enabled = row[AutomationRuleTable.enabled],
                    severity = row[AutomationRuleTable.severity],
                    zone = zone,
                    invalidReason = invalid,
                    logic = logic,
                    state = states[id] ?: RuleState()
                )
                byId[id] = rule

                val l = logic ?: return@forEach

                // L'index chaud : le sujet du DECLENCHEUR, et rien d'autre.
                l.trigger.subjectKey(ownerId)?.let { key ->
                    byTrigger.getOrPut(key) { mutableListOf() }.add(rule)
                }
                if (l.trigger is Trigger.Schedule) schedule[id] = rule

                // L'index de maintenance : TOUTES les references.
                l.allSignalRefs().forEach { ref ->
                    byAny.getOrPut(ref.signalKey) { mutableListOf() }.add(rule)
                }
            }
        }

        index = Index(byTrigger, byAny, byId, schedule)
        logger.info("Rule cache reloaded — ${byId.size} rule(s), ${byTrigger.size} hot subject(s)")
    }

    // ── La persistance de l'etat ─────────────────────────────────────────

    /**
     * Écrit l'état d'une règle.
     *
     * `INSERT` après un `UPDATE` à zéro ligne plutôt qu'un `upsert` : la ligne
     * d'état naît au premier passage de la règle, et la faire naître à la
     * création de la règle demanderait que toute écriture de règle sache
     * qu'un état existe.
     */
    fun save(ruleId: String, state: RuleState, nowMs: Long) {
        transaction {
            val updated = AutomationStateTable.update({ AutomationStateTable.ruleId eq ruleId }) {
                it[lastFiredAt] = state.lastFiredAt
                it[lastValueJson] = state.lastValue?.let { v -> encodeValue(v) }
                it[dayStartedAt] = state.dayStartedAt
                it[evaluations] = state.evaluations
                it[fired] = state.fired
                it[conditionFalse] = state.conditionFalse
                it[conditionUnknown] = state.conditionUnknown
                it[updatedAt] = nowMs
            }
            if (updated == 0) {
                AutomationStateTable.insert {
                    it[AutomationStateTable.ruleId] = ruleId
                    it[lastFiredAt] = state.lastFiredAt
                    it[lastValueJson] = state.lastValue?.let { v -> encodeValue(v) }
                    it[dayStartedAt] = state.dayStartedAt
                    it[evaluations] = state.evaluations
                    it[fired] = state.fired
                    it[conditionFalse] = state.conditionFalse
                    it[conditionUnknown] = state.conditionUnknown
                    it[updatedAt] = nowMs
                }
            }
        }
    }

    companion object {
        /**
         * `{"type":"int","value":1}` — la même forme que dans une définition.
         *
         * La même et pas une autre : deux encodages d'une valeur typée dans le
         * même système finiraient par diverger sur le cas limite qu'aucun des
         * deux n'a prévu.
         */
        fun encodeValue(v: TypedValue): String = when (v) {
            is TypedValue.Int -> """{"type":"int","value":${v.value}}"""
            is TypedValue.Float -> """{"type":"float","value":${v.value}}"""
            is TypedValue.Text -> """{"type":"string","value":${
                kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.json.JsonPrimitive.serializer(),
                    kotlinx.serialization.json.JsonPrimitive(v.value)
                )
            }}"""
        }

        fun decodeValue(raw: String): TypedValue? = runCatching {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(raw)
                as kotlinx.serialization.json.JsonObject
            val p = o["value"] as kotlinx.serialization.json.JsonPrimitive
            when ((o["type"] as kotlinx.serialization.json.JsonPrimitive).content) {
                "int" -> TypedValue.Int(p.content.toLong())
                "float" -> TypedValue.Float(p.content.toDouble())
                "string" -> TypedValue.Text(p.content)
                else -> null
            }
        }.getOrNull()
    }
}
