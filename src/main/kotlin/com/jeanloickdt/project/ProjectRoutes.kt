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
    widgetHistoryNumericRepository: com.jeanloickdt.widget.domain.WidgetHistoryNumericRepository
) {

    authenticate("jwt") {

        // ============================================================
        // GET /api/projects — liste projets du user connecté
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
        // POST /api/projects — créer un projet
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
        // GET /api/projects/{id} — détails projet + layoutJson complet
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
        // PATCH /api/projects/{id}/name — renommer un projet
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
        // PATCH /api/projects/{id}/layout — sync layout complet
        // Appelé avec debounce depuis l'app après chaque modification
        // Le server stocke layoutJson comme blob opaque — ne l'interprète pas
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
        // DELETE /api/projects/{id} — supprimer projet + cascade widgets
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

            // ─── Step 1 : kicker les devices encore connectes en TCP ──
            // Avant le cascade delete, on recupere la liste des devices
            // du projet et pour chacun on :
            //   1. broadcast device_offline (reason=deleted) — informe
            //      les apps qui regardent le projet (dashboard ouvert)
            //   2. ferme la socket TCP si elle est active — sinon le
            //      device ESP reste un fantome cote serveur, affiche
            //      "online" alors que le projet est supprime
            // Meme pattern que DELETE /api/devices/{id}.
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
                        // socket deja ferme ou I/O error — peu importe
                    }
                    // le finally du handleDeviceConnection retire la
                    // session du SessionRegistry automatiquement
                }
            }

            // ─── Step 2 : fermer les WS app-sessions de ce projet ────
            // Les apps qui avaient ce dashboard ouvert (phone2, tablet,
            // autre appareil du meme user…) restaient sur une WS qui
            // handshake un projectId defunct. On les ferme proprement
            // avec un CloseReason.NORMAL + message → cote client la B6
            // "disconnect dialog" se declenche et l'user peut revenir
            // a la liste.
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
                    // WS deja ferme ou I/O error — peu importe
                }
                // le finally du appWebSocket handler retire la session
                // du SessionRegistry automatiquement
            }

            // ─── Step 3 : cascade delete DB ───────────────────────────
            // ordre : history (opaque + numérique) → widgets → devices → projet
            widgetHistoryRepository.deleteAllByProject(projectId)
            widgetHistoryNumericRepository.deleteAllByProject(projectId)
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