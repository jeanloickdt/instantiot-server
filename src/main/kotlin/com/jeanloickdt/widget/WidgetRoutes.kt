// widget/WidgetRoutes.kt
package com.jeanloickdt.widget

import com.jeanloickdt.widget.domain.RegisterWidgetRequest
import com.jeanloickdt.widget.domain.WidgetHistoryNumericRepository
import com.jeanloickdt.widget.domain.WidgetHistoryPointResponse
import com.jeanloickdt.widget.domain.WidgetHistoryRepository
import com.jeanloickdt.widget.domain.WidgetHistoryResponse
import com.jeanloickdt.widget.domain.WidgetRepository
import com.jeanloickdt.widget.domain.WidgetStateResponse
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.widgetRoutes(
    widgetRepository: WidgetRepository,
    widgetHistoryRepository: WidgetHistoryRepository,
    widgetHistoryNumericRepository: WidgetHistoryNumericRepository
) {

    authenticate("jwt") {

        // ============================================================
        // POST /api/projects/{projectId}/widgets — enregistrer un widget
        // L'app génère l'id — doit correspondre au widget dans layoutJson
        // Le server stocke id + type uniquement
        // ============================================================
        post("/api/projects/{projectId}/widgets") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val projectId = call.parameters["projectId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))

            val body = call.receive<RegisterWidgetRequest>()

            widgetRepository.register(
                id        = body.id,
                projectId = projectId,
                ownerId   = ownerId,
                type      = body.type
            )

            call.respond(HttpStatusCode.Created, mapOf(
                "message"  to "Widget registered",
                "id"       to body.id,
                "type"     to body.type
            ))
        }

        // ============================================================
        // DELETE /api/widgets/{id} — supprimer widget + history (opaque + numérique)
        // ============================================================
        delete("/api/widgets/{id}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)

            val widgetId = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

            val widget = widgetRepository.findById(widgetId)

            if (widget == null || widget.ownerId != ownerId) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Widget not found"))
                return@delete
            }

            // supprimer history d'abord — cascade
            widgetHistoryRepository.deleteAllByWidget(widgetId)
            widgetHistoryNumericRepository.deleteAllByWidget(widgetId)
            widgetRepository.delete(widgetId)

            call.respond(HttpStatusCode.OK, mapOf(
                "message" to "Widget deleted",
                "id"      to widgetId
            ))
        }

        // ============================================================
        // GET /api/projects/{id}/states — derniers payloads
        // Appelé à la reconnexion app pour afficher les dernières valeurs
        // ============================================================
        get("/api/projects/{id}/states") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val projectId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

            val states = widgetRepository.findAllByProject(projectId)
                .filter { it.ownerId == ownerId } // isolation
                .map {
                    WidgetStateResponse(
                        widgetId   = it.id,
                        payload    = it.lastPayload,
                        lastSeenAt = it.lastSeenAt
                    )
                }

            call.respond(HttpStatusCode.OK, states)
        }

        // ============================================================
        // GET /api/widgets/{id}/history?from=&to=&seriesId= — points numériques
        //
        // Retourne l'historique décodé (t, y, seriesId?) prêt à hydrater
        // un chart/gauge/metric/level/slider côté app. `seriesId` est
        // optionnel — sans, toutes les séries du widget sont retournées
        // triées par timestamp.
        //
        // Fenêtre de rétention par défaut : 7 jours (history.retention.raw.days
        // dans ~/.instantiot/server.properties).
        // ============================================================
        get("/api/widgets/{id}/history") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val widgetId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

            val from = call.parameters["from"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing from"))

            val to = call.parameters["to"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing to"))

            val seriesId = call.parameters["seriesId"]?.takeIf { it.isNotBlank() }

            val widget = widgetRepository.findById(widgetId)

            if (widget == null || widget.ownerId != ownerId) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Widget not found"))
                return@get
            }

            val points = widgetHistoryNumericRepository
                .findByWidgetAndRange(widgetId, from, to, seriesId)
                .map {
                    WidgetHistoryPointResponse(
                        t        = it.recordedAt,
                        y        = it.value,
                        seriesId = it.seriesId
                    )
                }

            call.respond(HttpStatusCode.OK, points)
        }

        // ============================================================
        // GET /api/widgets/{id}/history-raw?from=&to= — historique opaque
        //
        // Ancienne route retournant le payload Base64 brut. Conservée pour
        // les widgets non-numériques (boutons, segswitch, dpad) et les
        // clients qui veulent décoder eux-mêmes.
        // ============================================================
        get("/api/widgets/{id}/history-raw") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val widgetId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

            val from = call.parameters["from"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing from"))

            val to = call.parameters["to"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing to"))

            val widget = widgetRepository.findById(widgetId)

            if (widget == null || widget.ownerId != ownerId) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Widget not found"))
                return@get
            }

            val history = widgetHistoryRepository.findByWidgetAndRange(widgetId, from, to)
                .map {
                    WidgetHistoryResponse(
                        payload    = it.payload,
                        recordedAt = it.recordedAt
                    )
                }

            call.respond(HttpStatusCode.OK, history)
        }
    }
}
