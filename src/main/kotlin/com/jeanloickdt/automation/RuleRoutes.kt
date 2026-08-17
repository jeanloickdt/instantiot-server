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
import com.jeanloickdt.widget.domain.WidgetRepository
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

@Serializable
data class RuleResponse(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val triggerKind: String,
    val triggerWidgetId: String?,
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
    val triggerWidgetId: String? = null,
    val definition: String
)

@Serializable
data class UpdateRuleRequest(
    val name: String? = null,
    val enabled: Boolean? = null,
    val triggerWidgetId: String? = null,
    val definition: String? = null
)

/**
 * Cloud-only concerns, injected so this file stays byte-identical across
 * editions:
 *
 *  - [quotaGate] — the plan quotas (`automations.max` / `notifications.max`).
 *    The plan module lives only in the cloud repo; the cloud wires this to
 *    `enforceStock`, the default always allows.
 *  - [allowedActionTypes] — the OFFRE boundary. Self-host wires
 *    `{EMAIL, COMMAND}`: a PUSH rule there would enqueue rows the worker can
 *    only mark DEAD (no Firebase credentials can ship in a public repo), so
 *    it is refused AT CREATION with a message that says why.
 */
class RulePolicies(
    val allowedActionTypes: Set<String> = setOf(
        RuleDefinition.TYPE_PUSH, RuleDefinition.TYPE_EMAIL, RuleDefinition.TYPE_COMMAND
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
    widgetRepository: WidgetRepository,
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

            validateRule(ownerId, body.triggerWidgetId, definition, widgetRepository, deviceRepository, policies)
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
                    it[triggerWidgetId]               = widgetIdFor(definition, body.triggerWidgetId)
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
            var newWidget: String? = null
            if (body.definition != null) {
                val definition = when (val r = RuleDefinition.parse(body.definition)) {
                    is RuleDefinition.Companion.ParseOutcome.Invalid ->
                        return@patch call.respond(HttpStatusCode.BadRequest, ApiError("Invalid definition: ${r.reason}"))
                    is RuleDefinition.Companion.ParseOutcome.Ok -> r.definition
                }
                val widgetArg = body.triggerWidgetId ?: existing.triggerWidgetId
                validateRule(ownerId, widgetArg, definition, widgetRepository, deviceRepository, policies)
                    ?.let { return@patch call.respond(it.first, ApiError(it.second)) }
                newKind = kindOf(definition)
                newWidget = widgetIdFor(definition, widgetArg)
            }

            transaction {
                AutomationRuleTable.update({
                    (AutomationRuleTable.id eq ruleId) and (AutomationRuleTable.ownerId eq ownerId)
                }) {
                    body.name?.let { n -> it[name] = n.trim() }
                    body.enabled?.let { e -> it[enabled] = e }
                    body.definition?.let { d -> it[definition] = d }
                    newKind?.let { k -> it[triggerKind] = k }
                    if (body.definition != null) it[triggerWidgetId] = newWidget
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
    triggerWidgetId: String?,
    definition: RuleDefinition,
    widgets: WidgetRepository,
    devices: DeviceRepository,
    policies: RulePolicies
): Pair<HttpStatusCode, String>? {
    // The OFFRE boundary: PUSH refused where no sender will ever exist.
    definition.actions.firstOrNull { it.type !in policies.allowedActionTypes }?.let {
        return HttpStatusCode.BadRequest to
            "Action type '${it.type}' is not available on this server (push requires InstantIoT Cloud)"
    }

    when (definition.trigger) {
        is Trigger.ValueThreshold, is Trigger.WidgetStale -> {
            if (triggerWidgetId == null) {
                return HttpStatusCode.BadRequest to "This rule kind needs triggerWidgetId"
            }
            if (widgets.findById(ownerId, triggerWidgetId) == null) {
                return HttpStatusCode.NotFound to "Widget not found"
            }
        }
        is Trigger.DeviceOffline -> {
            val d = devices.findById(definition.trigger.deviceId)
            if (d == null || d.ownerId != ownerId) {
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
        val device = devices.findById(deviceId)
        if (device == null || device.ownerId != ownerId) {
            return HttpStatusCode.NotFound to "Device not found"
        }

        // The most frequent loop, refused at the door: a COMMAND writing the
        // very widget the rule watches — the rule feeds itself through the
        // board. The command frame carries its widget id; decode and compare.
        val payloadB64 = spec.params["payloadB64"]?.jsonPrimitive?.content
        if (payloadB64 != null && triggerWidgetId != null) {
            val frame = runCatching { Base64.getDecoder().decode(payloadB64) }.getOrNull()
            if (frame != null && FrameParser.isValid(frame) &&
                FrameParser.extractWidgetId(frame) == triggerWidgetId
            ) {
                return HttpStatusCode.BadRequest to
                    "This COMMAND writes the widget the rule watches — the rule would trigger itself"
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
    is Trigger.WidgetStale    -> "stale"
    is Trigger.Schedule       -> "schedule"
}

private fun widgetIdFor(d: RuleDefinition, arg: String?): String? =
    when (d.trigger) {
        is Trigger.DeviceOffline, is Trigger.Schedule -> null   // not widget-keyed
        else -> arg
    }

private fun findRule(ownerId: String, ruleId: String) = transaction {
    AutomationRuleTable.selectAll()
        .where { (AutomationRuleTable.id eq ruleId) and (AutomationRuleTable.ownerId eq ownerId) }
        .singleOrNull()
        ?.let { it[AutomationRuleTable.id] to it[AutomationRuleTable.triggerWidgetId] }
        ?.let { (id, w) -> object { val id = id; val triggerWidgetId = w } }
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
                triggerWidgetId = row[AutomationRuleTable.triggerWidgetId],
                definition      = row[AutomationRuleTable.definition],
                triggered       = state?.first ?: false,
                lastFiredAt     = state?.second,
                createdAtMs     = row[AutomationRuleTable.createdAt]
            )
        }
}
