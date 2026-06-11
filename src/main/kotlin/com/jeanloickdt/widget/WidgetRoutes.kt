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

// widget/WidgetRoutes.kt
package com.jeanloickdt.widget

import com.jeanloickdt.common.ApiError

import com.jeanloickdt.project.domain.ProjectRepository
import com.jeanloickdt.relay.LastValueCache
import com.jeanloickdt.widget.domain.BulkRegisterWidgetsRequest
import com.jeanloickdt.widget.domain.BulkRegisterWidgetsResponse
import com.jeanloickdt.widget.domain.RegisterWidgetRequest
import com.jeanloickdt.widget.domain.RegisterWidgetResponse
import com.jeanloickdt.widget.domain.WidgetHistoryAggregateRepository
import com.jeanloickdt.widget.domain.WidgetHistoryNumericRepository
import com.jeanloickdt.widget.domain.WidgetHistoryEnvelope
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
    projectRepository: ProjectRepository,
    widgetHistoryRepository: WidgetHistoryRepository,
    widgetHistoryNumericRepository: WidgetHistoryNumericRepository,
    widgetHistoryMinRepository: WidgetHistoryAggregateRepository,
    widgetHistoryHourRepository: WidgetHistoryAggregateRepository,
    widgetHistoryDayRepository: WidgetHistoryAggregateRepository,
    lastValues: LastValueCache
) {

    authenticate("jwt") {

        // ============================================================
        // POST /api/projects/{projectId}/widgets — register a widget (idempotent)
        // The `id` must be the `protocolId` (the one the device uses
        // in its iWidgets v1 frames).
        // ============================================================
        post("/api/projects/{projectId}/widgets") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val projectId = call.parameters["projectId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Missing projectId"))

            // Same ownership pattern as everywhere else: the {projectId} in the
            // URL must be ours, else 404. Without this a user could register a
            // widget claiming another user's project. (Not the leak fix — the
            // relay auto-registers too — but the one-pattern consistency gate.)
            val project = projectRepository.findById(projectId)
            if (project == null || project.ownerId != ownerId) {
                return@post call.respond(HttpStatusCode.NotFound, ApiError("Project not found"))
            }

            val body = call.receive<RegisterWidgetRequest>()

            // The cache-aware repo keeps knownWidgetIds in sync (composition root).
            val created = widgetRepository.registerIfAbsent(
                id        = body.id,
                projectId = projectId,
                ownerId   = ownerId,
                type      = body.type
            )

            call.respond(
                if (created) HttpStatusCode.Created else HttpStatusCode.OK,
                RegisterWidgetResponse(
                    message = if (created) "Widget registered" else "Widget already registered",
                    id      = body.id,
                    type    = body.type,
                    created = created
                )
            )
        }

        // ============================================================
        // POST /api/projects/{projectId}/widgets/bulk — bulk register
        // Called by the app after each layout save to ensure that
        // all widgets of the project are known. Idempotent.
        // ============================================================
        post("/api/projects/{projectId}/widgets/bulk") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val projectId = call.parameters["projectId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Missing projectId"))

            // Project ownership gate — same as the single-register route above.
            val project = projectRepository.findById(projectId)
            if (project == null || project.ownerId != ownerId) {
                return@post call.respond(HttpStatusCode.NotFound, ApiError("Project not found"))
            }

            val body = call.receive<BulkRegisterWidgetsRequest>()

            var created = 0
            var existing = 0
            body.widgets.forEach { w ->
                if (w.id.isBlank()) return@forEach
                val inserted = widgetRepository.registerIfAbsent(
                    id        = w.id,
                    projectId = projectId,
                    ownerId   = ownerId,
                    type      = w.type
                )
                if (inserted) {
                    created++   // knownWidgetIds maintained by the cache-aware repo
                } else {
                    existing++
                }
            }

            call.respond(HttpStatusCode.OK, BulkRegisterWidgetsResponse(
                created  = created,
                existing = existing
            ))
        }

        // ============================================================
        // DELETE /api/widgets/{id} — delete widget + history (opaque + numeric)
        // ============================================================
        delete("/api/widgets/{id}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)

            val widgetId = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("Missing id"))

            // findById is owner-scoped (composite PK) → a null means either no
            // such widget or it belongs to another owner: 404 either way (never
            // reveal another owner's widget).
            val widget = widgetRepository.findById(ownerId, widgetId)

            if (widget == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("Widget not found"))
                return@delete
            }

            // delete history first — cascade across all tiers, scoped to this
            // owner so a colliding widgetId owned by another user is untouched.
            widgetHistoryRepository.deleteAllByWidget(ownerId, widgetId)
            widgetHistoryNumericRepository.deleteAllByWidget(ownerId, widgetId)
            widgetHistoryMinRepository.deleteAllByWidget(ownerId, widgetId)
            widgetHistoryHourRepository.deleteAllByWidget(ownerId, widgetId)
            widgetHistoryDayRepository.deleteAllByWidget(ownerId, widgetId)
            // delete() goes through the cache-aware repo, which purges
            // knownWidgetIds + lastValues for this key — same path the project
            // cascade now uses, so no delete path can leave phantom cache keys.
            widgetRepository.delete(ownerId, widgetId)

            call.respond(HttpStatusCode.OK, mapOf(
                "message" to "Widget deleted",
                "id"      to widgetId
            ))
        }

        // ============================================================
        // GET /api/projects/{id}/states — latest payloads
        // Called on app reconnection to display the latest values
        // ============================================================
        get("/api/projects/{id}/states") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val projectId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Missing id"))

            val states = widgetRepository.findAllByProject(projectId)
                .filter { it.ownerId == ownerId } // isolation
                .map {
                    // read-through: the RAM cache is fresher than the DB column
                    // (which is coalesced every 5s); DB is the cold-start fallback
                    val cached = lastValues.get(it.ownerId, it.id)
                    WidgetStateResponse(
                        widgetId   = it.id,
                        payload    = cached?.payload ?: it.lastPayload,
                        lastSeenAt = cached?.at ?: it.lastSeenAt
                    )
                }

            call.respond(HttpStatusCode.OK, states)
        }

        // ============================================================
        // GET /api/widgets/{id}/history?from=&to=&seriesId=&granularity=
        //
        // Granularity (default = "raw"):
        //   raw   : raw samples (max 1 / 5s per widget+series)
        //   min   : 1-minute buckets    (downsampled, with yMin/yMax/count)
        //   hour  : 1-hour buckets
        //   day   : 1-day buckets
        //
        // Retention per tier (config in ~/.instantiot/server.properties):
        //   raw=7d · min=90d · hour=365d · day=infinite
        // ============================================================
        get("/api/widgets/{id}/history") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val widgetId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Missing id"))

            val from = call.parameters["from"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Missing from"))

            val to = call.parameters["to"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Missing to"))

            val seriesId = call.parameters["seriesId"]?.takeIf { it.isNotBlank() }
            val granularity = (call.parameters["granularity"] ?: "raw").lowercase()

            // owner-scoped resolve: null = unknown or another owner's → 404
            val widget = widgetRepository.findById(ownerId, widgetId)

            if (widget == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("Widget not found"))
                return@get
            }

            val points: List<WidgetHistoryPointResponse> = when (granularity) {
                "raw" -> widgetHistoryNumericRepository
                    .findByWidgetAndRange(widgetId, ownerId, from, to, seriesId)
                    .map {
                        WidgetHistoryPointResponse(
                            t        = it.recordedAt,
                            y        = it.value,
                            seriesId = it.seriesId
                        )
                    }
                "min", "hour", "day" -> {
                    val repo = when (granularity) {
                        "min"  -> widgetHistoryMinRepository
                        "hour" -> widgetHistoryHourRepository
                        else   -> widgetHistoryDayRepository
                    }
                    repo.findByWidgetAndRange(widgetId, ownerId, from, to, seriesId).map {
                        WidgetHistoryPointResponse(
                            t        = it.bucketAt,
                            y        = it.avgValue,
                            seriesId = it.seriesId,
                            yMin     = it.minValue,
                            yMax     = it.maxValue,
                            count    = it.sampleCount
                        )
                    }
                }
                else -> {
                    call.respond(HttpStatusCode.BadRequest, ApiError(
                        "Invalid granularity (use raw|min|hour|day)"
                    ))
                    return@get
                }
            }

            // Captured after the query: reflects the moment when the server
            // is about to respond. The app uses this to
            // correct the app↔server clock skew (see AdvancedChart
            // live append).
            val serverTimeMs = System.currentTimeMillis()
            call.respond(HttpStatusCode.OK, WidgetHistoryEnvelope(serverTimeMs, points))
        }

        // ============================================================
        // GET /api/widgets/{id}/history-raw?from=&to= — opaque history
        //
        // Legacy route returning the raw Base64 payload. Kept for
        // non-numeric widgets (buttons, segswitch, dpad) and
        // clients that want to decode it themselves.
        // ============================================================
        get("/api/widgets/{id}/history-raw") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val widgetId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Missing id"))

            val from = call.parameters["from"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Missing from"))

            val to = call.parameters["to"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Missing to"))

            // owner-scoped resolve: null = unknown or another owner's → 404
            val widget = widgetRepository.findById(ownerId, widgetId)

            if (widget == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("Widget not found"))
                return@get
            }

            val history = widgetHistoryRepository.findByWidgetAndRange(widgetId, ownerId, from, to)
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