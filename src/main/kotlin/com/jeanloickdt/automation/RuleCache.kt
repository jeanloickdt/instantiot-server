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
import com.jeanloickdt.relay.WidgetKey
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("RuleCache")

/**
 * A rule's live memory. Mutated by the ENGINE ONLY — one coroutine, so no
 * synchronisation. Persisted at transitions so a nightly restart neither
 * re-arms the fleet nor replays yesterday's alerts.
 *
 * [pendingOfflineSince] is deliberately RAM-only: a restart inside the 30 s
 * offline-confirmation window drops that one confirmation. Accepted — the
 * alternative is a schema column for a sub-minute edge nobody will hit twice.
 */
class RuleState(
    var triggered: Boolean = false,
    var lastFiredAt: Long? = null,
    var lastValue: Double? = null,
    var pendingOfflineSince: Long? = null
)

/** One enabled rule, definition parsed, state loaded. */
class LoadedRule(
    val id: String,
    val ownerId: String,
    /** The widget for value/stale kinds; null for offline (device-keyed). */
    val widgetId: String?,
    val definition: RuleDefinition,
    val state: RuleState
)

/**
 * The rules, in RAM, indexed the way the hot path asks.
 *
 * ## Concurrency: an immutable snapshot behind one @Volatile field
 *
 * The cache is READ by every device-read coroutine (the `watches` gate, once
 * per frame) and WRITTEN by rule mutations. "Zero locks" holds for the
 * engine, not here — so writers rebuild a complete [Index] and swap the
 * reference; readers see either the old world or the new one, never a torn
 * one. Reload cost is proportional to the rule count (hundreds), and rule
 * mutations happen at human cadence.
 *
 * ## The producer gate, finally live
 *
 * [watches] is what replaces the `{ false }` predicate wired at étape 2: the
 * moment a rule lands in the table and [reload] runs, the relay starts
 * publishing values for that widget — and only that widget. Zero rules =
 * everything stays exactly as it is today.
 */
class RuleCache {

    class Index(
        val valueByWidget: Map<WidgetKey, List<LoadedRule>>,
        val staleByWidget: Map<WidgetKey, List<LoadedRule>>,
        val offlineByDevice: Map<Pair<String, String>, List<LoadedRule>>,
        val scheduleById: Map<String, LoadedRule>
    ) {
        val ruleCount: Int =
            valueByWidget.values.sumOf { it.size } + staleByWidget.values.sumOf { it.size } +
                offlineByDevice.values.sumOf { it.size } + scheduleById.size
    }

    @Volatile
    private var index = Index(emptyMap(), emptyMap(), emptyMap(), emptyMap())

    /** The hot-path gate — one volatile read and two map lookups. */
    fun watches(key: WidgetKey): Boolean {
        val i = index
        return key in i.valueByWidget || key in i.staleByWidget
    }

    /** The stale sweeper's watched set. */
    fun watchedStaleKeys(): Set<WidgetKey> = index.staleByWidget.keys

    fun valueRules(key: WidgetKey): List<LoadedRule> = index.valueByWidget[key] ?: emptyList()
    fun staleRules(key: WidgetKey): List<LoadedRule> = index.staleByWidget[key] ?: emptyList()
    fun offlineRules(ownerId: String, deviceId: String): List<LoadedRule> =
        index.offlineByDevice[ownerId to deviceId] ?: emptyList()

    /** Every offline rule — the sweeper tick walks these for confirmations. */
    fun allOfflineRules(): List<LoadedRule> = index.offlineByDevice.values.flatten()

    /** The schedule rule behind a TimeReached event. */
    fun scheduleRule(ruleId: String): LoadedRule? = index.scheduleById[ruleId]

    /**
     * Rebuild the snapshot from the tables. Called at boot and after every
     * rule mutation (the CRUD's single coupling point). Invalid definitions
     * are skipped loudly by the parser and simply absent from the index.
     */
    fun reload() {
        val value = HashMap<WidgetKey, MutableList<LoadedRule>>()
        val stale = HashMap<WidgetKey, MutableList<LoadedRule>>()
        val offline = HashMap<Pair<String, String>, MutableList<LoadedRule>>()
        val schedule = HashMap<String, LoadedRule>()

        transaction {
            val states = AutomationStateTable.selectAll().associate {
                it[AutomationStateTable.ruleId] to RuleState(
                    triggered   = it[AutomationStateTable.triggered],
                    lastFiredAt = it[AutomationStateTable.lastFiredAt],
                    lastValue   = it[AutomationStateTable.lastValue]
                )
            }

            AutomationRuleTable.selectAll()
                .where { AutomationRuleTable.enabled eq true }
                .forEach { row ->
                    val id = row[AutomationRuleTable.id]
                    val def = RuleDefinition.parseOrNull(id, row[AutomationRuleTable.definition])
                        ?: return@forEach
                    val rule = LoadedRule(
                        id         = id,
                        ownerId    = row[AutomationRuleTable.ownerId],
                        widgetId   = row[AutomationRuleTable.triggerWidgetId],
                        definition = def,
                        state      = states[id] ?: RuleState()
                    )
                    when (val t = def.trigger) {
                        is Trigger.ValueThreshold -> rule.widgetId?.let {
                            value.getOrPut(WidgetKey(rule.ownerId, it)) { mutableListOf() }.add(rule)
                        } ?: logger.error("Rule $id: kind 'value' without trigger_widget_id — ignored")

                        is Trigger.WidgetStale -> rule.widgetId?.let {
                            stale.getOrPut(WidgetKey(rule.ownerId, it)) { mutableListOf() }.add(rule)
                        } ?: logger.error("Rule $id: kind 'stale' without trigger_widget_id — ignored")

                        is Trigger.DeviceOffline ->
                            offline.getOrPut(rule.ownerId to t.deviceId) { mutableListOf() }.add(rule)

                        is Trigger.Schedule -> schedule[rule.id] = rule
                    }
                }
        }

        index = Index(value, stale, offline, schedule)
        if (index.ruleCount > 0) logger.info("Rule cache reloaded — ${index.ruleCount} enabled rule(s)")
    }
}

/**
 * Persists a rule's transition memory. Upsert by rule id — the row is created
 * on the first transition, not at rule creation, so a rule that never fires
 * costs no row.
 */
class SqliteAutomationStateStore {
    fun save(ruleId: String, state: RuleState, nowMs: Long) {
        transaction {
            val updated = AutomationStateTable.update({ AutomationStateTable.ruleId eq ruleId }) {
                it[triggered]   = state.triggered
                it[lastFiredAt] = state.lastFiredAt
                it[lastValue]   = state.lastValue
                it[updatedAt]   = nowMs
            }
            if (updated == 0) {
                AutomationStateTable.insert {
                    it[AutomationStateTable.ruleId]      = ruleId
                    it[AutomationStateTable.triggered]   = state.triggered
                    it[AutomationStateTable.lastFiredAt] = state.lastFiredAt
                    it[AutomationStateTable.lastValue]   = state.lastValue
                    it[AutomationStateTable.updatedAt]   = nowMs
                }
            }
        }
    }
}
