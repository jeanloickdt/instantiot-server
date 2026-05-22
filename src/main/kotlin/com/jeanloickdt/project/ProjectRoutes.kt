/*
 * InstantIoT Server — self-hosted IoT relay for makers.
 * Copyright (C) 2026 InstantIoT
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

// project/ProjectRoutes.kt
package com.jeanloickdt.project

import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.project.domain.CreateProjectRequest
import com.jeanloickdt.project.domain.ProjectRepository
import com.jeanloickdt.project.domain.ProjectResponse
import com.jeanloickdt.project.domain.UpdateProjectLayoutRequest
import com.jeanloickdt.project.domain.UpdateProjectNameRequest
import com.jeanloickdt.relay.ControlEventBroadcaster
import com.jeanloickdt.relay.DeviceOfflineReason
import com.jeanloickdt.relay.SessionRegistry
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import com.jeanloickdt.widget.domain.WidgetHistoryRepository
import com.jeanloickdt.widget.domain.WidgetRepository
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.projectRoutes(
    projectRepository: ProjectRepository,
    deviceRepository: DeviceRepository,
    widgetRepository: WidgetRepository,
    widgetHistoryRepository: WidgetHistoryRepository,
    widgetHistoryNumericRepository: com.jeanloickdt.widget.domain.WidgetHistoryNumericRepository,
    widgetHistoryMinRepository: com.jeanloickdt.widget.domain.WidgetHistoryAggregateRepository,
    widgetHistoryHourRepository: com.jeanloickdt.widget.domain.WidgetHistoryAggregateRepository,
    widgetHistoryDayRepository: com.jeanloickdt.widget.domain.WidgetHistoryAggregateRepository
) {

    authenticate("jwt") {

        // ============================================================
        // GET /api/projects — list projects of the authenticated user
        // ============================================================
        get("/api/projects") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val projects = projectRepository.findAllByOwner(ownerId)
                .map {
                    ProjectResponse(
                        id         = it.id,
                        name       = it.name,
                        layoutJson = it.layoutJson,
                        createdAt  = it.createdAt,
                        updatedAt  = it.updatedAt
                    )
                }
            call.respond(HttpStatusCode.OK, projects)
        }

        // ============================================================
        // POST /api/projects — create a project
        // ============================================================
        post("/api/projects") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val body    = call.receive<CreateProjectRequest>()
            val id      = projectRepository.create(body.name, ownerId)
            val project = projectRepository.findById(id)!!

            call.respond(HttpStatusCode.Created, ProjectResponse(
                id         = project.id,
                name       = project.name,
                layoutJson = project.layoutJson,
                createdAt  = project.createdAt,
                updatedAt  = project.updatedAt
            ))
        }

        // ============================================================
        // GET /api/projects/{id} — project details + full layoutJson
        // ============================================================
        get("/api/projects/{id}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val projectId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

            val project = projectRepository.findById(projectId)

            if (project == null || project.ownerId != ownerId) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
                return@get
            }

            call.respond(HttpStatusCode.OK, ProjectResponse(
                id         = project.id,
                name       = project.name,
                layoutJson = project.layoutJson,
                createdAt  = project.createdAt,
                updatedAt  = project.updatedAt
            ))
        }

        // ============================================================
        // PATCH /api/projects/{id}/name — rename a project
        // ============================================================
        patch("/api/projects/{id}/name") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@patch call.respond(HttpStatusCode.Unauthorized)

            val projectId = call.parameters["id"]
                ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

            val project = projectRepository.findById(projectId)

            if (project == null || project.ownerId != ownerId) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
                return@patch
            }

            val body = call.receive<UpdateProjectNameRequest>()
            projectRepository.updateName(projectId, body.name)
            val updated = projectRepository.findById(projectId)!!

            call.respond(HttpStatusCode.OK, ProjectResponse(
                id         = updated.id,
                name       = updated.name,
                layoutJson = updated.layoutJson,
                createdAt  = updated.createdAt,
                updatedAt  = updated.updatedAt
            ))
        }

        // ============================================================
        // PATCH /api/projects/{id}/layout — sync full layout
        // Called with debounce from the app after each modification
        // The server stores layoutJson as an opaque blob — does not interpret it
        // ============================================================
        patch("/api/projects/{id}/layout") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@patch call.respond(HttpStatusCode.Unauthorized)

            val projectId = call.parameters["id"]
                ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

            val project = projectRepository.findById(projectId)

            if (project == null || project.ownerId != ownerId) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
                return@patch
            }

            val body = call.receive<UpdateProjectLayoutRequest>()
            projectRepository.updateLayout(projectId, body.layoutJson)

            call.respond(HttpStatusCode.OK, mapOf(
                "message"   to "Layout updated",
                "projectId" to projectId
            ))
        }

        // ============================================================
        // DELETE /api/projects/{id} — delete project + cascade widgets
        // ============================================================
        delete("/api/projects/{id}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)

            val projectId = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

            val project = projectRepository.findById(projectId)

            if (project == null || project.ownerId != ownerId) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
                return@delete
            }

            // ─── Step 1 : kick devices still connected over TCP ───────
            // Before the cascade delete, we fetch the list of devices
            // for the project and for each one we:
            //   1. broadcast device_offline (reason=deleted) — informs
            //      the apps watching the project (dashboard open)
            //   2. close the TCP socket if it is active — otherwise the
            //      ESP device stays a ghost on the server side, shown
            //      "online" even though the project is deleted
            // Same pattern as DELETE /api/devices/{id}.
            val devicesToKick = deviceRepository.findAllByProject(projectId)
            devicesToKick.forEach { d ->
                ControlEventBroadcaster.deviceOffline(
                    projectId = projectId,
                    deviceId  = d.id,
                    reason    = DeviceOfflineReason.DELETED
                )
                SessionRegistry.getDeviceSession(d.id)?.let { active ->
                    try {
                        active.socket.close()
                    } catch (_: Exception) {
                        // socket already closed or I/O error — does not matter
                    }
                    // the finally block of handleDeviceConnection removes
                    // the session from SessionRegistry automatically
                }
            }

            // ─── Step 2 : close the WS app-sessions of this project ──
            // The apps that had this dashboard open (phone2, tablet,
            // another device of the same user…) stayed on a WS that
            // handshook a defunct projectId. We close them cleanly
            // with a CloseReason.NORMAL + message → on the client side
            // the B6 "disconnect dialog" triggers and the user can
            // return to the list.
            val appSessionsToKick = SessionRegistry.getAppSessionsForProject(projectId)
            appSessionsToKick.forEach { appSession ->
                try {
                    appSession.session.close(
                        CloseReason(
                            CloseReason.Codes.NORMAL,
                            "Project deleted"
                        )
                    )
                } catch (_: Exception) {
                    // WS already closed or I/O error — does not matter
                }
                // the finally block of the appWebSocket handler removes
                // the session from SessionRegistry automatically
            }

            // ─── Step 3 : cascade delete DB ───────────────────────────
            // order : history (all tiers) → widgets → devices → project
            widgetHistoryRepository.deleteAllByProject(projectId)
            widgetHistoryNumericRepository.deleteAllByProject(projectId)
            widgetHistoryMinRepository.deleteAllByProject(projectId)
            widgetHistoryHourRepository.deleteAllByProject(projectId)
            widgetHistoryDayRepository.deleteAllByProject(projectId)
            widgetRepository.deleteAllByProject(projectId)
            deviceRepository.deleteAllByProject(projectId)
            projectRepository.delete(projectId)

            call.respond(HttpStatusCode.OK, mapOf(
                "message" to "Project deleted",
                "id"      to projectId
            ))
        }
    }
}