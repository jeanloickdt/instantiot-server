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
import com.jeanloickdt.device.domain.UpdateDeviceRequest
import com.jeanloickdt.project.domain.ProjectRepository
import com.jeanloickdt.relay.ControlEventBroadcaster
import com.jeanloickdt.relay.DeviceOfflineReason
import com.jeanloickdt.relay.ConnectionRegistry
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.security.MessageDigest
import java.util.UUID

fun Route.deviceRoutes(
    deviceRepository: DeviceRepository,
    projectRepository: ProjectRepository,
    connections: ConnectionRegistry,
    events: ControlEventBroadcaster,
    /**
     * Le quota de cartes du compte. Ce serveur n'en vend pas : le defaut
     * laisse passer, et le nuage cable ici son `enforceStock`.
     *
     * Injecte plutot que code en dur — meme forme que `RulePolicies` et
     * `SignalPolicies` : la decision se cable, la route ne la connait pas.
     */
    quotaGate: suspend (call: io.ktor.server.application.ApplicationCall, ownerId: String, current: () -> Int) -> Boolean =
        { _, _, _ -> true },
    sinks: com.jeanloickdt.event.EventSinks? = null,
    /**
     * The live truth about who is connected.
     *
     * Presence stopped being written to the table on every transition — a
     * carrier hiccup used to turn three thousand reconnections into six
     * thousand synchronous writes. The RAM store is now authoritative and the
     * column is its durable mirror, so reading the column alone would show a
     * board offline for up to one flush period after it connected.
     *
     * Default `null` keeps every existing caller and test working: without a
     * store, the column is all there is, which is exactly the old behaviour.
     */
    presence: com.jeanloickdt.relay.PresenceStore? = null
) {
    // The column is the fallback, never the contradiction: RAM knows first.
    fun liveOnline(id: String, stored: Boolean) = presence?.isOnline(id) ?: stored

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
                        isOnline     = liveOnline(it.id, it.isOnline),
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

            // One ownership pattern everywhere: authorize the resource (the
            // project) up front — load it, require it to be ours, else 404 (never
            // reveal another user's project exists). The old code skipped this and
            // relied on the per-device filter below, which is safe but an
            // exception to the pattern; making every handler gate the same way is
            // exactly what keeps a gap like the creation one from reappearing.
            projectRepository.findById(ownerId, projectId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Project not found"))

            val devices = deviceRepository.findAllByProject(ownerId, projectId)
                // Defence in depth behind the project gate: device.ownerId equals
                // project.ownerId by construction (a device can only be created in
                // a project you own), so this is a no-op today — kept so a future
                // bug that broke that invariant could not leak rows.
                .filter { it.ownerId == ownerId }
                .map {
                    DeviceResponse(
                        id           = it.id,
                        name         = it.name,
                        projectId    = it.projectId,
                        isOnline     = liveOnline(it.id, it.isOnline),
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

            // ── Name validation ───────────────────────────────────
            // Same bounds as PATCH /name — the creation path must not be a
            // back door for empty or pathologically long names.
            val name = body.name.trim()
            if (name.length < 2 || name.length > 64) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("Name must be 2-64 characters")
                )
            }

            // ── Project ownership ─────────────────────────────────
            // The device is created with projectId = body.projectId, and the
            // relay routes frames by projectId — so a device dropped into another
            // user's project would leak/cross its data. Every other handler gates
            // on ownership (findById → ownerId != → 404); the creation path must
            // too. 404 (not 403) so we never reveal someone else's project exists.
            projectRepository.findById(ownerId, body.projectId)
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("Project not found"))

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

            // ── Plan quota ────────────────────────────────────────
            // Last of the checks, on purpose: a malformed request must read as
            // malformed, not as "upgrade your plan". Answers 402 itself when
            // it refuses. No plan file → unlimited → no COUNT(*) is run.
            val allowed = quotaGate(call, ownerId) {
                deviceRepository.countByOwner(ownerId).toInt()
            }
            if (!allowed) return@post

            // generate the plaintext token — shown only once
            val token     = UUID.randomUUID().toString()
            val tokenHash = sha256(token)

            val created = deviceRepository.create(
                ownerId      = ownerId,
                name         = name,
                projectId    = body.projectId,
                tokenHash    = tokenHash,
                deviceType   = deviceType,
                connectivity = connectivity
            )

            // returns the plaintext token — only once
            call.respond(HttpStatusCode.Created, CreateDeviceResponse(
                id           = created.id,
                name         = name,
                projectId    = body.projectId,
                token        = token,
                deviceType   = deviceType.name,
                connectivity = connectivity.name
            ))
        }

        // ============================================================
        // PATCH /api/devices/{id} — nom, type, connectivite
        //
        // La route `/name` reste : une app plus ancienne l'appelle encore, et
        // un relais qui la retirerait casserait le renommage sans rien dire.
        // ============================================================
        patch("/api/devices/{id}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@patch call.respond(HttpStatusCode.Unauthorized)
            val deviceId = call.parameters["id"]
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiError("Missing id"))
            val device = deviceRepository.findById(ownerId, deviceId)
                ?: return@patch call.respond(HttpStatusCode.NotFound, ApiError("Device not found"))

            val body = call.receive<UpdateDeviceRequest>()

            val nom = body.name?.trim()
            if (nom != null && (nom.length < 2 || nom.length > 64)) {
                return@patch call.respond(
                    HttpStatusCode.BadRequest, ApiError("Name must be 2-64 characters")
                )
            }

            val type = body.deviceType?.let {
                DeviceType.fromString(it) ?: return@patch call.respond(
                    HttpStatusCode.BadRequest, mapOf(
                        "error" to "Unknown deviceType", "value" to it,
                        "allowed" to DeviceType.entries.map { e -> e.name }
                    )
                )
            }
            val lien = body.connectivity?.let {
                DeviceConnectivity.fromString(it) ?: return@patch call.respond(
                    HttpStatusCode.BadRequest, mapOf(
                        "error" to "Unknown connectivity", "value" to it,
                        "allowed" to DeviceConnectivity.entries.map { e -> e.name }
                    )
                )
            }

            // La combinaison se valide sur la paire RESULTANTE.
            //
            // Un PATCH qui ne change que la connectivite doit etre confronte
            // au type deja en base : valider le seul champ envoye laisserait
            // poser du WiFi sur une carte qui n'en a pas, simplement parce
            // que la requete ne parlait pas du type.
            val typeFinal = type ?: device.deviceType?.let { DeviceType.fromString(it.name) }
            val lienFinal = lien ?: device.connectivity?.let { DeviceConnectivity.fromString(it.name) }
            if (typeFinal != null && lienFinal != null &&
                !isValidDeviceCombination(typeFinal, lienFinal)
            ) {
                return@patch call.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "Invalid combination (deviceType, connectivity)",
                    "deviceType" to typeFinal.name,
                    "connectivity" to lienFinal.name
                ))
            }

            if (nom != null) deviceRepository.updateName(ownerId, deviceId, nom)
            val apres = deviceRepository.updateHardware(
                ownerId, deviceId, type?.name, lien?.name
            ) ?: device

            call.respond(HttpStatusCode.OK, DeviceResponse(
                id           = apres.id,
                name         = nom ?: apres.name,
                projectId    = apres.projectId,
                isOnline     = liveOnline(apres.id, apres.isOnline),
                lastSeen     = apres.lastSeen,
                deviceType   = apres.deviceType?.name,
                connectivity = apres.connectivity?.name
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

            val device = deviceRepository.findById(ownerId, deviceId)

            if (device == null) {
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

            deviceRepository.updateName(ownerId, deviceId, trimmed)

            call.respond(HttpStatusCode.OK, DeviceResponse(
                id           = device.id,
                name         = trimmed,
                projectId    = device.projectId,
                isOnline     = liveOnline(device.id, device.isOnline),
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

            val device = deviceRepository.findById(ownerId, deviceId)

            if (device == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("Device not found"))
                return@delete
            }

            // broadcast device_offline (reason=deleted) BEFORE closing the session
            // so the apps know why the device is leaving
            events.deviceOffline(
                projectId = device.projectId,
                deviceId  = deviceId,
                reason    = DeviceOfflineReason.DELETED
            )

            // force disconnect of the existing TCP session if the device was connected
            connections.getDeviceSession(deviceId)?.let { activeSession ->
                try {
                    activeSession.socket.close()
                } catch (_: Exception) {
                    // socket already closed or I/O error — doesn't matter
                }
                // the finally of handleDeviceConnection will remove the session
                // and broadcast a device_offline reason=disconnected (accepted)
            }

            deviceRepository.delete(ownerId, deviceId)
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

            val device = deviceRepository.findById(ownerId, deviceId)

            if (device == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("Device not found"))
                return@post
            }

            val newToken     = UUID.randomUUID().toString()
            val newTokenHash = sha256(newToken)

            deviceRepository.renewToken(ownerId, deviceId, newTokenHash)

            // broadcast device_offline (reason=token_renewed) BEFORE closing the session
            // so the apps know it's not a crash but a renewal
            events.deviceOffline(
                projectId = device.projectId,
                deviceId  = deviceId,
                reason    = DeviceOfflineReason.TOKEN_RENEWED
            )

            // force disconnect of the old TCP session
            // the device will reconnect with its old token → server rejects it (red LED)
            // until reflashed with the new token
            connections.getDeviceSession(deviceId)?.let { activeSession ->
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