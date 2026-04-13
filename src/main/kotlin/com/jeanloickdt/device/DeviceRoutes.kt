// device/DeviceRoutes.kt
package com.jeanloickdt.device

import com.jeanloickdt.device.domain.CreateDeviceRequest
import com.jeanloickdt.device.domain.CreateDeviceResponse
import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.device.domain.DeviceResponse
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.security.MessageDigest
import java.util.UUID

fun Route.deviceRoutes(deviceRepository: DeviceRepository) {

    authenticate("jwt") {

        // ============================================================
        // GET /api/devices — liste tous les devices du user connecté
        // ============================================================
        get("/api/devices") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val devices = deviceRepository.findAllByOwner(ownerId)
                .map {
                    DeviceResponse(
                        id        = it.id,
                        name      = it.name,
                        projectId = it.projectId,
                        isOnline  = it.isOnline,
                        lastSeen  = it.lastSeen
                    )
                }
            call.respond(HttpStatusCode.OK, devices)
        }

        // ============================================================
        // GET /api/projects/{projectId}/devices — devices d'un projet
        // ============================================================
        get("/api/projects/{projectId}/devices") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))

            val devices = deviceRepository.findAllByProject(projectId)
                .filter { it.ownerId == ownerId }
                .map {
                    DeviceResponse(
                        id        = it.id,
                        name      = it.name,
                        projectId = it.projectId,
                        isOnline  = it.isOnline,
                        lastSeen  = it.lastSeen
                    )
                }
            call.respond(HttpStatusCode.OK, devices)
        }

        // ============================================================
        // POST /api/devices — enregistrer un nouveau device
        // Génère un token UUID v4 → retourné en clair UNE SEULE FOIS
        // Stocke seulement le SHA-256 du token en DB
        // ============================================================
        post("/api/devices") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val body = call.receive<CreateDeviceRequest>()

            // génère le token en clair — affiché une seule fois
            val token     = UUID.randomUUID().toString()
            val tokenHash = sha256(token)

            val id = deviceRepository.create(
                name      = body.name,
                projectId = body.projectId,
                ownerId   = ownerId,
                tokenHash = tokenHash
            )

            // retourne le token en clair — une seule fois
            call.respond(HttpStatusCode.Created, CreateDeviceResponse(
                id        = id,
                name      = body.name,
                projectId = body.projectId,
                token     = token
            ))
        }

        // ============================================================
        // DELETE /api/devices/{id} — supprimer device + révoquer token
        // ============================================================
        delete("/api/devices/{id}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)

            val deviceId = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

            val device = deviceRepository.findById(deviceId)

            if (device == null || device.ownerId != ownerId) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Device not found"))
                return@delete
            }

            deviceRepository.delete(deviceId)
            call.respond(HttpStatusCode.OK, mapOf(
                "message" to "Device deleted",
                "id"      to deviceId
            ))
        }

        // ============================================================
        // POST /api/devices/{id}/renew-token — regénère un nouveau token
        // L'ancien token est révoqué immédiatement
        // Le nouveau token est affiché une seule fois
        // ============================================================
        post("/api/devices/{id}/renew-token") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val deviceId = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

            val device = deviceRepository.findById(deviceId)

            if (device == null || device.ownerId != ownerId) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Device not found"))
                return@post
            }

            val newToken     = UUID.randomUUID().toString()
            val newTokenHash = sha256(newToken)

            deviceRepository.renewToken(deviceId, newTokenHash)

            call.respond(HttpStatusCode.OK, mapOf(
                "message" to "Token renewed — save this token, it will not be shown again",
                "id"      to deviceId,
                "token"   to newToken
            ))
        }
    }
}

// ============================================================
// SHA-256 — hash du token device
// Le token en clair n'est jamais stocké en DB
// ============================================================
private fun sha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}