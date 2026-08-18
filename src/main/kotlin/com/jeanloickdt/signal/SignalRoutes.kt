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

package com.jeanloickdt.signal

import com.jeanloickdt.common.ApiError
import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.signal.data.SignalTable
import com.jeanloickdt.signal.domain.SignalRepository
import com.jeanloickdt.signal.domain.SignalRow
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

@Serializable
data class SignalDto(
    val deviceId: String,
    val address: Int,
    /** `I5` — what the sketch writes. Rendered here so no client re-implements it. */
    val ref: String,
    val label: String,
    val type: String,
    val unit: String,
    val decimals: Int,
    val minValue: Double?,
    val maxValue: Double?,
    val historised: Boolean,
    val direction: String,
    val lastPayload: String?,
    val lastSeenAt: Long?
)

@Serializable
data class CreateSignalRequest(
    val label: String,
    val type: String,
    /** Omit it: the server takes the lowest free slot, so sketches read I0, I1, I2. */
    val address: Int? = null,
    val unit: String = "",
    val decimals: Int = 1,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val historised: Boolean = true,
    val direction: String = SignalTable.DIRECTION_MEASURE
)

@Serializable
data class WriteSignalValueRequest(
    /** For bool/int/float/enum. `1`/`0` for a bool. */
    val value: Double? = null,
    /** For string signals. */
    val text: String? = null
)

/**
 * The answer to a setpoint write.
 *
 * A declared type rather than a `mapOf`: a map whose values are a Boolean and
 * a String has no serializer kotlinx can guess, and it fails at *response*
 * time — a 500 on a route whose logic ran perfectly. The compiler cannot warn
 * about that, so the shape is written down here instead.
 */
@Serializable
data class WriteSignalValueResponse(
    val delivered: Boolean,
    val reason: String? = null
)

@Serializable
data class UpdateSignalRequest(
    val label: String? = null,
    /**
     * Editable — but changing it drops the stored value. See the contract on
     * [com.jeanloickdt.signal.domain.SignalRepository.update].
     */
    val type: String? = null,
    val unit: String? = null,
    val decimals: Int? = null,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val historised: Boolean? = null,
    val direction: String? = null
)

/**
 * Cloud-only concerns, injected so this file stays byte-identical across
 * editions — same shape as [com.jeanloickdt.automation.RulePolicies].
 *
 * [quotaGate] is where "N signals" will bite once the grid is decided. The
 * default always allows, which is exactly what a self-hosted node wants: its
 * limit is its own disk.
 */
class SignalPolicies(
    val quotaGate: suspend (call: ApplicationCall, ownerId: String, current: () -> Int) -> Boolean =
        { _, _, _ -> true }
)

private fun SignalRow.toDto() = SignalDto(
    deviceId = deviceId, address = address, ref = SignalTable.render(address),
    label = label, type = type, unit = unit, decimals = decimals,
    minValue = minValue, maxValue = maxValue, historised = historised,
    direction = direction, lastPayload = lastPayload, lastSeenAt = lastSeenAt
)

private val KNOWN_TYPES = setOf(
    SignalTable.TYPE_BOOL, SignalTable.TYPE_INT, SignalTable.TYPE_FLOAT,
    SignalTable.TYPE_STRING, SignalTable.TYPE_ENUM
)
private val KNOWN_DIRECTIONS = setOf(
    SignalTable.DIRECTION_MEASURE, SignalTable.DIRECTION_SETPOINT, SignalTable.DIRECTION_BOTH
)

/**
 * The declaration API — owner-scoped, board-scoped.
 *
 * Every route resolves the board first and 404s when it is not the caller's:
 * the same door the rules use. Without it, an address is guessable and someone
 * else's history is one request away.
 */
fun Route.signalRoutes(
    signals: SignalRepository,
    devices: DeviceRepository,
    policies: SignalPolicies = SignalPolicies(),
    clock: () -> Long = System::currentTimeMillis,
    /**
     * The seam onto the device outbox. The default refuses to deliver, which
     * makes an unwired node store setpoints without pretending they arrived.
     */
    sendToDevice: suspend (deviceId: String, frame: ByteArray) -> Boolean = { _, _ -> false },
    /** Hands the new value to every app watching the board's project. */
    broadcastToApps: (projectId: String, frame: ByteArray) -> Unit = { _, _ -> }
) {
    authenticate("jwt") {

        /** Everything the picker needs, in one call — it lists a whole project. */
        get("/api/signals") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(HttpStatusCode.OK, signals.listByOwner(ownerId).map { it.toDto() })
        }

        get("/api/devices/{deviceId}/signals") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val deviceId = call.ownedDevice(devices, ownerId) ?: return@get
            call.respond(HttpStatusCode.OK, signals.listByDevice(ownerId, deviceId).map { it.toDto() })
        }

        post("/api/devices/{deviceId}/signals") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val deviceId = call.ownedDevice(devices, ownerId) ?: return@post
            val body = call.receive<CreateSignalRequest>()

            val label = body.label.trim()
            if (label.length !in 1..64) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("Label must be 1-64 characters"))
            }
            if (body.type !in KNOWN_TYPES) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    ApiError("Unknown type '${body.type}' — one of $KNOWN_TYPES"))
            }
            if (body.direction !in KNOWN_DIRECTIONS) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    ApiError("Unknown direction '${body.direction}'"))
            }
            if (body.decimals !in 0..6) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("Decimals must be 0-6"))
            }
            if (body.minValue != null && body.maxValue != null && body.minValue >= body.maxValue) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("min must be < max"))
            }

            if (!policies.quotaGate(call, ownerId) { signals.listByOwner(ownerId).size }) return@post

            // No address given: the lowest free slot, so a board's addresses stay
            // dense and its sketch reads I0, I1, I2 instead of a scatter.
            val address = body.address ?: signals.nextFreeAddress(ownerId, deviceId)
                ?: return@post call.respond(HttpStatusCode.Conflict,
                    ApiError("No free address left on this board (${SignalTable.ADDRESS_MAX + 1} max)"))

            if (address !in SignalTable.ADDRESS_MIN..SignalTable.ADDRESS_MAX) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    ApiError("Address must be ${SignalTable.ADDRESS_MIN}-${SignalTable.ADDRESS_MAX}"))
            }

            val created = signals.create(
                ownerId = ownerId, deviceId = deviceId, address = address,
                label = label, type = body.type, unit = body.unit, decimals = body.decimals,
                minValue = body.minValue, maxValue = body.maxValue,
                historised = body.historised, direction = body.direction, nowMs = clock()
            )
            if (!created) {
                return@post call.respond(HttpStatusCode.Conflict,
                    ApiError("${SignalTable.render(address)} is already taken on this board"))
            }
            call.respond(HttpStatusCode.Created, signals.find(ownerId, deviceId, address)!!.toDto())
        }

        patch("/api/devices/{deviceId}/signals/{address}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@patch call.respond(HttpStatusCode.Unauthorized)
            val deviceId = call.ownedDevice(devices, ownerId) ?: return@patch
            val address = call.address() ?: return@patch
            val existing = signals.find(ownerId, deviceId, address)
                ?: return@patch call.respond(HttpStatusCode.NotFound, ApiError("Signal not found"))

            val body = call.receive<UpdateSignalRequest>()
            if (body.label != null && body.label.trim().length !in 1..64) {
                return@patch call.respond(HttpStatusCode.BadRequest, ApiError("Label must be 1-64 characters"))
            }
            if (body.direction != null && body.direction !in KNOWN_DIRECTIONS) {
                return@patch call.respond(HttpStatusCode.BadRequest, ApiError("Unknown direction"))
            }
            if (body.type != null && body.type !in KNOWN_TYPES) {
                return@patch call.respond(HttpStatusCode.BadRequest,
                    ApiError("Unknown type '${body.type}' — one of $KNOWN_TYPES"))
            }
            if (body.decimals != null && body.decimals !in 0..6) {
                return@patch call.respond(HttpStatusCode.BadRequest, ApiError("Decimals must be 0-6"))
            }
            // Bounds are checked against what the row will HOLD, not only against
            // what the request carries — sending min alone must not invert them.
            val min = body.minValue ?: existing.minValue
            val max = body.maxValue ?: existing.maxValue
            if (min != null && max != null && min >= max) {
                return@patch call.respond(HttpStatusCode.BadRequest, ApiError("min must be < max"))
            }

            // The type IS editable. What it costs is written down in one
            // place — the repository contract — and it costs the stored
            // value, not the history: samples already recorded stay the
            // numbers they were, and a client that changes float→string is
            // choosing to leave a curve behind, not to corrupt one.
            signals.update(
                ownerId = ownerId, deviceId = deviceId, address = address,
                label = body.label?.trim(), unit = body.unit, decimals = body.decimals,
                minValue = body.minValue, maxValue = body.maxValue,
                historised = body.historised, direction = body.direction,
                type = body.type, nowMs = clock()
            )
            call.respond(HttpStatusCode.OK, signals.find(ownerId, deviceId, address)!!.toDto())
        }

        /**
         * Writes a setpoint — what the app WANTS, as opposed to what the board
         * says it is. Stored first, then sent: an offline board finds it at its
         * next connection instead of losing it.
         */
        put("/api/devices/{deviceId}/signals/{address}/value") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@put call.respond(HttpStatusCode.Unauthorized)
            val deviceId = call.ownedDevice(devices, ownerId) ?: return@put
            val address = call.address() ?: return@put
            val body = call.receive<WriteSignalValueRequest>()

            val projectId = devices.findById(deviceId)?.projectId
            when (val r = SignalSetpoint.write(
                signals, ownerId, deviceId, address, body.value, body.text, clock(), sendToDevice,
                broadcast = { frame -> projectId?.let { broadcastToApps(it, frame) } }
            )) {
                is SignalSetpoint.Outcome.Delivered ->
                    call.respond(HttpStatusCode.OK, WriteSignalValueResponse(delivered = true))
                is SignalSetpoint.Outcome.Stored ->
                    // 202: recorded, not yet acted upon. Saying OK would claim
                    // the board did something it has not seen.
                    call.respond(HttpStatusCode.Accepted, WriteSignalValueResponse(
                        delivered = false,
                        reason = "device offline — will be restored on connect"
                    ))
                is SignalSetpoint.Outcome.Refused ->
                    call.respond(HttpStatusCode.BadRequest, ApiError(r.reason))
            }
        }

        delete("/api/devices/{deviceId}/signals/{address}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)
            val deviceId = call.ownedDevice(devices, ownerId) ?: return@delete
            val address = call.address() ?: return@delete

            if (!signals.delete(ownerId, deviceId, address)) {
                return@delete call.respond(HttpStatusCode.NotFound, ApiError("Signal not found"))
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Signal deleted"))
        }
    }
}

/**
 * Resolves the board and refuses it when it is not the caller's.
 *
 * **404, never 403** — the same choice the rules make: a 403 would confirm the
 * board exists, which is one bit of somebody else's inventory.
 */
private suspend fun ApplicationCall.ownedDevice(devices: DeviceRepository, ownerId: String): String? {
    val deviceId = parameters["deviceId"]
    if (deviceId.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, ApiError("Missing deviceId")); return null
    }
    val device = devices.findById(deviceId)
    if (device == null || device.ownerId != ownerId) {
        respond(HttpStatusCode.NotFound, ApiError("Device not found")); return null
    }
    return deviceId
}

private suspend fun ApplicationCall.address(): Int? {
    val raw = parameters["address"]
    // Accept both `5` and `I5`: the app shows the second, scripts type the first.
    val n = raw?.removePrefix("I")?.removePrefix("i")?.toIntOrNull()
    if (n == null || n !in SignalTable.ADDRESS_MIN..SignalTable.ADDRESS_MAX) {
        respond(HttpStatusCode.BadRequest, ApiError("Address must be I0-I255")); return null
    }
    return n
}
