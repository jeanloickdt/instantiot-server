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

package com.jeanloickdt.automation

import com.jeanloickdt.automation.data.AutomationRuleTable
import com.jeanloickdt.automation.data.AutomationStateTable
import com.jeanloickdt.automation.data.ScheduledJobTable
import com.jeanloickdt.common.ApiError
import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.relay.FrameParser
import com.jeanloickdt.signal.domain.SignalRepository
import io.ktor.http.HttpStatusCode
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
import io.ktor.server.application.ApplicationCall
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Le nom que ce champ porte SUR LE FIL — et qui dit enfin ce qu'il transporte :
 * une CLÉ DE SIGNAL (`deviceId:adresse`), jamais un widget.
 *
 * Le champ s'est appelé `triggerWidgetId` le temps où l'app ne parlait pas
 * encore à `/api/rules`. Aucune version publiée n'en a jamais lu ni écrit une
 * ligne — corrigé avant que l'écran naissant des règles ne fige le mauvais mot.
 */
private const val WIRE_TRIGGER = "triggerSignalKey"

@Serializable
data class RuleResponse(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val triggerKind: String,
    @SerialName(WIRE_TRIGGER) val triggerSignalKey: String?,
    /** The engine-owned JSON, verbatim — the app round-trips it untouched. */
    val definition: String,
    /** Live state, for the "currently firing" badge. */
    val triggered: Boolean,
    val lastFiredAt: Long?,
    val createdAtMs: Long
)

@Serializable
data class CreateRuleRequest(
    val name: String,
    /** Required for value/stale kinds; ignored for offline (device-keyed). */
    @SerialName(WIRE_TRIGGER) val triggerSignalKey: String? = null,
    val definition: String
)

@Serializable
data class UpdateRuleRequest(
    val name: String? = null,
    val enabled: Boolean? = null,
    @SerialName(WIRE_TRIGGER) val triggerSignalKey: String? = null,
    val definition: String? = null
)

/**
 * Les regles de decision, injectees plutot que codees en dur :
 *
 *  - [quotaGate] — les quotas du plan (`automations.max` /
 *    `notifications.max`), cable sur `enforceStock`. Le defaut laisse passer.
 *  - [allowedActionTypes] — la frontiere de l'OFFRE. Un type d'action qui
 *    n'est pas cable enfilerait des lignes que le livreur ne peut que marquer
 *    DEAD ; il est donc refuse A LA CREATION, avec un message qui dit
 *    pourquoi.
 */
class RulePolicies(
    /**
     * Le defaut EXCLUT `PUSH`, et c'est le point.
     *
     * L'appelant de ce depot le dit deja explicitement — voir `Application.kt`.
     * Mais le defaut, lui, listait les trois canaux : un appelant qui l'oublie
     * herite d'une permission qu'aucun expediteur ne peut honorer, et le
     * livreur ne peut alors que marquer DEAD, en silence.
     *
     * Ce n'est pas theorique : le depot du nuage a exactement fait cette faute.
     * Il avait perdu le parametre en recopiant ce cablage, et une regle
     * « previens-moi par notification » y etait acceptee (201), declenchee,
     * mise en file — puis jetee sans que l'utilisateur en sache rien. Pour une
     * alerte, c'est le pire mode de panne : on decouvre le silence apres
     * l'incident qu'on voulait eviter.
     *
     * Un defaut permissif se paie toujours du meme cote. Celui-ci echoue
     * desormais du cote sur.
     */
    val allowedActionTypes: Set<String> = setOf(
        RuleDefinition.TYPE_EMAIL, RuleDefinition.TYPE_COMMAND
    ),
    val quotaGate: suspend (call: ApplicationCall, ownerId: String, isAutomation: Boolean, current: () -> Int) -> Boolean =
        { _, _, _, _ -> true }
)

/**
 * CRUD over `automation_rules`, owner-scoped, with [RuleCache.reload] as the
 * single coupling point to the engine: every mutation ends with a reload, and
 * the producers' `watches()` gate flips in the same instant.
 */
fun Route.ruleRoutes(
    cache: RuleCache,
    signalRepository: SignalRepository,
    deviceRepository: DeviceRepository,
    policies: RulePolicies = RulePolicies()
) {
    authenticate("jwt") {

        get("/api/rules") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(HttpStatusCode.OK, listRules(ownerId))
        }

        post("/api/rules") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val body = call.receive<CreateRuleRequest>()

            val name = body.name.trim()
            if (name.length !in 1..64) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("Name must be 1-64 characters"))
            }

            val definition = when (val r = RuleDefinition.parse(body.definition)) {
                is RuleDefinition.Companion.ParseOutcome.Invalid ->
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid definition: ${r.reason}"))
                is RuleDefinition.Companion.ParseOutcome.Ok -> r.definition
            }

            validateRule(ownerId, body.triggerSignalKey, definition, signalRepository, deviceRepository, policies)
                ?.let { return@post call.respond(it.first, ApiError(it.second)) }

            // The quota: rules with a COMMAND action count as automations,
            // notification-only rules against the other line of the grid.
            val isAutomation = definition.actions.any { it.type == RuleDefinition.TYPE_COMMAND }
            val allowed = policies.quotaGate(call, ownerId, isAutomation) {
                countRules(ownerId, automation = isAutomation)
            }
            if (!allowed) return@post   // the gate answered (402)

            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            transaction {
                AutomationRuleTable.insert {
                    it[AutomationRuleTable.id]        = id
                    it[AutomationRuleTable.ownerId]   = ownerId
                    it[AutomationRuleTable.name]      = name
                    it[enabled]                       = true
                    it[triggerKind]                   = kindOf(definition)
                    it[triggerSignalKey]               = signalKeyFor(definition, body.triggerSignalKey)
                    it[AutomationRuleTable.definition] = body.definition
                    it[createdAt]                     = now
                    it[updatedAt]                     = now
                }
            }
            cache.reload()   // the producers' gate flips here
            materializeSchedule(id, definition, enabled = true)
            call.respond(HttpStatusCode.Created, listRules(ownerId).first { it.id == id })
        }

        patch("/api/rules/{id}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@patch call.respond(HttpStatusCode.Unauthorized)
            val ruleId = call.parameters["id"]
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiError("Missing rule id"))
            val body = call.receive<UpdateRuleRequest>()

            // Ownership first, 404 pattern — never reveal another tenant's rule.
            val existing = findRule(ownerId, ruleId)
                ?: return@patch call.respond(HttpStatusCode.NotFound, ApiError("Rule not found"))

            var newKind: String? = null
            var newTrigger: String? = null
            if (body.definition != null) {
                val definition = when (val r = RuleDefinition.parse(body.definition)) {
                    is RuleDefinition.Companion.ParseOutcome.Invalid ->
                        return@patch call.respond(HttpStatusCode.BadRequest, ApiError("Invalid definition: ${r.reason}"))
                    is RuleDefinition.Companion.ParseOutcome.Ok -> r.definition
                }
                val triggerArg = body.triggerSignalKey ?: existing.triggerSignalKey
                validateRule(ownerId, triggerArg, definition, signalRepository, deviceRepository, policies)
                    ?.let { return@patch call.respond(it.first, ApiError(it.second)) }
                newKind = kindOf(definition)
                newTrigger = signalKeyFor(definition, triggerArg)
            }

            transaction {
                AutomationRuleTable.update({
                    (AutomationRuleTable.id eq ruleId) and (AutomationRuleTable.ownerId eq ownerId)
                }) {
                    body.name?.let { n -> it[name] = n.trim() }
                    body.enabled?.let { e -> it[enabled] = e }
                    body.definition?.let { d -> it[definition] = d }
                    newKind?.let { k -> it[triggerKind] = k }
                    if (body.definition != null) it[triggerSignalKey] = newTrigger
                    it[updatedAt] = System.currentTimeMillis()
                }
            }
            cache.reload()
            // Re-materialise the next fire from the CURRENT row — definition
            // and enabled may both have changed.
            transaction {
                AutomationRuleTable.selectAll()
                    .where { AutomationRuleTable.id eq ruleId }
                    .singleOrNull()
            }?.let { row ->
                RuleDefinition.parseOrNull(ruleId, row[AutomationRuleTable.definition])?.let { d ->
                    materializeSchedule(ruleId, d, row[AutomationRuleTable.enabled])
                }
            }
            call.respond(HttpStatusCode.OK, listRules(ownerId).first { it.id == ruleId })
        }

        delete("/api/rules/{id}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)
            val ruleId = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("Missing rule id"))

            findRule(ownerId, ruleId)
                ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("Rule not found"))

            transaction {
                AutomationStateTable.deleteWhere { AutomationStateTable.ruleId eq ruleId }
                ScheduledJobTable.deleteWhere { ScheduledJobTable.ruleId eq ruleId }
                AutomationRuleTable.deleteWhere {
                    (AutomationRuleTable.id eq ruleId) and (AutomationRuleTable.ownerId eq ownerId)
                }
            }
            cache.reload()
            call.respond(HttpStatusCode.OK, mapOf("message" to "Rule deleted", "id" to ruleId))
        }
    }
}

// ── validation partagée création/édition ──────────────────────────────────

/**
 * Returns (status, message) to refuse with, or null when the rule is sound.
 * Everything here guards a mistake the ENGINE would otherwise pay for at
 * runtime — or a loop the user would pay for on real hardware.
 */
private fun validateRule(
    ownerId: String,
    triggerSignalKey: String?,
    definition: RuleDefinition,
    signals: SignalRepository,
    devices: DeviceRepository,
    policies: RulePolicies
): Pair<HttpStatusCode, String>? {
    // The OFFRE boundary: PUSH refused where no sender will ever exist.
    definition.actions.firstOrNull { it.type !in policies.allowedActionTypes }?.let {
        return HttpStatusCode.BadRequest to
            "Action type '${it.type}' is not available on this server (push requires InstantIoT Cloud)"
    }

    when (definition.trigger) {
        is Trigger.ValueThreshold, is Trigger.SignalStale -> {
            if (triggerSignalKey == null) {
                return HttpStatusCode.BadRequest to "This rule kind needs triggerSignalKey"
            }
            // La cible d'une règle est désormais une clé de SIGNAL —
            // `deviceId:adresse`, exactement ce que l'ingestion publie dans
            // le flux des règles. Une clé qui ne s'analyse pas est refusée
            // au lieu d'être cherchée : ce n'est pas un signal introuvable,
            // c'est une clé qui n'en a jamais été une.
            val parsed = com.jeanloickdt.signal.parseSignalKey(triggerSignalKey)
                ?: return HttpStatusCode.BadRequest to
                    "triggerSignalKey must be a signal key (deviceId:address)"
            if (signals.find(ownerId, parsed.first, parsed.second) == null) {
                // 404, jamais 403 — confirmer l'existence serait un bit de
                // l'inventaire de quelqu'un d'autre.
                return HttpStatusCode.NotFound to "Signal not found"
            }
        }
        is Trigger.DeviceOffline -> {
            // 404, jamais 403 — voir plus bas. La signature porte deja
            // l'appartenance : `findById` ne resout que ce qui est au bon compte.
            if (devices.findById(ownerId, definition.trigger.deviceId) == null) {
                // 404, not 403 — never reveal another tenant's device exists.
                return HttpStatusCode.NotFound to "Device not found"
            }
        }
        is Trigger.Schedule -> Unit   // the parse already validated at/tz/days
    }

    // COMMAND targets: same ownership reflex, at creation this time (the
    // engine re-checks at enqueue — defence in depth, not redundancy: a row
    // inserted outside this API must not command another tenant's board).
    definition.actions.filter { it.type == RuleDefinition.TYPE_COMMAND }.forEach { spec ->
        val deviceId = spec.params["deviceId"]?.jsonPrimitive?.content
            ?: return HttpStatusCode.BadRequest to "COMMAND needs deviceId"
        if (devices.findById(ownerId, deviceId) == null) {
            return HttpStatusCode.NotFound to "Device not found"
        }

        // La boucle la plus frequente, refusee a la porte : une COMMAND qui
        // ecrit le signal meme que la regle surveille — la regle se nourrit
        // elle-meme, en passant par le materiel, la ou `depth` ne peut pas
        // suivre.
        //
        // La comparaison se fait sur (carte, adresse), pas sur une chaine.
        // Le modele widget comparait le nom porte par la trame au declencheur
        // de la regle ; depuis que le declencheur est une cle de signal, ces
        // deux chaines ne peuvent plus etre egales et la garde ne se serait
        // plus jamais declenchee.
        val payloadB64 = spec.params["payloadB64"]?.jsonPrimitive?.content
        val watched = triggerSignalKey?.let { com.jeanloickdt.signal.parseSignalKey(it) }
        if (payloadB64 != null && watched != null && deviceId == watched.first) {
            val frame = runCatching { Base64.getDecoder().decode(payloadB64) }.getOrNull()
            if (frame != null && FrameParser.isValid(frame) &&
                com.jeanloickdt.signal.SignalFrame.address(frame) == watched.second
            ) {
                return HttpStatusCode.BadRequest to
                    "This COMMAND writes the signal the rule watches — the rule would trigger itself"
            }
        }
    }
    return null
}

/**
 * Keeps `scheduled_jobs` in step with the rule: the poll is an indexed range
 * scan over PRE-COMPUTED next fires, never a parse of every schedule every
 * ten seconds. A disabled or non-schedule rule simply has no row.
 */
private fun materializeSchedule(ruleId: String, definition: RuleDefinition, enabled: Boolean) {
    val trigger = definition.trigger as? Trigger.Schedule
    transaction {
        ScheduledJobTable.deleteWhere { ScheduledJobTable.ruleId eq ruleId }
        if (trigger != null && enabled) {
            ScheduledJobTable.insert {
                it[ScheduledJobTable.ruleId] = ruleId
                it[nextRunAt]                = ScheduleMath.nextRunAfter(System.currentTimeMillis(), trigger)
                it[timezone]                 = trigger.zone.id
            }
        }
    }
}

private fun kindOf(d: RuleDefinition): String = when (d.trigger) {
    is Trigger.ValueThreshold -> "value"
    is Trigger.DeviceOffline  -> "offline"
    is Trigger.SignalStale    -> "stale"
    is Trigger.Schedule       -> "schedule"
}

private fun signalKeyFor(d: RuleDefinition, arg: String?): String? =
    when (d.trigger) {
        is Trigger.DeviceOffline, is Trigger.Schedule -> null   // not widget-keyed
        else -> arg
    }

private fun findRule(ownerId: String, ruleId: String) = transaction {
    AutomationRuleTable.selectAll()
        .where { (AutomationRuleTable.id eq ruleId) and (AutomationRuleTable.ownerId eq ownerId) }
        .singleOrNull()
        ?.let { it[AutomationRuleTable.id] to it[AutomationRuleTable.triggerSignalKey] }
        ?.let { (id, w) -> object { val id = id; val triggerSignalKey = w } }
}

private fun countRules(ownerId: String, automation: Boolean): Int = transaction {
    AutomationRuleTable.selectAll()
        .where { AutomationRuleTable.ownerId eq ownerId }
        .count { row ->
            val def = RuleDefinition.parseOrNull(row[AutomationRuleTable.id], row[AutomationRuleTable.definition])
                ?: return@count false
            def.actions.any { it.type == RuleDefinition.TYPE_COMMAND } == automation
        }
}

private fun listRules(ownerId: String): List<RuleResponse> = transaction {
    val states = AutomationStateTable.selectAll().associate {
        it[AutomationStateTable.ruleId] to
            (it[AutomationStateTable.triggered] to it[AutomationStateTable.lastFiredAt])
    }
    AutomationRuleTable.selectAll()
        .where { AutomationRuleTable.ownerId eq ownerId }
        .map { row ->
            val state = states[row[AutomationRuleTable.id]]
            RuleResponse(
                id              = row[AutomationRuleTable.id],
                name            = row[AutomationRuleTable.name],
                enabled         = row[AutomationRuleTable.enabled],
                triggerKind     = row[AutomationRuleTable.triggerKind],
                triggerSignalKey = row[AutomationRuleTable.triggerSignalKey],
                definition      = row[AutomationRuleTable.definition],
                triggered       = state?.first ?: false,
                lastFiredAt     = state?.second,
                createdAtMs     = row[AutomationRuleTable.createdAt]
            )
        }
}
