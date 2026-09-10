package com.jeanloickdt.automation.v2

import com.jeanloickdt.automation.data.RuleContinuationTable
import java.time.Instant
import java.time.ZoneId
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Une séquence arrêtée sur une attente, prête à reprendre.
 *
 * Tout ce dont la reprise a besoin voyage ICI, et rien n'est relu depuis la
 * règle : une règle modifiée pendant l'attente ne doit pas changer ce qui
 * était déjà décidé.
 */
data class Continuation(
    val id: Int,
    val ownerId: String,
    val ruleId: String,
    val dueAt: Long,
    val remaining: List<Action>,
    val offset: Int,
    val factTime: Long,
    val triggerValue: TypedValue?
)

/**
 * Le registre des attentes en vol.
 *
 * ## Pourquoi ce n'est jamais un `delay()`
 *
 * Un fil endormi perd son attente au premier redémarrage. « Allume, attends
 * une heure, éteins » deviendrait « allume » — et le chauffage resterait
 * allumé toute la nuit après un déploiement de 21 h. L'échéance est donc une
 * LIGNE, et la reprise une lecture.
 *
 * C'est aussi la machinerie qui manquait à `SignalStale` et à
 * `DeviceDisconnected(afterMs)` : « rien ne s'est passé pendant N » n'est pas
 * un événement, et aucun canal ne le publiera jamais. Construite une fois,
 * elle débloque les trois.
 */
interface ContinuationRepository {

    /**
     * Pose une attente. `false` si le compte a atteint sa borne.
     *
     * On REFUSE plutôt que de tronquer : une séquence à moitié exécutée est
     * pire que pas de séquence — « allume » sans « éteins » laisse le
     * chauffage allumé, et personne ne saurait pourquoi.
     */
    fun schedule(
        ownerId: String, ruleId: String, dueAt: Long,
        remaining: List<Action>, offset: Int, factTime: Long,
        triggerValue: TypedValue?, nowMs: Long
    ): Boolean

    /** Les attentes échues, les plus anciennes d'abord. */
    fun due(nowMs: Long, limit: Int): List<Continuation>

    /** Consommée : une reprise ne se rejoue pas. */
    fun delete(id: Int)

    /** Combien ce compte en a en vol — la borne de ressource. */
    fun countFor(ownerId: String): Int

    /**
     * Les attentes d'une règle supprimée ou réécrite.
     *
     * Réécrire une règle EFFACE ses attentes en vol : la séquence qui allait
     * reprendre n'est plus celle que l'utilisateur a sous les yeux, et la
     * laisser finir exécuterait une version qu'il vient de remplacer.
     */
    fun deleteForRule(ruleId: String)
}

class ExposedContinuationRepository(
    /**
     * La borne d'attentes en vol par compte.
     *
     * Une RESSOURCE, pas une politique : invisible, non réglable, et elle ne
     * s'achète pas. Une règle déclenchée chaque seconde avec une attente de
     * 24 h accumulerait 86 400 lignes — même famille que le fusible par carte,
     * et rien à voir avec ce que quelqu'un a payé.
     */
    private val maxInFlight: Int = MAX_IN_FLIGHT
) : ContinuationRepository {

    override fun schedule(
        ownerId: String, ruleId: String, dueAt: Long,
        remaining: List<Action>, offset: Int, factTime: Long,
        triggerValue: TypedValue?, nowMs: Long
    ): Boolean = transaction {
        if (countForIn(ownerId) >= maxInFlight) return@transaction false
        RuleContinuationTable.insert {
            it[RuleContinuationTable.ownerId] = ownerId
            it[RuleContinuationTable.ruleId] = ruleId
            it[RuleContinuationTable.dueAt] = dueAt
            it[RuleContinuationTable.remaining] = RuleCodec.encodeActions(remaining)
            it[RuleContinuationTable.offset] = offset
            it[RuleContinuationTable.factTime] = factTime
            it[triggerValueJson] = triggerValue?.let { v -> RuleCodec.encodeValue(v) }
            it[createdAt] = nowMs
        }
        true
    }

    override fun due(nowMs: Long, limit: Int): List<Continuation> = transaction {
        RuleContinuationTable.selectAll()
            .where { RuleContinuationTable.dueAt lessEq nowMs }
            .orderBy(RuleContinuationTable.dueAt)
            .limit(limit)
            .mapNotNull { row ->
                // Une ligne indechiffrable ne doit pas bloquer la file : on la
                // saute, elle sera reprise au tour suivant et journalisee la.
                val actions = RuleCodec.decodeActions(row[RuleContinuationTable.remaining])
                    ?: return@mapNotNull null
                Continuation(
                    id = row[RuleContinuationTable.id],
                    ownerId = row[RuleContinuationTable.ownerId],
                    ruleId = row[RuleContinuationTable.ruleId],
                    dueAt = row[RuleContinuationTable.dueAt],
                    remaining = actions,
                    offset = row[RuleContinuationTable.offset],
                    factTime = row[RuleContinuationTable.factTime],
                    triggerValue = row[RuleContinuationTable.triggerValueJson]
                        ?.let { RuleCodec.decodeValue(it) }
                )
            }
    }

    override fun delete(id: Int) {
        transaction { RuleContinuationTable.deleteWhere { RuleContinuationTable.id eq id } }
    }

    override fun countFor(ownerId: String): Int = transaction { countForIn(ownerId) }

    override fun deleteForRule(ruleId: String) {
        transaction {
            RuleContinuationTable.deleteWhere { RuleContinuationTable.ruleId eq ruleId }
        }
    }

    private fun countForIn(ownerId: String): Int =
        RuleContinuationTable.selectAll()
            .where { RuleContinuationTable.ownerId eq ownerId }
            .count().toInt()

    companion object {
        /** Voir [maxInFlight]. Un point de départ, sans donnée d'usage. */
        const val MAX_IN_FLIGHT = 200
    }
}

/**
 * Quand cette attente arrive à échéance.
 *
 * `Until` lit le fuseau de la RÈGLE, jamais celui du serveur : « jusqu'à 23 h »
 * veut dire 23 h chez celui qui a écrit la règle, comme partout ailleurs.
 *
 * Et quand l'heure est déjà passée, c'est demain. « Jusqu'à 23 h » écrit à
 * 23 h 30 ne peut pas vouloir dire « il y a trente minutes » : la seule lecture
 * défendable est la prochaine occurrence.
 */
fun dueAtOf(delay: Action.Delay, nowMs: Long, zone: ZoneId): Long = when (delay) {
    is Action.Delay.For -> nowMs + delay.seconds * 1000L
    is Action.Delay.Until -> {
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        val target = local.toLocalDate()
            .atStartOfDay(zone)
            .plusMinutes(delay.minuteOfDay.toLong())
        // `plusMinutes` sur un `ZonedDateTime` traverse l'heure d'ete : sur le
        // jour ou 2 h n'existe pas, 2 h 30 tombe a 3 h 30, et l'attente ne
        // disparait pas.
        val next = if (target.toInstant().toEpochMilli() > nowMs) target
                   else local.toLocalDate().plusDays(1)
                       .atStartOfDay(zone).plusMinutes(delay.minuteOfDay.toLong())
        next.toInstant().toEpochMilli()
    }
}
