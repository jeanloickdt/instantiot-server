/*
 * InstantIoT Server — self-hosted IoT relay for makers.
 * Copyright (C) 2026 Djoufack Tsobeng Jean Loick (InstantIoT)
 * Author: Djoufack Tsobeng Jean Loick (@jeanloick_dt)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

// automation/NtfyConfigRoutes.kt
package com.jeanloickdt.automation

import com.jeanloickdt.auth.domain.UserRepository
import com.jeanloickdt.auth.requireAdmin
import com.jeanloickdt.common.ApiError
import com.jeanloickdt.common.ServerConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class NtfyConfigResponse(
    val configured: Boolean,
    val server: String,
    val topic: String,
    /**
     * Le jeton n'est JAMAIS rendu, seulement sa presence.
     *
     * Meme regle que la cle Brevo, qui ne rend que ses quatre derniers
     * caracteres : un panneau d'administration qui reaffiche un secret le
     * met dans l'historique du navigateur, dans les captures d'ecran, et
     * dans le presse-papier de qui fait un copier-coller un peu large.
     */
    val hasToken: Boolean,
    val managedByEnv: Boolean
)

@Serializable
data class UpdateNtfyConfigRequest(
    val server: String? = null,
    val topic: String? = null,
    val token: String? = null
)

@Serializable
data class TestNtfyRequest(val topic: String? = null)

/**
 * Le panneau de ntfy : regler, et EPROUVER.
 *
 * ## Pourquoi le bouton d'essai existe
 *
 * Meme raison que pour l'e-mail : un sujet colle sans preuve est un ticket
 * de support differe. Ici c'est pire, parce que la panne est silencieuse par
 * nature — une notification qui n'arrive pas ne laisse aucune trace chez
 * celui qui l'attendait, et il ne s'en apercoit qu'a l'incident suivant,
 * celui qu'elle devait annoncer.
 *
 * Le bouton passe par le VRAI expediteur, celui qui livre en production.
 * Eprouver un chemin voisin ne prouverait que ce chemin-la.
 */
fun Route.ntfyConfigRoutes(
    userRepository: UserRepository,
    /** Le VRAI expediteur — le bouton d'essai doit parcourir le chemin de production. */
    sender: NtfyActionSender
) {
    authenticate("jwt") {

        get("/api/admin/ntfy-config") {
            call.requireAdmin(userRepository) ?: return@get
            call.respond(
                HttpStatusCode.OK,
                NtfyConfigResponse(
                    configured = ServerConfig.ntfyTopic.isNotBlank(),
                    server = ServerConfig.ntfyServer,
                    topic = ServerConfig.ntfyTopic,
                    hasToken = ServerConfig.ntfyToken.isNotBlank(),
                    managedByEnv = ServerConfig.ntfyManagedByEnv
                )
            )
        }

        patch("/api/admin/ntfy-config") {
            call.requireAdmin(userRepository) ?: return@patch
            if (ServerConfig.ntfyManagedByEnv) {
                // Une edition ecrasee au prochain redemarrage est pire qu'un
                // refus clair.
                return@patch call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("ntfy is managed by the server environment (NTFY_TOPIC)")
                )
            }
            val body = call.receive<UpdateNtfyConfigRequest>()

            // Une instance se joint en HTTP. Refuser ici evite une adresse
            // qui echouera a chaque livraison sans qu'on sache pourquoi.
            if (body.server != null && body.server.isNotBlank() &&
                !body.server.startsWith("http://") && !body.server.startsWith("https://")
            ) {
                return@patch call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("'server' must start with http:// or https://")
                )
            }
            // Un sujet est un segment d'URL. Une barre dedans viserait une
            // autre adresse que celle qu'on croit regler.
            if (body.topic != null && ("/" in body.topic || " " in body.topic)) {
                return@patch call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("'topic' must be a single path segment — no slash, no space")
                )
            }
            ServerConfig.saveNtfyConfig(body.server, body.topic, body.token)
            call.respond(HttpStatusCode.OK, mapOf("message" to "ntfy config saved"))
        }

        post("/api/admin/ntfy-config/test") {
            val admin = call.requireAdmin(userRepository) ?: return@post
            val probe = PendingAction(
                id = -1, idempotencyKey = "test", ownerId = admin.id, ruleId = null,
                type = DeliveryWorker.TYPE_PUSH,
                // Construite, jamais assemblee a la main : un titre portant un
                // guillemet produirait sinon une charge que l'expediteur ne
                // sait pas relire.
                payload = buildJsonObject {
                    put("title", "InstantIoT")
                    put("body", "La configuration ntfy fonctionne.")
                }.toString(),
                status = PendingAction.PENDING, attempts = 0, nextAttemptAt = 0, occurredAt = 0
            )
            when (val r = sender.send(probe)) {
                is SendResult.Ok -> call.respond(
                    HttpStatusCode.OK,
                    mapOf("message" to "Notification de test envoyee sur « ${ServerConfig.ntfyTopic} »")
                )
                is SendResult.Fatal -> call.respond(HttpStatusCode.BadRequest, ApiError(r.reason))
                is SendResult.Retry -> call.respond(
                    HttpStatusCode.BadGateway,
                    ApiError("ntfy injoignable : ${r.reason}")
                )
            }
        }
    }
}
