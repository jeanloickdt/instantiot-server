/*
 * InstantIoT Server — self-hosted IoT relay for makers.
 * Copyright (C) 2026 InstantIoT
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

// widget/domain/WidgetModels.kt
package com.jeanloickdt.widget.domain

import kotlinx.serialization.Serializable

// ============================================================
// 📥 REQUESTS
// ============================================================

// Register a widget — called by the app when a widget is added
// The server only knows the id and the type — not the geometry nor the settings
@Serializable
data class RegisterWidgetRequest(
    val id: String,    // protocolId of the widget (matches what the device sends)
    val type: String   // kind of the widget ("Gauge", "SimpleButton", "HorizontalSlider", etc.)
)

// Batch register — called by the app after a layout save to make sure
// that ALL widgets of the layout are known on the server side, even those
// that have not yet received a device frame (App→Device sliders/buttons).
// Idempotent via `registerIfAbsent`.
@Serializable
data class BulkRegisterWidgetsRequest(
    val widgets: List<RegisterWidgetRequest>
)

@Serializable
data class BulkRegisterWidgetsResponse(
    val created: Int,   // number of newly inserted widgets
    val existing: Int   // number of widgets already in DB (no-op)
)

// ============================================================
// 📤 RESPONSES
// ============================================================

// State of a widget — last_payload for reconnection
// Returned by GET /api/projects/{id}/states
@Serializable
data class WidgetStateResponse(
    val widgetId: String,
    val payload: String?,  // null if never received from the ESP
    val lastSeenAt: Long?
)

// History of a widget — opaque payload by time range
// Returned by GET /api/widgets/{id}/history-raw?from=&to=
@Serializable
data class WidgetHistoryResponse(
    val payload: String,
    val recordedAt: Long
)

// Numeric point of a widget — for chart/gauge/metric/level/slider
// Returned by GET /api/widgets/{id}/history?from=&to=&seriesId=&granularity=
//
// For granularity=raw: yMin/yMax/count null (individual point).
// For granularity=min/hour/day: yMin/yMax/count populated (aggregated bucket).
@Serializable
data class WidgetHistoryPointResponse(
    val t: Long,                  // timestamp ms epoch (recordedAt for raw, bucket_at for aggregated)
    val y: Double,                // value for raw, avg for aggregated
    val seriesId: String? = null, // null for non-chart widgets
    val yMin: Double? = null,     // null for raw; bucket min for aggregated
    val yMax: Double? = null,     // null for raw; bucket max for aggregated
    val count: Int? = null        // null for raw; number of samples in the bucket
)

/**
 * Response envelope for `GET /api/widgets/{id}/history`.
 *
 * The [serverTimeMs] field lets the app compute its **clock skew**
 * (`serverTimeMs - app.now()` at reception time) and correct
 * the timestamps of live points arriving afterwards via WebSocket. Without
 * it, mixing server t (history) and app t (live) creates
 * visible jumps in the curve ("fishhook" at the history ↔ live
 * boundary) as soon as there is a >1s clock offset.
 */
@Serializable
data class WidgetHistoryEnvelope(
    val serverTimeMs: Long,
    val points: List<WidgetHistoryPointResponse>
)