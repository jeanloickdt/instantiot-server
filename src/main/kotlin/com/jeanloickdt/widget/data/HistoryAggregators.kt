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

// widget/data/HistoryAggregators.kt
package com.jeanloickdt.widget.data

/**
 * Globally accessible singleton holding the 3 aggregation tiers
 * in RAM (minute, hour, day).
 *
 * Fed by [com.jeanloickdt.relay.DeviceRelay.handleDeviceFrame]
 * as soon as a numeric sample arrives — the 3 tiers are updated in
 * parallel (each from the raw samples, no SQL cascade).
 *
 * Flushed periodically by the 5s job in `Application.kt` which calls
 * [TierAggregator.extractClosedBuckets] on each tier and persists
 * the closed buckets to the DB.
 *
 * **Why 3 independent tiers** (and no re-aggregation
 * hour ← minute, day ← hour like the old `HistoryAggregator`):
 *  - Each tier consumes the raw samples directly → perfect
 *    mathematical fidelity (the daily average accounts for
 *    every sample, not just averages of averages).
 *  - No dependency between tiers → if the admin disables raw,
 *    the 3 tiers keep working.
 *  - No deferred cascade → all tables are always up
 *    to date in real time (5s window).
 *
 * **Data lost on a hard crash** (without anti-crash snapshot):
 *  - raw (if enabled): 5s max (unflushed buffer)
 *  - minute: 1 min max (current bucket in RAM)
 *  - hour  : 1 h max  (current bucket in RAM)
 *  - day   : 24 h max (current bucket in RAM)
 *
 * The `ApplicationStopping` hook flushes EVERYTHING (including in-progress buckets)
 * → zero loss on a clean shutdown.
 *
 * **Anti-crash snapshot preparation** (future): the buckets contain
 * only primitive types → trivial serialization. A future
 * `SnapshotManager` will be able to read the state without modifying this code.
 */
object HistoryAggregators {
    /** Bucket = 1 minute. Feeds `widget_history_min`. */
    val minute = TierAggregator(bucketSizeMs = 60_000L)

    /** Bucket = 1 hour. Feeds `widget_history_hour`. */
    val hour = TierAggregator(bucketSizeMs = 3_600_000L)

    /** Bucket = 1 day. Feeds `widget_history_day`. */
    val day = TierAggregator(bucketSizeMs = 86_400_000L)
}