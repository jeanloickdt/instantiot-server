package com.jeanloickdt.automation

import com.jeanloickdt.automation.data.AutomationRuleTable
import com.jeanloickdt.automation.data.PendingActionTable
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Une notification livrée à un compte — vue COMME UN TIR, pas comme une ligne.
 *
 * `pending_actions` est une file, indexée par action : deux canaux d'une même
 * règle deviennent deux lignes. Pour un humain, c'est UN évènement — « l'alerte
 * gel de 03 h 12 est partie ». Regrouper ici, plutôt que dans chaque écran,
 * garantit que le compteur, le fil et un futur récapitulatif hebdomadaire ne
 * comptent pas trois fois la même chose.
 */
data class NotificationFire(
    val ruleId: String?,
    val ruleName: String?,
    val occurredAt: Long,
    /** INFO / WARNING / CRITICAL — copie du niveau de la regle au tir. */
    val severity: String,
    val channels: List<Delivery>
) {
    data class Delivery(
        val type: String, val status: String, val attempts: Int,
        /** Le mot que le canal a envoye. `null` quand il n'y en a pas (COMMAND). */
        val title: String? = null,
        val body: String? = null
    )
}

/**
 * Ce que le fil rend. `next` est le curseur de la page suivante — `null` quand
 * il n'y en a plus. Le format est privé au serveur : l'app le renvoie tel
 * quel, ne l'interprète jamais.
 */
data class NotificationPage(val fires: List<NotificationFire>, val next: String?)

interface NotificationRepository {
    /**
     * @param cursor renvoyé par la page précédente — un `occurredAt` en
     *   millisecondes sous forme de chaîne. `null` pour la première page.
     * @param limit nombre maximum de TIRS (pas d'actions) rendus par appel.
     */
    fun list(ownerId: String, cursor: String?, limit: Int): NotificationPage

    /**
     * Retire des tirs du fil. Rend combien de LIGNES ont disparu.
     *
     * Les clés sont celles du fil — `(règle, instant)`, `ruleId` nul pour un
     * tir système. Un tir porte plusieurs canaux et ils partent ensemble : le
     * push et le courriel d'une même alerte sont une seule ligne à l'écran, et
     * n'en retirer qu'un laisserait un demi-tir qui ne veut rien dire.
     *
     * `ownerId` porte la restriction, comme partout : on ne supprime jamais la
     * ligne de quelqu'un d'autre, même en connaissant sa clé.
     */
    fun delete(ownerId: String, keys: List<Pair<String?, Long>>): Int
}

class ExposedNotificationRepository : NotificationRepository {

    override fun delete(ownerId: String, keys: List<Pair<String?, Long>>): Int = transaction {
        // UNE transaction pour tout le lot, et une instruction par clé.
        //
        // Un `IN` sur des couples se dirait en SQL mais pas dans le DSL sans
        // fabriquer du texte, et vingt petites suppressions dans une seule
        // transaction coutent moins qu'une requete construite a la main sur
        // des valeurs venues du client.
        keys.sumOf { (ruleId, occurredAt): Pair<String?, Long> ->
            PendingActionTable.deleteWhere {
                (PendingActionTable.ownerId eq ownerId) and
                    (PendingActionTable.occurredAt eq occurredAt) and
                    // `eq null` ne s'ecrit pas comme une egalite : SQL dit
                    // `IS NULL`, et une egalite avec NULL est fausse pour
                    // tout, y compris NULL. Sans cette branche, un tir systeme
                    // ne se supprimerait jamais et le bouton mentirait.
                    //
                    // Exposed rend justement `IS NULL` quand on lui passe
                    // `null` a `eq` sur une colonne nullable — c'est le seul
                    // endroit du depot ou on s'appuie dessus, d'ou la note.
                    (PendingActionTable.ruleId eq ruleId)
            }
        }
    }

    override fun list(ownerId: String, cursor: String?, limit: Int): NotificationPage {
        require(limit in 1..200) { "limit hors bornes : $limit" }
        val before = cursor?.toLongOrNull()

        return transaction {
            // 1) Les LIGNES. On demande une marge — un tir portant ses trois
            //    canaux compte pour trois lignes, plus un débordement possible.
            val take = (limit + 1) * 4
            val rows = PendingActionTable.selectAll()
                .where {
                    // SEULEMENT les PUSH.
                    //
                    // Le fil listait toute la file : envoyer un email par
                    // automatisation faisait donc apparaitre une notification
                    // en plus, et une commande vers une carte aussi. On y
                    // lisait « il s'est passe quelque chose » la ou il ne
                    // s'etait rien passe QUI NOUS CONCERNE : l'email etait
                    // parti, c'est tout, et c'etait justement ce qu'on avait
                    // demande.
                    //
                    // Ce fil porte ce que l'utilisateur a demande de RECEVOIR
                    // — l'action « envoyer une notification » — et ce que le
                    // systeme a besoin de dire, qui emprunte le meme canal
                    // avec `ruleId = null`. Ce qu'une regle fait ailleurs se
                    // lit sur sa fiche, pas ici.
                    val mine = (PendingActionTable.ownerId eq ownerId) and
                        (PendingActionTable.type eq DeliveryWorker.TYPE_PUSH)
                    if (before == null) mine
                    else mine and (PendingActionTable.occurredAt less before)
                }
                .orderBy(PendingActionTable.occurredAt to SortOrder.DESC)
                .limit(take)
                .map {
                    Row(
                        ruleId     = it[PendingActionTable.ruleId],
                        occurredAt = it[PendingActionTable.occurredAt],
                        type       = it[PendingActionTable.type],
                        status     = it[PendingActionTable.status],
                        attempts   = it[PendingActionTable.attempts],
                        payload    = it[PendingActionTable.payload],
                        severity   = it[PendingActionTable.severity]
                    )
                }

            // 2) Les NOMS des règles, une seule requête par page — on n'itère
            //    pas les règles inconnues, et on rend `null` pour celles qui
            //    ont été supprimées entre le tir et la lecture.
            val ruleIds = rows.mapNotNull { it.ruleId }.toSet()
            val names: Map<String, String> = if (ruleIds.isEmpty()) emptyMap()
            else AutomationRuleTable.selectAll()
                .where { AutomationRuleTable.id inList ruleIds }
                .associate { it[AutomationRuleTable.id] to it[AutomationRuleTable.name] }

            // 3) Le REGROUPEMENT — deux canaux d'un même tir deviennent une ligne.
            val fires = rows
                .groupBy { it.ruleId to it.occurredAt }
                .map { (key, group) ->
                    NotificationFire(
                        ruleId    = key.first,
                        ruleName  = key.first?.let { names[it] },
                        occurredAt= key.second,
                        severity  = group.first().severity,
                        channels  = group.map { r ->
                            val (title, body) = previewOf(r.type, r.payload)
                            NotificationFire.Delivery(r.type, r.status, r.attempts, title, body)
                        }
                    )
                }
                .sortedByDescending { it.occurredAt }

            val page = fires.take(limit)
            val next = if (fires.size > limit) page.last().occurredAt.toString() else null
            NotificationPage(page, next)
        }
    }

    private data class Row(
        val ruleId: String?, val occurredAt: Long,
        val type: String, val status: String, val attempts: Int,
        val payload: String, val severity: String
    )
}

/**
 * Ce que la carte doit MONTRER, extrait du payload propre au canal.
 *
 * Chaque canal a sa forme :
 *   PUSH    → {"title","body"}
 *   EMAIL   → {"subject","body"}
 *   COMMAND → une trame, aucun mot humain
 *
 * Un payload cabosse renvoie (null, null) — l'ecran affiche alors le nom de
 * la regle seul, ce qui reste utile.
 */
private fun previewOf(type: String, payload: String): Pair<String?, String?> = try {
    val json = kotlinx.serialization.json.Json.parseToJsonElement(payload)
        .let { it as? kotlinx.serialization.json.JsonObject } ?: return Pair(null, null)
    fun s(key: String) = (json[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    when (type) {
        "PUSH" -> s("title") to s("body")
        "EMAIL"   -> s("subject") to s("body")
        "COMMAND" -> null to null
        else      -> null to null
    }
} catch (e: Exception) {
    null to null
}

private infix fun org.jetbrains.exposed.sql.Column<String>.inList(
    values: Collection<String>
): org.jetbrains.exposed.sql.Op<Boolean> =
    org.jetbrains.exposed.sql.SqlExpressionBuilder.run { this@inList inList values }
