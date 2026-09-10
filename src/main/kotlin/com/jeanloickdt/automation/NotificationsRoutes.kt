package com.jeanloickdt.automation

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.request.receive
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * `GET /api/notifications` — le fil de ce que les automatisations ont fait.
 *
 * Une ligne = un TIR (une règle × un instant). Voir [NotificationRepository]
 * pour pourquoi on regroupe ici plutôt que dans chaque client.
 *
 * Trois paramètres : `cursor` (opaque, rendu par la page précédente) et
 * `limit` (borné 1–100, défaut 50). L'ordre est décroissant — le plus récent
 * en tête, ce que l'écran affiche.
 *
 * `DELETE /api/notifications` retire des tirs du fil, EN LOT — voir plus bas.
 */
fun Route.notificationsRoutes(repo: NotificationRepository) {
    authenticate("jwt") {
        get("/api/notifications") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val cursor = call.request.queryParameters["cursor"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
            if (limit !in 1..MAX_LIMIT) {
                return@get call.respond(HttpStatusCode.BadRequest, "limit hors bornes")
            }
            val page = repo.list(ownerId, cursor, limit)
            call.respond(HttpStatusCode.OK, page.toResponse())
        }

        /**
         * Retirer des tirs du fil.
         *
         * ## En LOT, et pas un par un
         *
         * L'écran fait sélectionner puis supprimer : un utilisateur qui coche
         * vingt lignes ferait vingt appels, dont certains échoueraient — et il
         * faudrait alors décider quoi montrer d'une suppression à moitié
         * faite. Un appel, une transaction, tout ou rien.
         *
         * ## La clé est celle du FIL, pas celle des lignes
         *
         * Un tir est `(règle, instant)` et porte plusieurs canaux : le push
         * ET le courriel d'une même alerte sont une seule ligne à l'écran.
         * Supprimer par identifiant de ligne laisserait la moitié d'un tir en
         * place, ce qui ne veut rien dire pour qui regarde.
         *
         * `ruleId` nul est un tir SYSTÈME — un plafond de sortie atteint. Il
         * se supprime comme les autres, et sa clé est son seul instant.
         *
         * ## Ce que ça supprime vraiment
         *
         * Les lignes de `pending_actions`. Un tir encore `PENDING` ne partira
         * donc pas : retirer de son fil une alerte qu'on n'a pas encore reçue
         * revient à dire « je n'en veux pas », et la livrer ensuite
         * contredirait le geste.
         */
        delete("/api/notifications") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)

            val body = runCatching { call.receive<DeleteNotificationsRequest>() }.getOrNull()
                ?: return@delete call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "corps illisible")
                )
            if (body.fires.isEmpty()) {
                return@delete call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "aucun tir a supprimer")
                )
            }
            if (body.fires.size > MAX_DELETE) {
                return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "au plus $MAX_DELETE tirs par appel")
                )
            }

            val removed = repo.delete(ownerId, body.fires.map { it.ruleId to it.occurredAt })
            call.respond(HttpStatusCode.OK, mapOf("deleted" to removed))
        }
    }
}

/**
 * Les tirs à retirer, par leur clé de FIL.
 *
 * Une borne sur la taille du lot : c'est une requête écrite par un client,
 * et une liste de dix mille entrées serait une transaction qu'on tient pour
 * quelqu'un qui n'a pas dix mille notifications à l'écran.
 */
@Serializable
data class DeleteNotificationsRequest(val fires: List<FireKey>) {
    @Serializable
    data class FireKey(
        /** Nul pour un tir système — un plafond de sortie atteint. */
        val ruleId: String? = null,
        val occurredAt: Long
    )
}

private const val MAX_DELETE = 200

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 100

@Serializable
private data class NotificationChannelResponse(
    val type: String, val status: String, val attempts: Int,
    val title: String? = null,
    val body: String? = null
)

@Serializable
private data class NotificationFireResponse(
    val ruleId: String?, val ruleName: String?,
    val occurredAt: Long,
    val severity: String,
    val channels: List<NotificationChannelResponse>
)

@Serializable
private data class NotificationPageResponse(
    val fires: List<NotificationFireResponse>, val next: String?
)

private fun NotificationPage.toResponse() = NotificationPageResponse(
    fires = fires.map { fire ->
        NotificationFireResponse(
            ruleId = fire.ruleId, ruleName = fire.ruleName,
            occurredAt = fire.occurredAt,
            severity = fire.severity,
            channels = fire.channels.map { NotificationChannelResponse(it.type, it.status, it.attempts, it.title, it.body) }
        )
    },
    next = next
)
