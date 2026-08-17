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

import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.event.EventSinks
import com.jeanloickdt.event.RelayEvent
import com.jeanloickdt.relay.WidgetKey
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AutomationEngine")

/**
 * Consumes the two event channels, evaluates rules in RAM, writes rows.
 * Nothing else: no network, no sending — delivery belongs to the
 * [DeliveryWorker], on the other side of the durability frontier.
 *
 * ## One coroutine, no locks
 *
 * A single coroutine runs [run]; rule state is therefore mutated by one
 * thread, ever. The cost of 99 % of events is a map lookup and a comparison —
 * the disk is only touched at TRANSITIONS.
 *
 * ## Discrete events are never starved
 *
 * Kotlin's `select` is biased toward its first clause, and under a telemetry
 * flood the values branch would win almost always — starving exactly the rare
 * and important events. So the loop drains `discrete` COMPLETELY before each
 * pick from `values`, and `discrete` is the first select clause besides.
 *
 * ## The idempotency contract (the fact, not the detection)
 *
 * The dedup key derives from the TIME OF THE FACT, per kind:
 *
 * | kind | fact time |
 * |---|---|
 * | value | the sample's `occurredAt` |
 * | stale | `lastSeenAt` — the identity of the silence episode |
 * | offline | `pendingOfflineSince` — when the device vanished |
 *
 * If detection time were used, a re-detection (sweeper restart, replayed
 * event) would mint a fresh key and the owner would get one push per tick.
 * The unique index of étape 3 turns every such duplicate into a no-op INSERT.
 *
 * ## What `depth` does and does not cover
 *
 * Events synthesized by rule actions carry `depth + 1` and are refused past
 * [MAX_DEPTH] — that kills SOFTWARE loops. The PHYSICAL loop (command → valve
 * moves → sensor emits → new frame) cannot carry depth: the returning frame
 * is a fact of the world, depth 0. Against that one, the real defences are
 * hysteresis and cooldown.
 */
class AutomationEngine(
    private val sinks: EventSinks,
    private val cache: RuleCache,
    private val actions: PendingActionRepository,
    private val stateStore: SqliteAutomationStateStore,
    /** COMMAND targets are re-checked against the rule's owner — étape 9's
     *  CRUD will validate too, but a row inserted any other way must not be
     *  able to command another tenant's board. Same reflex as [WidgetKey]:
     *  never trust an identifier without its owner. */
    private val deviceRepository: DeviceRepository,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val depthRefused = AtomicLong(0)
    val depthRefusedCount: Long get() = depthRefused.get()

    /** The consuming loop — launch in its own coroutine. */
    suspend fun run() {
        while (true) {
            // Drain the rare-and-precious channel completely first.
            val event: RelayEvent = sinks.discrete.tryReceive().getOrNull()
                ?: sinks.values.tryReceive().getOrNull()
                ?: select {
                    sinks.discrete.onReceive { it }   // first clause = priority
                    sinks.values.onReceive { it }
                }
            try {
                handle(event)
            } catch (e: Exception) {
                // One bad rule/event must not kill everyone's automations.
                logger.error("Engine failed on ${event::class.simpleName} — event skipped", e)
            }
        }
    }

    /** Pure dispatch — public for tests, which feed events without channels. */
    fun handle(event: RelayEvent) {
        if (event.depth > MAX_DEPTH) {
            depthRefused.incrementAndGet()
            logger.warn("Event at depth ${event.depth} refused — a rule chain is looping")
            return
        }
        when (event) {
            is RelayEvent.WidgetValue -> {
                val key = WidgetKey(event.ownerId, event.widgetId)
                cache.valueRules(key).forEach { evaluateValue(it, event) }
                // The sensor spoke: any stale episode on this widget is over.
                cache.staleRules(key).forEach { rearmIfTriggered(it) }
            }

            is RelayEvent.WidgetStale -> {
                val key = WidgetKey(event.ownerId, event.widgetId)
                cache.staleRules(key).forEach { evaluateStale(it, event) }
            }

            is RelayEvent.DeviceOffline ->
                cache.offlineRules(event.ownerId, event.deviceId).forEach { rule ->
                    // Arm the confirmation — NEVER a delay here: one offline
                    // rule must not freeze every rule for thirty seconds. The
                    // sweeper tick confirms; DeviceOnline cancels for free.
                    if (!rule.state.triggered && rule.state.pendingOfflineSince == null) {
                        rule.state.pendingOfflineSince = event.occurredAt
                    }
                }

            is RelayEvent.DeviceOnline ->
                cache.offlineRules(event.ownerId, event.deviceId).forEach { rule ->
                    rule.state.pendingOfflineSince = null   // the bounce never fires
                    rearmIfTriggered(rule)
                }

            else -> Unit   // TimeReached, QuotaReached… — no rule kinds yet
        }
    }

    /**
     * Offline confirmations. Called by the periodic tick, not by the loop:
     * "still absent after [Trigger.DeviceOffline.afterMs]" is a fact about
     * elapsed time, and the engine never waits.
     */
    fun tick(nowMs: Long) {
        cache.allOfflineRules().forEach { rule ->
            val pendingSince = rule.state.pendingOfflineSince ?: return@forEach
            val trigger = rule.definition.trigger as? Trigger.DeviceOffline ?: return@forEach
            if (nowMs - pendingSince < trigger.afterMs) return@forEach

            // Still pending = no DeviceOnline cancelled it: the device is
            // genuinely gone. The fact time is when it VANISHED.
            rule.state.pendingOfflineSince = null
            rule.state.triggered = true
            if (cooldownOk(rule, pendingSince)) {
                fire(rule, factTime = pendingSince, value = null)
            } else {
                stateStore.save(rule.id, rule.state, clock())
            }
        }
    }

    // ── Évaluations ───────────────────────────────────────────────────────

    private fun evaluateValue(rule: LoadedRule, event: RelayEvent.WidgetValue) {
        val t = rule.definition.trigger as? Trigger.ValueThreshold ?: return
        val state = rule.state
        state.lastValue = event.value

        if (state.triggered) {
            // Hysteresis: only a crossing BACK past the rearm line re-arms.
            val rearmed = if (t.above) event.value <= t.rearm else event.value >= t.rearm
            if (rearmed) {
                state.triggered = false
                stateStore.save(rule.id, state, clock())
            }
            return
        }

        val crossed = if (t.above) event.value > t.threshold else event.value < t.threshold
        if (!crossed) return

        // The crossing latches the rule EVEN when the cooldown swallows the
        // alert: a sustained condition is one episode, not one alert per
        // sample after the cooldown expires.
        state.triggered = true
        if (cooldownOk(rule, event.occurredAt)) {
            fire(rule, factTime = event.occurredAt, value = event.value)
        } else {
            stateStore.save(rule.id, state, clock())
        }
    }

    private fun evaluateStale(rule: LoadedRule, event: RelayEvent.WidgetStale) {
        if (rule.state.triggered) return   // the episode was already told
        rule.state.triggered = true
        if (cooldownOk(rule, event.lastSeenAt)) {
            // The fact is the EPISODE — keyed by lastSeenAt, so a sweeper
            // restart re-reporting the same silence dedups on the index.
            fire(rule, factTime = event.lastSeenAt, value = null)
        } else {
            stateStore.save(rule.id, rule.state, clock())
        }
    }

    private fun rearmIfTriggered(rule: LoadedRule) {
        if (rule.state.triggered) {
            rule.state.triggered = false
            stateStore.save(rule.id, rule.state, clock())
        }
    }

    private fun cooldownOk(rule: LoadedRule, factTime: Long): Boolean {
        val last = rule.state.lastFiredAt ?: return true
        return factTime - last >= rule.definition.cooldownMs
    }

    // ── Le tir : la seule écriture du moteur ──────────────────────────────

    private fun fire(rule: LoadedRule, factTime: Long, value: Double?) {
        val now = clock()
        rule.state.lastFiredAt = factTime
        transaction {
            rule.definition.actions.forEachIndexed { i, spec ->
                if (!targetAllowed(rule, spec)) return@forEachIndexed
                val enqueued = actions.enqueue(
                    idempotencyKey = "${rule.id}:$factTime:$i",
                    ownerId        = rule.ownerId,
                    ruleId         = rule.id,
                    type           = spec.type,
                    payload        = render(spec.params, value).toString(),
                    occurredAt     = factTime,
                    nowMs          = now
                )
                if (!enqueued) {
                    // The unique index spoke: this exact fact was already
                    // enqueued. The system working, not failing.
                    logger.debug("Duplicate fire deduped — rule=${rule.id} fact=$factTime")
                }
            }
            stateStore.save(rule.id, rule.state, now)
        }
        logger.info("Rule fired — id=${rule.id} owner=${rule.ownerId} actions=${rule.definition.actions.size}")
    }

    /** COMMAND may only target a board of the RULE'S owner. */
    private fun targetAllowed(rule: LoadedRule, spec: ActionSpec): Boolean {
        if (spec.type != RuleDefinition.TYPE_COMMAND) return true
        val deviceId = spec.params["deviceId"]?.jsonPrimitive?.content
        if (deviceId == null) {
            logger.error("Rule ${rule.id}: COMMAND without deviceId — action skipped")
            return false
        }
        val device = deviceRepository.findById(deviceId)
        if (device == null || device.ownerId != rule.ownerId) {
            logger.error(
                "Rule ${rule.id}: COMMAND targets device $deviceId which is not owned by " +
                    "${rule.ownerId} — action SKIPPED (cross-tenant command refused)"
            )
            return false
        }
        return true
    }

    /** `{{value}}` in string params becomes the sample that fired the rule. */
    private fun render(params: JsonObject, value: Double?): JsonObject {
        if (value == null) return params
        val text = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        return JsonObject(params.mapValues { (_, v) ->
            val p = v as? JsonPrimitive ?: return@mapValues v
            if (p.isString && "{{value}}" in p.content) JsonPrimitive(p.content.replace("{{value}}", text)) else v
        })
    }

    companion object {
        /** Past this, a rule chain is feeding itself — refuse and count. */
        const val MAX_DEPTH = 3
    }
}
