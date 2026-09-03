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

package com.jeanloickdt.retention


import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.not
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less

/**
 * The one implementation of [RetentionSweep], shared by the five history
 * tiers. Written once because five copies of "delete older than, except for
 * these owners" is five chances to invert a condition and silently erase a
 * paying account's data.
 *
 * All five tables carry `owner_id` without a join
 * ([WidgetHistoryTable], [WidgetHistoryNumericTable] and the aggregates), so
 * this is a `WHERE`, not a restructuring. And the sweep already scanned the
 * whole table — the indexes lead with `widget_id`, never with the timestamp
 * alone — so adding an owner filter does not change its cost class.
 *
 * @return rows deleted, for the log.
 */
internal fun Table.sweepRetention(
    sweep: RetentionSweep,
    timestamp: Column<Long>,
    ownerId: Column<String>
): Int {
    if (sweep.isNoop) return 0
    var deleted = 0

    // Pass 1 — everybody on the default duration. The exceptions are excluded
    // rather than enumerated: paying accounts are the minority, so this list
    // stays short no matter how many accounts exist.
    sweep.defaultCutoffMs?.let { cutoff ->
        val exceptions = sweep.exceptions
        deleted += deleteWhere {
            if (exceptions.isEmpty()) timestamp less cutoff
            else (timestamp less cutoff) and not(ownerId inList exceptions)
        }
    }

    // Pass 2 — one statement per distinct duration, not per account.
    sweep.overrides.forEach { override ->
        val cutoff = override.cutoffMs ?: return@forEach   // null = keep forever
        if (override.ownerIds.isEmpty()) return@forEach
        deleted += deleteWhere { (timestamp less cutoff) and (ownerId inList override.ownerIds) }
    }

    return deleted
}
