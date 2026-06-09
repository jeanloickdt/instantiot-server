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

// device/DeviceRoutes.kt
package com.jeanloickdt.device

import com.jeanloickdt.common.ApiError

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
        // GET /api/devices — lists all devices of the logged-in user
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
        // GET /api/projects/{projectId}/devices — devices of a project
        // ============================================================
        get("/api/projects/{projectId}/devices") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Missing projectId"))

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
        // POST /api/devices — register a new device
        // Generates a UUID v4 token → returned in plaintext ONLY ONCE
        // Stores only the SHA-256 of the token in DB
        // ============================================================
        post("/api/devices") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val body = call.receive<CreateDeviceRequest>()

            // ── Enum validation ───────────────────────────────────
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

            // generate the plaintext token — shown only once
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

            // returns the plaintext token — only once
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
        // PATCH /api/devices/{id}/name — rename a device
        // Doesn't touch the TCP session: the ESP device keeps emitting
        // normally, only its label changes on the server and apps side.
        // ============================================================
        patch("/api/devices/{id}/name") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@patch call.respond(HttpStatusCode.Unauthorized)

            val deviceId = call.parameters["id"]
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiError("Missing id"))

            val device = deviceRepository.findById(deviceId)

            if (device == null || device.ownerId != ownerId) {
                call.respond(HttpStatusCode.NotFound, ApiError("Device not found"))
                return@patch
            }

            val body = call.receive<UpdateDeviceNameRequest>()
            val trimmed = body.name.trim()
            if (trimmed.length < 2 || trimmed.length > 64) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("Name must be 2-64 characters")
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
        // DELETE /api/devices/{id} — delete device + revoke token
        // Broadcasts device_offline (reason=deleted) to the apps + closes the TCP session
        // ============================================================
        delete("/api/devices/{id}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)

            val deviceId = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("Missing id"))

            val device = deviceRepository.findById(deviceId)

            if (device == null || device.ownerId != ownerId) {
                call.respond(HttpStatusCode.NotFound, ApiError("Device not found"))
                return@delete
            }

            // broadcast device_offline (reason=deleted) BEFORE closing the session
            // so the apps know why the device is leaving
            ControlEventBroadcaster.deviceOffline(
                projectId = device.projectId,
                deviceId  = deviceId,
                reason    = DeviceOfflineReason.DELETED
            )

            // force disconnect of the existing TCP session if the device was connected
            SessionRegistry.getDeviceSession(deviceId)?.let { activeSession ->
                try {
                    activeSession.socket.close()
                } catch (_: Exception) {
                    // socket already closed or I/O error — doesn't matter
                }
                // the finally of handleDeviceConnection will remove the session
                // and broadcast a device_offline reason=disconnected (accepted)
            }

            deviceRepository.delete(deviceId)
            call.respond(HttpStatusCode.OK, mapOf(
                "message" to "Device deleted",
                "id"      to deviceId
            ))
        }

        // ============================================================
        // POST /api/devices/{id}/renew-token — regenerates a new token
        // The old token is revoked immediately
        // The new token is shown only once
        // Broadcasts device_offline (reason=token_renewed) + closes the TCP session
        // ============================================================
        post("/api/devices/{id}/renew-token") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val deviceId = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Missing id"))

            val device = deviceRepository.findById(deviceId)

            if (device == null || device.ownerId != ownerId) {
                call.respond(HttpStatusCode.NotFound, ApiError("Device not found"))
                return@post
            }

            val newToken     = UUID.randomUUID().toString()
            val newTokenHash = sha256(newToken)

            deviceRepository.renewToken(deviceId, newTokenHash)

            // broadcast device_offline (reason=token_renewed) BEFORE closing the session
            // so the apps know it's not a crash but a renewal
            ControlEventBroadcaster.deviceOffline(
                projectId = device.projectId,
                deviceId  = deviceId,
                reason    = DeviceOfflineReason.TOKEN_RENEWED
            )

            // force disconnect of the old TCP session
            // the device will reconnect with its old token → server rejects it (red LED)
            // until reflashed with the new token
            SessionRegistry.getDeviceSession(deviceId)?.let { activeSession ->
                try {
                    activeSession.socket.close()
                } catch (_: Exception) {
                    // socket already closed or I/O error
                }
                // the finally of handleDeviceConnection will remove the session
                // and broadcast a device_offline reason=disconnected (accepted)
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
// SHA-256 — hash of the device token
// The plaintext token is never stored in DB
// ============================================================
private fun sha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}