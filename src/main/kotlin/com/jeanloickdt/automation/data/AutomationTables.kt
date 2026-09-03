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

package com.jeanloickdt.automation.data

import org.jetbrains.exposed.sql.Table

/**
 * The durable half of notifications & automations — the tables BEFORE the
 * engine, deliberately: [PendingActionTable] is the contract the whole
 * subsystem hangs from, and writing the engine first would mean writing it
 * against a semantics that does not exist yet, then rewriting it at the first
 * lost alert.
 *
 * All schema is additive (new tables only), so the boot migration is the
 * usual `createMissingTablesAndColumns`.
 */

/**
 * One rule = one row. `definition` is JSON the ENGINE owns — thresholds,
 * hysteresis, schedule, the actions to produce. The relational columns are
 * exactly the ones something else queries: the rule cache loads by
 * `(owner_id, trigger_signal_key)`, the REST API scopes by `owner_id`, the
 * scheduler filters by `trigger_kind`. Modelling the rest as columns would
 * freeze the rule vocabulary into the schema and cost a migration per new
 * operator — the engine's vocabulary must stay the engine's.
 */
object AutomationRuleTable : Table("automation_rules") {
    val id        = text("id")
    val ownerId   = text("owner_id")
    val name      = text("name")
    val enabled   = bool("enabled").default(true)

    /** `value` | `presence` | `stale` | `schedule` | `system` — which events feed it. */
    val triggerKind = text("trigger_kind")

    /**
     * La cle du signal surveille — `"deviceId:adresse"`, la meme que partout
     * ailleurs. Null pour une regle qui ne surveille aucun signal : un
     * horaire, la presence d'une carte.
     *
     * La colonne s'appelait `trigger_widget_id`. Le renommage se faisait au
     * demarrage du fabricant SQLite, disparu avec lui : la colonne porte son
     * nom actuel dans Postgres depuis la bascule.
     */
    val triggerSignalKey = text("trigger_signal_key").nullable()

    /** Engine-owned JSON: condition, hysteresis, cooldown, actions. */
    val definition = text("definition")

    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * The armed/triggered memory of each rule, PERSISTED — without it a nightly
 * restart re-arms the whole fleet and replays yesterday's alerts at dawn.
 * Separate from the rule row because it changes at data cadence while the
 * rule changes at human cadence: one hot row, one cold row.
 */
object AutomationStateTable : Table("automation_state") {
    val ruleId      = text("rule_id")
    val triggered   = bool("triggered").default(false)
    val lastFiredAt = long("last_fired_at").nullable()
    /** The value that armed/fired last — hysteresis needs the previous reading. */
    val lastValue   = double("last_value").nullable()
    val updatedAt   = long("updated_at")
    override val primaryKey = PrimaryKey(ruleId)
}

/**
 * ═══ THE DURABILITY FRONTIER ═══
 *
 * Everything before this table is best-effort RAM; everything after is
 * replayable. The boundary is one INSERT: after that line, a crash loses
 * nothing.
 *
 * `idempotency_key` carries the exactly-once-ish semantics and its UNIQUE
 * index is **the guarantee, not an optimisation**: the engine may evaluate
 * the same event twice (restart mid-batch), and the second INSERT must die on
 * the constraint rather than send the owner two pushes.
 *
 * The lease (`leased_until`) is what lets a crashed worker's rows be picked
 * up again: a worker takes a 5-minute lease, delivers, marks. If it dies
 * in between, the lease expires and another pass retries — which is exactly
 * why delivery semantics are per type (PUSH/EMAIL at-least-once, COMMAND
 * at-most-once; the worker enforces that, étape 4).
 */
object PendingActionTable : Table("pending_actions") {
    val id             = integer("id").autoIncrement()
    val idempotencyKey = text("idempotency_key")
    val ownerId        = text("owner_id")
    val ruleId         = text("rule_id").nullable()

    /** `PUSH` | `EMAIL` | `COMMAND` — decides the delivery semantics. */
    val type = text("type")

    /** Channel-owned JSON: push title/body, email fields, command frame. */
    val payload = text("payload")

    /** `PENDING` | `SENT` | `DEAD`. */
    val status = text("status").default("PENDING")

    val attempts      = integer("attempts").default(0)
    val nextAttemptAt = long("next_attempt_at")
    val leasedUntil   = long("leased_until").nullable()

    /** When the CAUSE happened — kept through retries, shown to the user. */
    val occurredAt = long("occurred_at")
    val createdAt  = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * The materialised "next fire" of schedule rules. The spec (cron-like, IANA
 * timezone) lives in the rule's `definition`; this row is only the answer to
 * "what is due?", so the scheduler's poll is an indexed range scan instead of
 * parsing every schedule every ten seconds. `next_run_at` is UTC epoch ms —
 * the timezone already did its work when the value was computed.
 */
object ScheduledJobTable : Table("scheduled_jobs") {
    val ruleId    = text("rule_id")
    val nextRunAt = long("next_run_at")
    /** IANA zone of the rule — re-materialisation crosses DST with it. */
    val timezone  = text("timezone")
    override val primaryKey = PrimaryKey(ruleId)
}

/**
 * Where a push lands: FCM tokens, one row per app install. Registered by the
 * app (`POST /api/push-tokens`, étape 6 côté app), consumed by the delivery
 * worker. A token FCM declares dead is deleted on the spot — a growing pile
 * of dead tokens is the classic silent push-rot.
 */
object PushTokenTable : Table("push_tokens") {
    val token     = text("token")
    val ownerId   = text("owner_id")
    /** `android` | `ios` — APNs-via-FCM needs to know. */
    val platform  = text("platform")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(token)
}

/**
 * The `messages.perMonth` flow counter (étape 0b). One row per account per
 * month — RAM-accumulated on the hot path, drained by the 5 s flush, so a
 * frame never costs a DB write. `period` is `"2026-08"`: the reset is a new
 * key, not an UPDATE that could race.
 */
object MessageUsageTable : Table("message_usage") {
    val ownerId = text("owner_id")
    val period  = text("period")
    val count   = long("count").default(0)
    override val primaryKey = PrimaryKey(ownerId, period)
}

object AutomationTables {
    /** Registered together everywhere the schema is built. */
    val ALL = arrayOf<Table>(
        AutomationRuleTable, AutomationStateTable, PendingActionTable,
        ScheduledJobTable, PushTokenTable, MessageUsageTable
    )
}
