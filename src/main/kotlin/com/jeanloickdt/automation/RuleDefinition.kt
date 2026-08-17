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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.max

private val logger = LoggerFactory.getLogger("RuleDefinition")

/**
 * What a rule reacts to. Three kinds in v1 — exactly the three producers that
 * already emit. `schedule` waits for the scheduler (étape 7).
 */
sealed interface Trigger {

    /**
     * A numeric threshold with hysteresis.
     *
     * [rearm] is the anti-flapping margin: triggered at [threshold], the rule
     * only re-arms once the value crosses BACK past [rearm]. A tank at
     * 89.8 / 90.1 / 89.9 crosses "above 90" three times in two seconds; with
     * rearm at 85 it fires once.
     *
     * Default margin: `max(2% · |threshold|, 0.01)`. The absolute floor covers
     * the degenerate threshold of zero — "below 0 °C" is a plausible rule and
     * 2 % of zero is zero. Sensor noise wider than the floor is the cooldown's
     * job; users near a noisy zero should set `rearm` explicitly.
     */
    data class ValueThreshold(
        val above: Boolean,
        val threshold: Double,
        val rearm: Double
    ) : Trigger

    /**
     * The device went silent — CONFIRMED. [afterMs] is the debounce: a board
     * bouncing on weak Wi-Fi disconnects and reconnects within seconds, and
     * without this delay every bounce is a push. The confirmation runs on the
     * sweeper tick, never as a delay in the engine — one offline rule must not
     * freeze every other rule for thirty seconds.
     *
     * The relay already waits heartbeat × 2.5 before emitting DeviceOffline
     * (12.5 s at a 5 s heartbeat); with the 30 s default the owner is warned
     * at ~42 s. Right for "the freezer got unplugged".
     */
    data class DeviceOffline(
        val deviceId: String,
        val afterMs: Long
    ) : Trigger

    /** The sensor stopped reporting — the sweeper detects, the rule reacts. */
    data object WidgetStale : Trigger
}

/**
 * One action to enqueue when the rule fires. [params] stays channel-owned
 * JSON: the engine renders the `{{value}}` template and checks COMMAND
 * ownership, and otherwise passes the object through to `pending_actions` —
 * the DeliveryWorker's senders know their own fields.
 */
data class ActionSpec(
    val type: String,
    val params: JsonObject
)

/**
 * The engine-owned half of a rule — everything the relational columns don't
 * carry. Parsed once at cache load, never on the hot path.
 */
data class RuleDefinition(
    val trigger: Trigger,
    val cooldownMs: Long,
    val actions: List<ActionSpec>
) {
    companion object {
        /** Anti-burst floor between two fires of the same rule. */
        const val DEFAULT_COOLDOWN_MS = 60_000L

        /** Offline confirmation debounce. */
        const val DEFAULT_OFFLINE_AFTER_MS = 30_000L

        const val HYSTERESIS_RATIO = 0.02
        const val HYSTERESIS_FLOOR = 0.01

        const val TYPE_PUSH    = DeliveryWorker.TYPE_PUSH
        const val TYPE_EMAIL   = DeliveryWorker.TYPE_EMAIL
        const val TYPE_COMMAND = DeliveryWorker.TYPE_COMMAND

        private val KNOWN_TYPES = setOf(TYPE_PUSH, TYPE_EMAIL, TYPE_COMMAND)

        fun defaultRearm(above: Boolean, threshold: Double): Double {
            val margin = max(HYSTERESIS_RATIO * abs(threshold), HYSTERESIS_FLOOR)
            return if (above) threshold - margin else threshold + margin
        }

        /** Parse, or the human reason it failed — the API's 400 carries it. */
        sealed interface ParseOutcome {
            data class Ok(val definition: RuleDefinition) : ParseOutcome
            data class Invalid(val reason: String) : ParseOutcome
        }

        /**
         * Parse, or `null` with a LOUD log. The same policy as `plans.json`:
         * broken data degrades ITS rule, never the engine — a crash here would
         * turn one malformed row into "no automations for anybody".
         */
        fun parseOrNull(ruleId: String, json: String): RuleDefinition? =
            when (val r = parse(json)) {
                is ParseOutcome.Ok -> r.definition
                is ParseOutcome.Invalid -> {
                    logger.error("Rule $ruleId has an invalid definition (${r.reason}) — IGNORED, not evaluated")
                    null
                }
            }

        fun parse(json: String): ParseOutcome = try {
            val root = Json.parseToJsonElement(json).jsonObject
            val whenNode = root["when"]?.jsonObject
                ?: return ParseOutcome.Invalid("missing 'when'")

            val trigger: Trigger = when (val kind = whenNode["kind"]?.jsonPrimitive?.content) {
                "value" -> {
                    val above = whenNode["above"]?.jsonPrimitive?.doubleOrNull
                    val below = whenNode["below"]?.jsonPrimitive?.doubleOrNull
                    if ((above == null) == (below == null)) {
                        return ParseOutcome.Invalid("'value' needs exactly one of above/below")
                    }
                    val isAbove = above != null
                    val threshold = above ?: below!!
                    val rearm = (if (isAbove) whenNode["rearmBelow"] else whenNode["rearmAbove"])
                        ?.jsonPrimitive?.doubleOrNull
                        ?: defaultRearm(isAbove, threshold)
                    // A rearm on the wrong side of the threshold disables the
                    // hysteresis entirely — refuse rather than flap.
                    if (isAbove && rearm >= threshold) return ParseOutcome.Invalid("rearmBelow must be < above")
                    if (!isAbove && rearm <= threshold) return ParseOutcome.Invalid("rearmAbove must be > below")
                    Trigger.ValueThreshold(isAbove, threshold, rearm)
                }

                "offline" -> {
                    val deviceId = whenNode["deviceId"]?.jsonPrimitive?.content
                        ?: return ParseOutcome.Invalid("'offline' needs deviceId")
                    val afterS = whenNode["afterS"]?.jsonPrimitive?.intOrNull
                    if (afterS != null && afterS < 0) return ParseOutcome.Invalid("afterS must be >= 0")
                    Trigger.DeviceOffline(deviceId, (afterS?.toLong() ?: (DEFAULT_OFFLINE_AFTER_MS / 1000)) * 1000)
                }

                "stale" -> Trigger.WidgetStale

                else -> return ParseOutcome.Invalid("unknown kind '$kind'")
            }

            val cooldownS = root["cooldownS"]?.jsonPrimitive?.intOrNull
            if (cooldownS != null && cooldownS < 0) return ParseOutcome.Invalid("cooldownS must be >= 0")

            val actions = root["actions"]?.jsonArray?.map { el ->
                val obj = el.jsonObject
                val type = obj["type"]?.jsonPrimitive?.content
                    ?: return ParseOutcome.Invalid("action without type")
                if (type !in KNOWN_TYPES) return ParseOutcome.Invalid("unknown action type '$type'")
                ActionSpec(type, JsonObject(obj.filterKeys { it != "type" }))
            } ?: emptyList()
            if (actions.isEmpty()) return ParseOutcome.Invalid("no actions — a rule that does nothing is a bug upstream")

            ParseOutcome.Ok(RuleDefinition(
                trigger    = trigger,
                cooldownMs = (cooldownS?.toLong() ?: (DEFAULT_COOLDOWN_MS / 1000)) * 1000,
                actions    = actions
            ))
        } catch (e: Exception) {
            ParseOutcome.Invalid(e.message ?: e::class.simpleName ?: "unparseable")
        }
    }
}
