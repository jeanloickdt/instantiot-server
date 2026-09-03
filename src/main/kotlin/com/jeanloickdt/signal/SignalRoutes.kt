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
    val replayOnConnect: Boolean,
    val automationVisible: Boolean,
    val lastPayload: String?,
    val lastSeenAt: Long?,
    /**
     * The board disagrees with this declaration — how many frames were refused,
     * and what it actually sends.
     *
     * Carried on the signal rather than announced only over the socket, because
     * the moment the user needs it is the moment they open the app to find out
     * why a widget went quiet — which is usually *after* the refusals started.
     * A live event would have been missed; a field is always there.
     *
     * `null` when the board and the declaration agree, which is the normal case.
     */
    val mismatch: SignalMismatchDto? = null
)

/** What the app needs to write the sentence: reflash the board, or change the type. */
@Serializable
data class SignalMismatchDto(
    val refusedCount: Long,
    /** What the slot is declared to hold. */
    val expectedType: String,
    /** What the board actually sends. */
    val receivedType: String,
    val lastAtMs: Long
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
    /**
     * `value` ou `action`.
     *
     * Une **action** est normalisée par le serveur : écrite par l'app, sans
     * historique, sans rejeu. Ce n'est pas de la rigidité — ces trois réglages
     * n'ont rien à décrire sur un fait, et les laisser réglables créerait des
     * déclarations incohérentes que personne ne saurait interpréter.
     */
    val historised: Boolean = true,
    /**
     * La carte retrouve-t-elle cette valeur en se reconnectant ?
     *
     * `true` par défaut — c'est le comportement qui existait avant ce champ.
     * Le passer à `false` fait de cette adresse une ACTION : rien n'est
     * rejoué, donc rien ne peut se déclencher tout seul.
     */
    val replayOnConnect: Boolean = true,
    /** Proposé dans l'éditeur de règles. `true` par défaut : tout signal l'était. */
    val automationVisible: Boolean = true,
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
    val replayOnConnect: Boolean? = null,
    val automationVisible: Boolean? = null,
)

/**
 * Les regles de decision, injectees — meme forme que
 * [com.jeanloickdt.automation.RulePolicies].
 *
 * [quotaGate] est l'endroit ou « N signaux » mordra quand la grille le
 * decidera. Le defaut laisse passer.
 */
class SignalPolicies(
    val quotaGate: suspend (call: ApplicationCall, ownerId: String, current: () -> Int) -> Boolean =
        { _, _, _ -> true }
)

private fun SignalRow.toDto() = SignalDto(
    deviceId = deviceId, address = address, ref = SignalTable.render(address),
    label = label, type = type, unit = unit, decimals = decimals,
    minValue = minValue, maxValue = maxValue,
    historised = historised,
    replayOnConnect = replayOnConnect,
    automationVisible = automationVisible,
    lastPayload = lastPayload, lastSeenAt = lastSeenAt,
    mismatch = TypeMismatches.stateOf(deviceId, address)?.let {
        SignalMismatchDto(
            refusedCount = it.count,
            expectedType = it.expectedType,
            receivedType = it.receivedType,
            lastAtMs = it.lastAtMs
        )
    }
)


/**
 * Four types, four wire tags, one for one.
 *
 * `enum` a disparu, jusqu'a la tolerance qu'on lui laissait. Il n'y a pas de
 * `TAG_ENUM` sur le fil : une carte n'envoie pas « un enum », elle envoie un
 * entier — c'etait donc un entier avec des etiquettes, une question de
 * presentation, pas un type. Et aucune liste d'etiquettes n'a jamais ete
 * stockee nulle part, ni ici ni cote app : il promettait une capacite que le
 * modele n'a pas.
 *
 * Une ligne qui porterait encore ce type est desormais refusee a l'ingestion,
 * `rankOfType` ne la connaissant plus. C'est assume : le type n'a jamais
 * quitte le banc d'essai.
 *
 * The app has hidden it from the creation form for a while already
 * (`DeviceSignalsContent`), so nothing user-facing changes here.
 */
private val KNOWN_TYPES = setOf(
    SignalTable.TYPE_INT, SignalTable.TYPE_FLOAT,
    SignalTable.TYPE_STRING
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
    broadcastToApps: (projectId: String, frame: ByteArray) -> Unit = { _, _ -> },
    /**
     * Ce que le plan du compte accorde par palier. Le défaut — vide — vaut
     * « on ne sait pas », pas « rien n'est accordé » : sans fichier de plans
     * (auto-hébergé) tout est servi sans annonce. Voir [SignalHistoryQuery].
     */
    historyWindows: (ownerId: String, nowMs: Long) -> List<HistoryWindows.Window> =
        { _, _ -> emptyList() },
    /**
     * La lecture d'un palier. Injectée plutôt qu'appelée en direct pour que
     * les tests de route n'aient pas à monter une base — et parce que la
     * route n'a aucune raison de savoir quel moteur la sert.
     */
    readHistory: (signalId: Long, ownerId: String, fromMs: Long, toMs: Long, resolution: String) -> List<SignalHistoryPoint> =
        { _, _, _, _, _ -> emptyList() }
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
            if (body.decimals !in 0..6) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("Decimals must be 0-6"))
            }
            if (body.minValue != null && body.maxValue != null && body.minValue >= body.maxValue) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("min must be < max"))
            }

            // The gate counts HISTORISED signals, not signals.
            //
            // Declaring an address costs one row that never grows; keeping a
            // trace of it costs 396 kB per day, for as long as the retention
            // says. Only the second is worth a limit.
            val willBeHistorised = body.historised
            if (willBeHistorised &&
                !policies.quotaGate(call, ownerId) { signals.countHistorised(ownerId) }
            ) return@post

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
                // Les deux interrupteurs sont la verite.
                //
                // « nature » etait un raccourci qui en ecrasait deux : declaree
                // action, une adresse ressortait sans historique et sans rejeu,
                // quoi qu'on ait demande — et le PATCH, lui, ne la regardait
                // pas. On cochait « conserver l'historique », le relais rangeait
                // false sans le dire ; on rouvrait, on enregistrait sans rien
                // changer, et true passait. Memes valeurs, deux resultats.
                historised = body.historised,
                replayOnConnect = body.replayOnConnect,
                // La visibilite dans l'editeur n'est PAS normalisee par la
                // nature : une action est meme le candidat type d'une regle
                // — « si la porte s'ouvre, allume ». La forcer a false
                // retirerait de l'editeur exactement ce qu'on veut y voir.
                automationVisible = body.automationVisible,
                nowMs = clock()
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
            // Turning historisation ON is a creation as far as the quota is
            // concerned. Without this, the ceiling would be trivially bypassed:
            // declare every signal unhistorised, then flip them one by one.
            val turningOn = body.historised == true && !existing.historised
            if (turningOn &&
                !policies.quotaGate(call, ownerId) { signals.countHistorised(ownerId) }
            ) return@patch

            signals.update(
                ownerId = ownerId, deviceId = deviceId, address = address,
                label = body.label?.trim(), unit = body.unit, decimals = body.decimals,
                minValue = body.minValue, maxValue = body.maxValue,
                historised = body.historised, replayOnConnect = body.replayOnConnect,
                automationVisible = body.automationVisible,
                type = body.type, nowMs = clock()
            )

            // A complaint describes a declaration. When the declaration changes,
            // the complaint is about a problem the user may have just fixed —
            // keeping it would warn them about their own solution. The board is
            // free to disagree again, and it will be recorded again.
            if (body.type != null && body.type != existing.type) {
                TypeMismatches.clear(deviceId, address)
            }

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

            val projectId = devices.findById(ownerId, deviceId)?.projectId
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

        // ============================================================
        // GET /api/devices/{deviceId}/signals/{address}/history
        //     ?from=&to=&resolution=
        //
        // `resolution` est OPTIONNEL — `auto` par défaut, et le serveur
        // choisit alors le palier qui rend ~1000 points pour la plage
        // demandée. Une app sans avis n'a rien à savoir des paliers.
        // (L'ancienne route widget avait `raw` pour défaut : le pire choix
        // possible sur six mois.)
        //
        // La réponse dit TOUJOURS ce qui a été servi, et pourquoi quand ça
        // diffère de la demande. Voir SignalHistoryQuery.
        // ============================================================
        get("/api/devices/{deviceId}/signals/{address}/history") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val deviceId = call.ownedDevice(devices, ownerId) ?: return@get
            val address = call.address() ?: return@get

            val signal = signals.find(ownerId, deviceId, address)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Signal not found"))

            val nowMs = clock()
            // Défauts utiles plutôt qu'obligatoires : sans `from`/`to`, on
            // sert les dernières 24 h. Une app qui ouvre un graphe n'a pas à
            // calculer une plage pour obtenir quelque chose à dessiner.
            val toMs = call.request.queryParameters["to"]?.toLongOrNull() ?: nowMs
            val fromMs = call.request.queryParameters["from"]?.toLongOrNull() ?: (toMs - 86_400_000L)
            val requested = call.request.queryParameters["resolution"] ?: SignalHistoryQuery.AUTO

            val decision = SignalHistoryQuery
                .resolve(requested, fromMs, toMs, historyWindows(ownerId, nowMs))
                .getOrElse {
                    return@get call.respond(HttpStatusCode.BadRequest, ApiError(it.message ?: "Bad resolution"))
                }

            val rows = readHistory(signal.id, ownerId, fromMs, toMs, decision.resolution)
            // Le plafond tronque et LE DIT. `raw` n'en avait aucun : 24 h
            // d'un signal à 1 Hz, c'est 86 400 objets sur le chemin de
            // réponse de l'app.
            val truncated = rows.size > SignalHistoryQuery.MAX_ROWS

            call.respond(
                HttpStatusCode.OK,
                SignalHistoryEnvelope(
                    // Gardé de l'ancienne route, et ce n'est pas un détail :
                    // l'app corrige sa dérive d'horloge avec. Le perdre se
                    // paierait en courbes décalées.
                    serverTimeMs = nowMs,
                    signal = "$deviceId:$address",
                    resolution = decision.resolution,
                    requested = requested.lowercase(),
                    notice = decision.notice,
                    truncated = truncated,
                    points = rows.take(SignalHistoryQuery.MAX_ROWS)
                )
            )
        }
    }
}

/** Un point d'historique — agrégé ou brut, selon la résolution servie. */
@Serializable
data class SignalHistoryPoint(
    /** Début du seau, ou instant de l'échantillon en `raw`. */
    val t: Long,
    /** Moyenne du seau, ou la valeur elle-même en `raw`. */
    val y: Double,
    /** `null` en `raw` — un échantillon n'a pas d'amplitude. */
    val yMin: Double? = null,
    val yMax: Double? = null,
    /**
     * Les instants exacts des extrêmes. Ils remontent inchangés dans la
     * cascade : une courbe en résolution jour peut nommer la seconde où le
     * minimum de l'année a eu lieu.
     */
    val minAt: Long? = null,
    val maxAt: Long? = null,
    /** Nombre d'échantillons agrégés. `null` en `raw`, où il vaut toujours 1. */
    val n: Int? = null
)

@Serializable
data class SignalHistoryEnvelope(
    val serverTimeMs: Long,
    val signal: String,
    /** Ce qui a été servi — toujours présent, choisi ou imposé. */
    val resolution: String,
    /** Ce qui avait été demandé, pour que l'app sache si elle a été suivie. */
    val requested: String,
    /** Non-null quand le résultat diffère de la demande. Jamais un silence. */
    val notice: String? = null,
    /** Le plafond de lignes a été atteint — la plage est plus large que la réponse. */
    val truncated: Boolean = false,
    val points: List<SignalHistoryPoint>
)

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
    val device = devices.findById(ownerId, deviceId)
    if (device == null) {
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
