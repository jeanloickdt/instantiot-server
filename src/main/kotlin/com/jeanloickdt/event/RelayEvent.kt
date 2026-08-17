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

package com.jeanloickdt.event

/**
 * The one vocabulary every rule source speaks.
 *
 * The rules engine must react to things of very different natures — a value
 * arriving, a device disappearing, a sensor going *silent*, a clock striking.
 * Without a common vocabulary the engine would need one door and one format
 * per source, and every new source would be a rewrite of the engine. With it,
 * a rule triggers on "an event about my widget" and never asks where it came
 * from.
 *
 * ## NOT [com.jeanloickdt.relay.ControlEvent], on purpose
 *
 * They look alike and must never merge:
 *
 * | | `ControlEvent` | `RelayEvent` |
 * |---|---|---|
 * | reader | the mobile app, as JSON over WSS | the rules engine, in process |
 * | contract | **protocol** — deployed apps parse it | internal — free to evolve |
 *
 * Merging them would chain the engine's evolution to the Play Store release
 * cycle.
 *
 * ## `depth` is the loop guard
 *
 * "Tank > 90% → close the valve"; the valve closes, the sensor reports a new
 * value, another rule reopens it — a loop at machine speed, on real hardware.
 * Every event caused by a rule's action carries `depth + 1`, and the engine
 * refuses past a small limit. The field exists on every event from day one
 * because retrofitting it after the first incident would mean touching every
 * producer at once.
 *
 * ## `occurredAt` is when it HAPPENED
 *
 * Not when it was processed. A queue that backs up for two minutes must not
 * shift every timestamp by two minutes — hysteresis windows, cooldowns and
 * "silent for N minutes" all reason about the moment of the fact.
 */
sealed interface RelayEvent {
    /** Tenant isolation, same key as everywhere else in the relay. */
    val ownerId: String

    /** Epoch ms of the fact itself — not of its processing. */
    val occurredAt: Long

    /** 0 = caused by the outside world; +1 for each rule action in the chain. */
    val depth: Int

    // ── ① Data ────────────────────────────────────────────────────────────

    /** A numeric sample, already validated finite by the read path. */
    data class WidgetValue(
        override val ownerId: String,
        val widgetId: String,
        val seriesId: String?,
        val value: Double,
        override val occurredAt: Long,
        override val depth: Int = 0
    ) : RelayEvent

    /** A non-numeric payload — the opaque tier's cousin. */
    data class WidgetText(
        override val ownerId: String,
        val widgetId: String,
        val payloadBase64: String,
        override val occurredAt: Long,
        override val depth: Int = 0
    ) : RelayEvent

    // ── ② Presence ────────────────────────────────────────────────────────

    data class DeviceOnline(
        override val ownerId: String,
        val deviceId: String,
        override val occurredAt: Long,
        override val depth: Int = 0
    ) : RelayEvent

    data class DeviceOffline(
        override val ownerId: String,
        val deviceId: String,
        /** Same strings as [com.jeanloickdt.relay.DeviceOfflineReason]. */
        val reason: String,
        override val occurredAt: Long,
        override val depth: Int = 0
    ) : RelayEvent

    // ── ③ Absence — the alert IoT is really about ─────────────────────────

    /**
     * The sensor went quiet: still connected, or not, but no value for this
     * widget since [lastSeenAt]. The failure nobody sees on a dashboard —
     * a dashboard shows the last value, not the fact that it stopped moving.
     */
    data class WidgetStale(
        override val ownerId: String,
        val widgetId: String,
        val lastSeenAt: Long,
        override val occurredAt: Long,
        override val depth: Int = 0
    ) : RelayEvent

    // ── ④ Time ────────────────────────────────────────────────────────────

    /** A schedule fired. [scheduledFor] is the wall-clock target, not the wake-up. */
    data class TimeReached(
        override val ownerId: String,
        val ruleId: String,
        val scheduledFor: Long,
        override val occurredAt: Long,
        override val depth: Int = 0
    ) : RelayEvent

    // ── ⑤ System ──────────────────────────────────────────────────────────

    /** A plan limit was hit — the entitlement key names which one. */
    data class QuotaReached(
        override val ownerId: String,
        val right: String,
        override val occurredAt: Long,
        override val depth: Int = 0
    ) : RelayEvent

    /** A board was refused or evicted — flooding fuse, bad token after renewal… */
    data class DeviceRejected(
        override val ownerId: String,
        val deviceId: String,
        val reason: String,
        override val occurredAt: Long,
        override val depth: Int = 0
    ) : RelayEvent

    /** The subscription moved — upgrade, renewal, lapse into grace or expiry. */
    data class PlanChanged(
        override val ownerId: String,
        val plan: String,
        val state: String,
        override val occurredAt: Long,
        override val depth: Int = 0
    ) : RelayEvent
}
