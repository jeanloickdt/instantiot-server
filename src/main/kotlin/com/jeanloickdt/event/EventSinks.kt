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

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("EventSinks")

/**
 * The two bounded channels between the hot path and the rules engine.
 *
 * Two, not one — the diagram that promised a single channel "bounded AND never
 * loses" promised a contradiction. Bounded and lossless cannot coexist without
 * spilling to disk, which would put I/O right back next to the hot path. The
 * honest answer is the [com.jeanloickdt.relay.AppOutbox] answer: split by
 * nature, because the drop policies genuinely differ.
 *
 * | channel | carries | when full |
 * |---|---|---|
 * | [values] | [RelayEvent.WidgetValue] / [RelayEvent.WidgetText] | **drop the oldest** — the next sample is a second away |
 * | [discrete] | everything else | **drop + count LOUDLY** — each one has meaning |
 *
 * A stale gauge value lost costs nothing: its successor supersedes it by
 * definition. A `DeviceOffline` lost is a breakdown alert never sent.
 *
 * ## Why the discrete channel still has a bound
 *
 * Discrete events are rare — presence flaps, quota hits, schedule fires. The
 * only way [DISCRETE_CAPACITY] fills is a consumer that is stuck or dead, and
 * against a dead consumer an unbounded channel does not save the events: it
 * turns them into an OOM that takes the relay down with it. Bounded-plus-loud
 * keeps the node alive and makes the failure visible instead of fatal. The
 * counters here are exactly what the "oldest PENDING age" metric watches
 * later.
 *
 * ## Producers never wait
 *
 * [publish] is `trySend` only — safe from the device read coroutine, from
 * presence callbacks, from anywhere. The rule is the same one the outboxes
 * enforce: a slow consumer degrades ITS channel, never the reading of sockets.
 */
class EventSinks(
    valuesCapacity: Int = VALUES_CAPACITY,
    discreteCapacity: Int = DISCRETE_CAPACITY
) {

    /** Lossy by design — `DROP_OLDEST` keeps the freshest samples. */
    val values: Channel<RelayEvent> = Channel(
        capacity = valuesCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { droppedValues.incrementAndGet() }
    )

    /** Never dropped in normal life; overflow means the consumer is gone. */
    val discrete: Channel<RelayEvent> = Channel(capacity = discreteCapacity)

    private val droppedValues = AtomicLong(0)
    private val droppedDiscrete = AtomicLong(0)

    /** Observability hooks — the future "oldest PENDING" metric reads these. */
    val droppedValueCount: Long get() = droppedValues.get()
    val droppedDiscreteCount: Long get() = droppedDiscrete.get()

    /**
     * Route by nature, hand over, return. Never suspends, never throws.
     */
    fun publish(event: RelayEvent) {
        when (event) {
            is RelayEvent.WidgetValue, is RelayEvent.WidgetText ->
                // DROP_OLDEST makes trySend always succeed; the eviction is
                // counted by onUndeliveredElement.
                values.trySend(event)

            else -> {
                if (!discrete.trySend(event).isSuccess) {
                    // The consumer is stuck or dead. Loud, rate-limited to the
                    // first occurrence: a flood of ERROR lines would bury the
                    // one that matters.
                    if (droppedDiscrete.incrementAndGet() == 1L) {
                        logger.error(
                            "Discrete event channel is FULL — the rules consumer is stuck or dead. " +
                                "Dropping ${event::class.simpleName} (and counting further losses silently)."
                        )
                    }
                }
            }
        }
    }

    companion object {
        /**
         * ~1 s of headroom at the bench-measured 900 frames/s. More would only
         * hand the engine staler values; a value's freshness IS its worth.
         */
        const val VALUES_CAPACITY = 1024

        /**
         * Days of normal discrete traffic. Sized so that only a dead consumer
         * can fill it — an eviction here must stay a strong signal, never an
         * accident of a busy minute.
         */
        const val DISCRETE_CAPACITY = 4096
    }
}
