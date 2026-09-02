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

// project/ProjectRoutes.kt
package com.jeanloickdt.project

import com.jeanloickdt.common.ApiError

import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.project.domain.CreateProjectRequest
import com.jeanloickdt.project.domain.ProjectRepository
import com.jeanloickdt.project.domain.ProjectRow
import com.jeanloickdt.project.domain.ProjectResponse
import com.jeanloickdt.project.domain.ProjectSummary
import com.jeanloickdt.project.domain.ProjectSummaryResponse
import com.jeanloickdt.project.domain.UpdateProjectLayoutRequest
import com.jeanloickdt.project.domain.UpdateProjectNameRequest
import com.jeanloickdt.relay.ControlEventBroadcaster
import com.jeanloickdt.relay.DeviceOfflineReason
import com.jeanloickdt.relay.ConnectionRegistry
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import com.jeanloickdt.signal.domain.SignalHistoryPurge
import com.jeanloickdt.signal.domain.SignalRepository
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = org.slf4j.LoggerFactory.getLogger("ProjectRoutes")

/**
 * La réponse d'un projet.
 *
 * Elle était construite champ par champ à quatre endroits identiques. Une
 * cinquième route, ou un champ ajouté à [ProjectRow], et l'une des quatre
 * diverge sans que rien ne le signale.
 */
private fun ProjectRow.toResponse() = ProjectResponse(
    id         = id,
    name       = name,
    layoutJson = layoutJson,
    version    = version,
    createdAt  = createdAt,
    updatedAt  = updatedAt
)

private fun ProjectSummary.toResponse() = ProjectSummaryResponse(
    id        = id,
    name      = name,
    version   = version,
    createdAt = createdAt,
    updatedAt = updatedAt
)

/**
 * La taille maximale d'un layout, en OCTETS UTF-8.
 *
 * Une constante technique, pas un droit de plan : elle ne s'achète pas, elle
 * protège — même nature que le fusible de dix messages par seconde. Elle vit
 * donc à côté du code plutôt que dans la grille tarifaire.
 *
 * Pourquoi 256 Ko : un tableau de bord de cinquante widgets avec positions,
 * tailles, styles et liaisons pèse une dizaine de kilo-octets. Vingt-cinq fois
 * au-dessus du gros tableau réaliste — personne ne la touche par accident, et
 * elle arrête net l'accident.
 *
 * Sans elle, un client bogué ou malveillant pouvait pousser des dizaines de
 * mégaoctets : stockés, relus à chaque ouverture, et présents dans chaque
 * sauvegarde.
 */
private const val MAX_LAYOUT_BYTES = 256 * 1024

fun Route.projectRoutes(
    projectRepository: ProjectRepository,
    deviceRepository: DeviceRepository,
    signalRepository: SignalRepository,
    signalHistoryRepository: SignalHistoryPurge,
    connections: ConnectionRegistry,
    events: ControlEventBroadcaster
) {

    authenticate("jwt") {

        // ============================================================
        // GET /api/projects — list projects of the authenticated user
        // ============================================================
        get("/api/projects") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            // Sommaire, pas complet : la liste sert a afficher des noms, et le
            // layout de chaque projet est le champ le plus lourd de la base.
            // Le depot ne selectionne meme pas la colonne — voir
            // `findAllByOwnerSummary`.
            val projects = projectRepository.findAllByOwnerSummary(ownerId)
                .map { it.toResponse() }
            call.respond(HttpStatusCode.OK, projects)
        }

        // ============================================================
        // POST /api/projects — create a project
        // ============================================================
        post("/api/projects") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val body    = call.receive<CreateProjectRequest>()
            val name    = body.name.trim()
            if (name.length < 2 || name.length > 64) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("Name must be 2-64 characters")
                )
            }
            val project = projectRepository.create(ownerId, name)

            call.respond(HttpStatusCode.Created, project.toResponse())
        }

        // ============================================================
        // GET /api/projects/{id} — project details + full layoutJson
        // ============================================================
        get("/api/projects/{id}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val projectId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Missing id"))

            val project = projectRepository.findById(ownerId, projectId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Project not found"))

            call.respond(HttpStatusCode.OK, project.toResponse())
        }

        // ============================================================
        // PATCH /api/projects/{id}/name — rename a project
        // ============================================================
        patch("/api/projects/{id}/name") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@patch call.respond(HttpStatusCode.Unauthorized)

            val projectId = call.parameters["id"]
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiError("Missing id"))

            // Pas de `findById` ici : `updateName` est deja scope par
            // proprietaire et rend `null` si le projet n'existe pas ou n'est
            // pas a nous. La garde a demenage dans le depot ; la relire ici
            // ferait deux requetes pour une ecriture.
            val body = call.receive<UpdateProjectNameRequest>()
            val name = body.name.trim()
            if (name.length < 2 || name.length > 64) {
                return@patch call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("Name must be 2-64 characters")
                )
            }
            val updated = projectRepository.updateName(ownerId, projectId, name)
                ?: return@patch call.respond(HttpStatusCode.NotFound, ApiError("Project not found"))

            call.respond(HttpStatusCode.OK, updated.toResponse())
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
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiError("Missing id"))

            // Premiere barriere, AVANT de lire le corps : refuser apres
            // `receive` voudrait dire avoir deja charge le blob en memoire.
            val declared = call.request.contentLength()
            if (declared != null && declared > MAX_LAYOUT_BYTES) {
                return@patch call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ApiError("Layout too large (max ${MAX_LAYOUT_BYTES / 1024} KB)")
                )
            }

            val body = call.receive<UpdateProjectLayoutRequest>()

            // Seconde barriere, APRES l'analyse : une requete en morceaux
            // (`Transfer-Encoding: chunked`) n'annonce aucune taille, et la
            // premiere barriere ne la voit pas.
            //
            // EN OCTETS UTF-8, pas en caracteres. `String.length` compte des
            // unites de code : un layout plein d'accents ou d'emoji passerait
            // une verification en caracteres et depasserait la borne la ou elle
            // compte vraiment — en base.
            if (body.layoutJson.toByteArray(Charsets.UTF_8).size > MAX_LAYOUT_BYTES) {
                return@patch call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ApiError("Layout too large (max ${MAX_LAYOUT_BYTES / 1024} KB)")
                )
            }

            if (body.version == null) {
                // Not refused — an app that predates the guard must keep
                // working. But it is worth a line: while this happens, two
                // phones editing the same dashboard can still erase each other.
                logger.warn("Layout write without a version on project $projectId — " +
                    "concurrent edits are unprotected until the app sends one")
            }

            when (val r = projectRepository.updateLayout(ownerId, projectId, body.layoutJson, body.version)) {
                is com.jeanloickdt.project.domain.LayoutWrite.Ok -> {
                    // The other phones learn about it now, instead of walking
                    // into a 409 the next time they save. The guard prevents the
                    // loss; this is what makes the loss rare.
                    events.layoutChanged(projectId, r.version)
                    call.respond(HttpStatusCode.OK, mapOf(
                        "message"   to "Layout updated",
                        "projectId" to projectId,
                        "version"   to r.version.toString()
                    ))
                }

                is com.jeanloickdt.project.domain.LayoutWrite.Conflict ->
                    // 409 with the winning layout attached: "somebody saved" is
                    // useless to the app without "and here is what they saved".
                    call.respond(HttpStatusCode.Conflict, com.jeanloickdt.project.domain.LayoutConflictResponse(
                        error = "This dashboard changed since you opened it",
                        currentVersion = r.currentVersion,
                        currentLayoutJson = r.currentLayoutJson
                    ))

                is com.jeanloickdt.project.domain.LayoutWrite.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ApiError("Project not found"))
            }
        }

        // ============================================================
        // DELETE /api/projects/{id} — delete project + cascade widgets
        // ============================================================
        delete("/api/projects/{id}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)

            val projectId = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("Missing id"))

            // Celle-ci RESTE, contrairement a ses jumelles de /name et
            // /layout — et la raison doit etre ecrite, sinon quelqu'un la
            // supprimera avec les deux autres.
            //
            // Les etapes 1 et 2 ferment des sockets et des WebSockets : des
            // effets HORS transaction, qu'aucun `rollback` ne rattrape. Sans
            // cette garde, une suppression visant un projet inexistant — ou
            // celui de quelqu'un d'autre — deconnecterait quand meme des
            // cartes avant que la cascade ne reponde 404.
            projectRepository.findById(ownerId, projectId)
                ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("Project not found"))

            // ─── Step 1 : kick devices still connected over TCP ───────
            // Before the cascade delete, we fetch the list of devices
            // for the project and for each one we:
            //   1. broadcast device_offline (reason=deleted) — informs
            //      the apps watching the project (dashboard open)
            //   2. close the TCP socket if it is active — otherwise the
            //      ESP device stays a ghost on the server side, shown
            //      "online" even though the project is deleted
            // Same pattern as DELETE /api/devices/{id}.
            val devicesToKick = deviceRepository.findAllByProject(ownerId, projectId)
            devicesToKick.forEach { d ->
                events.deviceOffline(
                    projectId = projectId,
                    deviceId  = d.id,
                    reason    = DeviceOfflineReason.DELETED
                )
                connections.getDeviceSession(d.id)?.let { active ->
                    try {
                        active.socket.close()
                    } catch (_: Exception) {
                        // socket already closed or I/O error — does not matter
                    }
                    // the finally block of handleDeviceConnection removes
                    // the session from ConnectionRegistry automatically
                }
            }

            // ─── Step 2 : close the WS app-sessions of this project ──
            // The apps that had this dashboard open (phone2, tablet,
            // another device of the same user…) stayed on a WS that
            // handshook a defunct projectId. We close them cleanly
            // with a CloseReason.NORMAL + message → on the client side
            // the B6 "disconnect dialog" triggers and the user can
            // return to the list.
            val appSessionsToKick = connections.getAppSessionsForProject(projectId)
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
                // the session from ConnectionRegistry automatically
            }

            // ─── Step 3 : cascade delete DB (atomic) ──────────────────
            // order : history (all tiers) → widgets → devices → project
            //
            // One transaction wraps all 8 deletes: Exposed joins each repo's
            // own transaction{} into this outer one, so the cascade is
            // all-or-nothing. Without it, a crash mid-cascade (e.g. after the
            // history but before the widgets) would leave a project that still
            // exists — it is deleted last — but has lost part of its data. The
            // kicks above stay outside on purpose: they are I/O effects
            // (socket/WS close), not DB, and must not be rolled back.
            transaction {
                // L'ordre suit les clés étrangères : historique → signaux →
                // cartes → projet. À l'envers, la contrainte refuse et toute
                // la transaction avorte.
                //
                // Les tables d'historique ne portent pas de `project_id` — le
                // modèle cible l'a retiré. Le lien se refait par une
                // sous-requête, côté moteur : une instruction par table, quelle
                // que soit la taille du projet.
                //
                // La version d'avant résolvait les identifiants ici : une
                // requête par carte, puis un `IN (…)` de tous les signaux. À
                // cent cartes, cent allers-retours et une clause de plusieurs
                // milliers d'éléments.
                val devices = deviceRepository.findAllByProject(ownerId, projectId)
                val deviceIds = devices.map { it.id }
                signalHistoryRepository.deleteAllByDevices(ownerId, deviceIds)
                // Une instruction, pas une par carte. La ligne du dessus venait
                // d'etre corrigee pour ce probleme exact ; la correction
                // s'etait arretee une ligne trop tot. A cent cartes, c'etait
                // cent allers-retours dans une transaction qui tient des
                // verrous — et c'est le compte Advanced, celui qui a le plus de
                // cartes, qui attendait le plus longtemps.
                //
                // `ownerId` vient du JETON, plus de la donnee relue
                // (`it.ownerId`). Les deux sont egaux aujourd'hui ; suivre ce
                // que la base dit plutot que ce que le jeton dit est le mauvais
                // reflexe le jour ou ils divergent.
                signalRepository.deleteByDevices(ownerId, deviceIds)
                deviceRepository.deleteAllByProject(ownerId, projectId)
                projectRepository.delete(ownerId, projectId)
            }

            call.respond(HttpStatusCode.OK, mapOf(
                "message" to "Project deleted",
                "id"      to projectId
            ))
        }
    }
}