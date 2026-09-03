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

import com.jeanloickdt.relay.LastValueCache
import com.jeanloickdt.relay.SignalRef

/**
 * "The sensor went quiet" — the alert IoT is really about.
 *
 * A dashboard shows the last value, not the fact that it stopped moving: a
 * frozen gauge reading 22 °C looks exactly like a healthy one. The failure
 * nobody sees is the one worth paying for.
 *
 * This looked like a subsystem in the work order. It is a periodic read of
 * [LastValueCache] — the per-widget timestamp has been there since the cache
 * was born. The only real logic is the **episode** semantics below.
 *
 * ## One alert per silence, not one per sweep
 *
 * A sweep runs every minute; a silent sensor stays silent for hours. Without
 * memory, the owner gets the same alert sixty times a night. The sweeper
 * therefore remembers the `lastSeenAt` it reported for each widget:
 *
 *  - same `lastSeenAt` as reported  → same silence, already told — skip
 *  - newer `lastSeenAt`             → the sensor spoke since; the episode is
 *                                     over, forget it — a NEW silence may fire
 *
 * The memory is keyed by what the CACHE says, not by wall-clock cooldowns, so
 * a sweeper restart re-reports ongoing silences at most once — annoying,
 * never wrong — and no state needs persisting.
 *
 * ## Who decides what is watched
 *
 * [watched] comes from the caller — ultimately the rule cache. The sweeper
 * never scans every widget: silence only means something if a rule asked
 * about it, and the watched set numbers in the hundreds where the cache holds
 * tens of thousands.
 *
 * Not thread-safe, deliberately: one instance, driven by one periodic loop.
 */
class SignalStaleSweeper(
    private val cache: LastValueCache,
    private val sinks: EventSinks,
    /** Silence threshold. A rule-level per-widget threshold can come later. */
    private val silenceMs: Long = DEFAULT_SILENCE_MS
) {
    /** widget → the lastSeenAt we already alerted on. */
    private val reported = HashMap<SignalRef, Long>()

    /** @return how many [RelayEvent.SignalStale] were published this pass. */
    fun sweep(watched: Set<SignalRef>, nowMs: Long): Int {
        // Widgets that left the watched set must not pin memory forever.
        reported.keys.retainAll(watched)

        val stale = cache.staleSince(nowMs - silenceMs, watched)

        // A widget that spoke again is absent from `stale` — dropping it here
        // closes its episode, so the NEXT silence can fire.
        reported.keys.retainAll(stale.map { it.first }.toSet())

        var published = 0
        stale.forEach { (key, lastSeenAt) ->
            if (reported[key] == lastSeenAt) return@forEach   // same silence, already told

            reported[key] = lastSeenAt
            sinks.publish(
                RelayEvent.SignalStale(
                    ownerId = key.ownerId,
                    signalKey = key.key,
                    lastSeenAt = lastSeenAt,
                    occurredAt = nowMs
                )
            )
            published++
        }
        return published
    }

    companion object {
        /** 15 min: long enough for any sane reporting cadence, short enough to matter. */
        const val DEFAULT_SILENCE_MS = 15 * 60_000L
    }
}
