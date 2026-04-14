// project/ProjectRoutes.kt
package com.jeanloickdt.project

import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.project.domain.CreateProjectRequest
import com.jeanloickdt.project.domain.ProjectRepository
import com.jeanloickdt.project.domain.ProjectResponse
import com.jeanloickdt.project.domain.UpdateProjectLayoutRequest
import com.jeanloickdt.project.domain.UpdateProjectNameRequest
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
    widgetHistoryRepository: WidgetHistoryRepository
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

            // cascade delete — ordre : history → widgets → devices → projet
            widgetHistoryRepository.deleteAllByProject(projectId)
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