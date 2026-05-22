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

// relay/ControlEvent.kt
package com.jeanloickdt.relay

import kotlinx.serialization.Serializable

/**
 * Control events sent to the apps via Frame.Text (JSON) on WebSocket /ws/app.
 *
 * App WS protocol :
 *   - Frame.Text handshake : projectId (1st message)
 *   - Frame.Binary : iWidgets v1 frames (device data, 5-100 Hz)
 *   - Frame.Text {"type": "..."} : control events (after handshake)
 *
 * The app distinguishes text vs binary to route between the iWidgets parser and the event parser.
 *
 * Event types :
 *   - "device_online"   : an ESP device has just connected over TCP
 *   - "device_offline"  : an ESP device has disconnected (disconnect / token_renewed / deleted)
 *   - "command_failed"  : an App->Device command has failed (device_offline / forbidden / relay_error)
 *   - "bucket_updated"  : an aggregation bucket has just closed (min/hour/day),
 *                         emitted when the RAM aggregator flushes to DB. Lets charts
 *                         in historical preset mode update their window without
 *                         re-fetching over HTTP (Blynk SuperChart pattern).
 */
@Serializable
data class ControlEvent(
    val type: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val reason: String? = null,           // reason for device_offline and command_failed
    // ─── BUCKET_UPDATED fields ─────────────────────────────
    val widgetId: String? = null,
    val seriesId: String? = null,
    val bucketAt: Long? = null,           // ms epoch, start of the bucket
    val avg: Double? = null,              // weighted average of the bucket
    val min: Double? = null,              // min value of the bucket
    val max: Double? = null,              // max value of the bucket
    val count: Int? = null,               // number of aggregated samples
    val granularity: String? = null       // "minute" | "hour" | "day"
)

/**
 * Event types — constants to avoid typos.
 */
object ControlEventType {
    const val DEVICE_ONLINE   = "device_online"
    const val DEVICE_OFFLINE  = "device_offline"
    const val COMMAND_FAILED  = "command_failed"
    const val BUCKET_UPDATED  = "bucket_updated"
}

/**
 * Bucket granularities — aligned with the HistoryAggregators tiers.
 */
object BucketGranularity {
    const val MINUTE = "minute"
    const val HOUR   = "hour"
    const val DAY    = "day"
}

/**
 * Reasons for a device_offline.
 */
object DeviceOfflineReason {
    const val DISCONNECTED  = "disconnected"     // normal TCP disconnect (network loss, socket closed)
    const val TOKEN_RENEWED = "token_renewed"    // admin regenerated the token → old one kicked
    const val DELETED       = "deleted"          // admin deleted the device
}

/**
 * Reasons for a command_failed.
 */
object CommandFailedReason {
    const val DEVICE_OFFLINE = "device_offline"  // TCP session absent/closed
    const val FORBIDDEN      = "forbidden"       // device belongs to another user
    const val RELAY_ERROR    = "relay_error"     // exception during the TCP write
}