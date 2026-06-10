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

// relay/HistoryBuffers.kt
package com.jeanloickdt.relay

import java.util.concurrent.ConcurrentLinkedQueue

// History entry — buffer before SQLite flush
data class HistoryEntry(
    val widgetId: WidgetId,
    val projectId: String,
    val ownerId: String,
    val payload: String,
    val recordedAt: Long
)

// **Numeric** history entry — buffer before SQLite flush.
// Populated in parallel with HistoryEntry when FrameParser.extractNumericValue
// returns a decodable sample (gauge/metric/level/slider/chart).
data class NumericHistoryEntry(
    val widgetId: WidgetId,
    val projectId: String,
    val ownerId: String,
    val seriesId: String?,
    val value: Double,
    val recordedAt: Long
)

/**
 * RAM staging area of the ingest pipeline: what the device read-loop writes
 * per frame, and what the 5s flush job drains into SQLite. The read path
 * never touches the DB — these queues are the decoupling.
 */
class HistoryBuffers {

    // history buffer — flushed every 5s to SQLite WAL batch
    val historyBuffer = ConcurrentLinkedQueue<HistoryEntry>()

    // numeric history buffer — populated ONLY if the admin enabled
    // the raw tier (ServerConfig.historyRawEnabled). Flushed every 5s to
    // widget_history_numeric.
    val numericHistoryBuffer = ConcurrentLinkedQueue<NumericHistoryEntry>()

    // RAM cache of widgetIds (= protocolId) already known in the DB.
    // Used by auto-register in DeviceRelay: a widgetId already in
    // the Set → no DB hit, otherwise INSERT OR IGNORE + add to the Set.
    // Evicted on widget delete (WidgetRoutes) so a deleted widget
    // re-registers on its next frame.
    val knownWidgetIds: MutableSet<WidgetId> = java.util.concurrent.ConcurrentHashMap.newKeySet()
}
