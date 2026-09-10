package com.jeanloickdt.automation.v2

import com.jeanloickdt.automation.data.AutomationRunTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Le résultat d'un passage de règle, tel que la fiche l'affiche.
 *
 * Quatre issues, et `TESTED` n'est pas une commodité : sans elle, on
 * confondrait un essai manuel avec un vrai tir en relisant la trace, et la
 * ligne « tirée 2 fois » deviendrait fausse dès qu'on a appuyé sur le bouton.
 */
enum class RunOutcome {
    /** La règle a tiré : les actions sont écrites dans l'outbox. */
    FIRED,

    /** Évaluée, pas tirée — la condition, une carte hors ligne. */
    SKIPPED,

    /**
     * HISTORIQUE : le fusible a sauté, avant qu'il ne soit retiré.
     *
     * Plus rien ne l'écrit. La valeur reste parce que des lignes d'avant la
     * portent, et qu'une valeur qu'on cesse d'écrire n'est pas une valeur
     * qu'on peut cesser de lire : la retirer ferait échouer la lecture d'une
     * trace parfaitement valide.
     */
    MUTED,

    /** « Run actions now » — un essai, qui ne consomme aucun compteur. */
    TESTED
}

data class RuleRun(
    val id: Int,
    val ruleId: String,
    val atMs: Long,
    val outcome: RunOutcome,
    val reason: String?
)

/**
 * Les vingt derniers passages de chaque règle.
 *
 * ## Pourquoi vingt, et pourquoi un tampon
 *
 * Une ligne par évaluation n'est pas tenable : une règle sur un capteur à 1 Hz
 * produirait 86 400 lignes par jour, et le disque du VPS est la seule
 * ressource non bornée avant les quotas.
 *
 * Vingt lignes couvrent ce que la fiche montre et ce qu'un humain relit. Au
 * delà, la question n'est plus « qu'est-ce qui s'est passé » mais « quelle est
 * la tendance », et c'est aux compteurs du jour d'y répondre — eux ne coûtent
 * aucune ligne.
 *
 * ## La taille est bornée par CONSTRUCTION
 *
 * La purge se fait à l'insertion, dans la même transaction. Pas de tâche de
 * ménage périodique : une purge planifiée est une purge qui peut ne pas
 * tourner, et on découvrirait le problème en regardant le disque plein.
 */
class AutomationRuns(
    private val keepPerRule: Int = KEEP_PER_RULE
) {

    /**
     * Écrit un passage et rogne le surplus, en une transaction.
     *
     * L'appelant est déjà dans une transaction la plupart du temps — Exposed
     * les imbrique sans surcoût, et l'écrire ici garantit qu'aucun appelant ne
     * peut insérer sans purger.
     */
    fun record(
        ownerId: String,
        ruleId: String,
        atMs: Long,
        outcome: RunOutcome,
        reason: String? = null
    ) = transaction {
        AutomationRunTable.insert {
            it[AutomationRunTable.ownerId] = ownerId
            it[AutomationRunTable.ruleId] = ruleId
            it[at] = atMs
            it[AutomationRunTable.outcome] = outcome.name
            it[AutomationRunTable.reason] = reason?.take(REASON_MAX)
        }
        trim(ruleId)
    }

    /**
     * Les passages les plus récents d'abord — l'ordre de « LAST RUNS ».
     *
     * Le tri porte sur `id` et non sur `at` : deux passages de la même
     * milliseconde sont possibles (deux trames dans le même lot), et `at`
     * seul les rendrait dans un ordre arbitraire d'une lecture à l'autre.
     */
    fun list(
        ownerId: String,
        ruleId: String,
        limit: Int = KEEP_PER_RULE,
        /**
         * Le curseur : ne rendre que ce qui précède cet `id`.
         *
         * La pagination ne descendra jamais loin — le tampon fait vingt
         * lignes, un seul appel les couvre toutes. Elle existe pour que la
         * FORME de la réponse ne change pas le jour où on garde plus, pas
         * pour parcourir une profondeur qui n'existe pas.
         */
        before: Int? = null
    ): List<RuleRun> = transaction {
        AutomationRunTable.selectAll()
            .where {
                var w = (AutomationRunTable.ruleId eq ruleId) and (AutomationRunTable.ownerId eq ownerId)
                if (before != null) w = w and (AutomationRunTable.id less before)
                w
            }
            .orderBy(AutomationRunTable.id, SortOrder.DESC)
            .limit(limit.coerceIn(1, KEEP_PER_RULE))
            .map {
                RuleRun(
                    id = it[AutomationRunTable.id],
                    ruleId = it[AutomationRunTable.ruleId],
                    atMs = it[AutomationRunTable.at],
                    // Une issue inconnue — ecrite par une version plus recente,
                    // relue apres un retour arriere — tombe sur SKIPPED plutot
                    // que de faire echouer toute la lecture. La fiche affichera
                    // « ignore » au lieu d'un ecran vide.
                    outcome = runCatching {
                        RunOutcome.valueOf(it[AutomationRunTable.outcome])
                    }.getOrDefault(RunOutcome.SKIPPED),
                    reason = it[AutomationRunTable.reason]
                )
            }
    }

    /** Efface la trace d'une règle — à sa suppression. */
    fun deleteForRule(ruleId: String) = transaction {
        AutomationRunTable.deleteWhere { AutomationRunTable.ruleId eq ruleId }
    }

    /**
     * Supprime au-delà de la vingtième ligne de cette règle.
     *
     * On lit l'`id` de la vingtième et on supprime tout ce qui est en dessous,
     * plutôt que `DELETE ... ORDER BY ... OFFSET` que Postgres n'accepte pas
     * directement. Une lecture indexée et une suppression par plage : deux
     * requêtes qui ne grossissent pas avec le nombre de passages.
     */
    private fun trim(ruleId: String) {
        val cutoff = AutomationRunTable
            .select(AutomationRunTable.id)
            .where { AutomationRunTable.ruleId eq ruleId }
            .orderBy(AutomationRunTable.id, SortOrder.DESC)
            .limit(1).offset(keepPerRule.toLong())
            .map { it[AutomationRunTable.id] }
            .firstOrNull() ?: return

        AutomationRunTable.deleteWhere {
            (AutomationRunTable.ruleId eq ruleId) and (AutomationRunTable.id lessEq cutoff)
        }
    }

    companion object {
        const val KEEP_PER_RULE = 20

        /**
         * Le motif est une phrase courte, rendue telle quelle. Bornée ici
         * plutôt qu'à l'appel : un motif construit avec une valeur reçue de
         * l'extérieur ne doit pas pouvoir faire grossir la table.
         */
        const val REASON_MAX = 200
    }
}
