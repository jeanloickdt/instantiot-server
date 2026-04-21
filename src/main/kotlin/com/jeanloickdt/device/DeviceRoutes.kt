// device/DeviceRoutes.kt
package com.jeanloickdt.device

import com.jeanloickdt.device.domain.CreateDeviceRequest
import com.jeanloickdt.device.domain.CreateDeviceResponse
import com.jeanloickdt.device.domain.DeviceConnectivity
import com.jeanloickdt.device.domain.DeviceType
import com.jeanloickdt.device.domain.isValidDeviceCombination
import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.device.domain.DeviceResponse
import com.jeanloickdt.device.domain.UpdateDeviceNameRequest
import com.jeanloickdt.relay.ControlEventBroadcaster
import com.jeanloickdt.relay.DeviceOfflineReason
import com.jeanloickdt.relay.SessionRegistry
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
                        id           = it.id,
                        name         = it.name,
                        projectId    = it.projectId,
                        isOnline     = it.isOnline,
                        lastSeen     = it.lastSeen,
                        deviceType   = it.deviceType?.name,
                        connectivity = it.connectivity?.name
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
                        id           = it.id,
                        name         = it.name,
                        projectId    = it.projectId,
                        isOnline     = it.isOnline,
                        lastSeen     = it.lastSeen,
                        deviceType   = it.deviceType?.name,
                        connectivity = it.connectivity?.name
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

            // ── Validation enums ──────────────────────────────────
            val deviceType = DeviceType.fromString(body.deviceType)
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "Unknown deviceType",
                    "value" to body.deviceType,
                    "allowed" to DeviceType.entries.map { it.name }
                ))

            val connectivity = DeviceConnectivity.fromString(body.connectivity)
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "Unknown connectivity",
                    "value" to body.connectivity,
                    "allowed" to DeviceConnectivity.entries.map { it.name }
                ))

            if (!isValidDeviceCombination(deviceType, connectivity)) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "Invalid combination (deviceType, connectivity)",
                    "deviceType" to deviceType.name,
                    "connectivity" to connectivity.name
                ))
            }

            // génère le token en clair — affiché une seule fois
            val token     = UUID.randomUUID().toString()
            val tokenHash = sha256(token)

            val id = deviceRepository.create(
                name         = body.name,
                projectId    = body.projectId,
                ownerId      = ownerId,
                tokenHash    = tokenHash,
                deviceType   = deviceType,
                connectivity = connectivity
            )

            // retourne le token en clair — une seule fois
            call.respond(HttpStatusCode.Created, CreateDeviceResponse(
                id           = id,
                name         = body.name,
                projectId    = body.projectId,
                token        = token,
                deviceType   = deviceType.name,
                connectivity = connectivity.name
            ))
        }

        // ============================================================
        // PATCH /api/devices/{id}/name — renommer un device
        // Ne touche pas la session TCP : le device ESP continue a emettre
        // normalement, seul son label change cote serveur et apps.
        // ============================================================
        patch("/api/devices/{id}/name") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@patch call.respond(HttpStatusCode.Unauthorized)

            val deviceId = call.parameters["id"]
                ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

            val device = deviceRepository.findById(deviceId)

            if (device == null || device.ownerId != ownerId) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Device not found"))
                return@patch
            }

            val body = call.receive<UpdateDeviceNameRequest>()
            val trimmed = body.name.trim()
            if (trimmed.length < 2 || trimmed.length > 64) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Name must be 2-64 characters")
                )
                return@patch
            }

            deviceRepository.updateName(deviceId, trimmed)

            call.respond(HttpStatusCode.OK, DeviceResponse(
                id           = device.id,
                name         = trimmed,
                projectId    = device.projectId,
                isOnline     = device.isOnline,
                lastSeen     = device.lastSeen,
                deviceType   = device.deviceType?.name,
                connectivity = device.connectivity?.name
            ))
        }

        // ============================================================
        // DELETE /api/devices/{id} — supprimer device + révoquer token
        // Broadcast device_offline (reason=deleted) aux apps + ferme la session TCP
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

            // broadcast device_offline (reason=deleted) AVANT de fermer la session
            // pour que les apps sachent pourquoi le device part
            ControlEventBroadcaster.deviceOffline(
                projectId = device.projectId,
                deviceId  = deviceId,
                reason    = DeviceOfflineReason.DELETED
            )

            // force disconnect de la session TCP existante si le device etait connecte
            SessionRegistry.getDeviceSession(deviceId)?.let { activeSession ->
                try {
                    activeSession.socket.close()
                } catch (_: Exception) {
                    // socket deja ferme ou I/O error — peu importe
                }
                // le finally du handleDeviceConnection va retirer la session
                // et broadcaster un device_offline reason=disconnected (accepte)
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
        // Broadcast device_offline (reason=token_renewed) + ferme la session TCP
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

            // broadcast device_offline (reason=token_renewed) AVANT de fermer la session
            // pour que les apps sachent que ce n'est pas un crash mais un renouvellement
            ControlEventBroadcaster.deviceOffline(
                projectId = device.projectId,
                deviceId  = deviceId,
                reason    = DeviceOfflineReason.TOKEN_RENEWED
            )

            // force disconnect de l'ancienne session TCP
            // le device reconnectera avec son vieux token → serveur le rejette (LED rouge)
            // jusqu'a reflash avec le nouveau token
            SessionRegistry.getDeviceSession(deviceId)?.let { activeSession ->
                try {
                    activeSession.socket.close()
                } catch (_: Exception) {
                    // socket deja ferme ou I/O error
                }
                // le finally du handleDeviceConnection va retirer la session
                // et broadcaster un device_offline reason=disconnected (accepte)
            }

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