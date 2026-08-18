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

package com.jeanloickdt.signal.data

import org.jetbrains.exposed.sql.Table

/**
 * A **signal** — the value contract between a board and everything that reads
 * it. See `PROTOCOLE-2.0.md`.
 *
 * ## The key is (owner, device, address)
 *
 * The address space `I0..I255` is enumerated **per board**, so each sketch is
 * written without knowing what the other boards use. That is also why the wire
 * carries one byte and no board identity: the connection is already
 * authenticated by the device token, so the relay knows who is speaking.
 *
 * `owner_id` is in the key for the same reason it was in `widgets`: addresses
 * collide across tenants, and a shorter key would let one account's read spill
 * another's data.
 *
 * ## What lives here and not on a widget
 *
 * Unit, min/max, type: several widgets share one signal, so anything that must
 * look the same to all of them belongs here. Decimals, labels and colors stay on
 * the widget — they are drawing, not data.
 *
 * ## What deliberately does NOT live here
 *
 * **No send rate.** It is not a per-signal setting: the only throttle is the
 * platform's `caps["messages.perSecond"]`, the same number that already feeds
 * the fuse, pushed to the board so it stops before being disconnected.
 *
 * **No history tier.** [historised] is a boolean. The minute/hour/day cascade is
 * internal and automatic; `raw` is a plan capability, not a choice.
 */
object SignalTable : Table("signals") {

    val ownerId  = text("owner_id")
    val deviceId = text("device_id")
    /** `0..255` — what travels on the wire, one byte. Rendered `I0`..`I255`. */
    val address  = integer("address")

    /** The human name. Never on the wire: "Température serre". */
    val label    = text("label")

    /** `bool` · `int` · `float` · `string` · `enum` — validation and decoding. */
    val type     = text("type")
    val unit     = text("unit").default("")
    /** Default decimals; a widget may show more or fewer. */
    val decimals = integer("decimals").default(1)

    /** Bounds are a CLAMP, not a rejection — a sensor spike must not vanish. */
    val minValue = double("min_value").nullable()
    val maxValue = double("max_value").nullable()

    /**
     * Do we keep a trace of this signal?
     *
     * `true` feeds the minute/hour/day cascade — measured at 275 bytes per
     * minute row, so **~35,6 MB per signal per 90 days**, of which the minute
     * tier is 98%. `false` keeps only [lastPayload], which costs nothing.
     *
     * There is no tier to pick: the cascade is internal, and `raw` is granted
     * by the plan.
     */
    val historised = bool("historised").default(true)

    /** `measure` (board writes) · `setpoint` (app writes) · `both`. */
    val direction = text("direction").default(DIRECTION_MEASURE)

    /** Last value seen, base64 payload — written by the relay only. */
    val lastPayload = text("last_payload").nullable()
    /** Timestamp of the last frame from the board — written by the relay only. */
    val lastSeenAt  = long("last_seen_at").nullable()

    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(ownerId, deviceId, address)

    const val DIRECTION_MEASURE  = "measure"
    const val DIRECTION_SETPOINT = "setpoint"
    const val DIRECTION_BOTH     = "both"

    const val TYPE_BOOL   = "bool"
    const val TYPE_INT    = "int"
    const val TYPE_FLOAT  = "float"
    const val TYPE_STRING = "string"
    const val TYPE_ENUM   = "enum"

    /** The address space, per board — one byte on the wire. */
    const val ADDRESS_MIN = 0
    const val ADDRESS_MAX = 255

    /** `5` → `"I5"`. The wire never carries this form; humans and sketches do. */
    fun render(address: Int): String = "I$address"
}
