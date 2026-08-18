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

package com.jeanloickdt.signal

import com.jeanloickdt.common.ServerConfig
import com.jeanloickdt.event.EventSinks
import com.jeanloickdt.event.RelayEvent
import com.jeanloickdt.relay.HistoryBuffers
import com.jeanloickdt.relay.LastValueCache
import com.jeanloickdt.relay.WidgetKey
import com.jeanloickdt.signal.domain.SignalRepository
import com.jeanloickdt.widget.data.HistoryAggregators
import com.jeanloickdt.relay.NumericHistoryEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SignalIngest")

/**
 * Diagnostic for frames addressed to a signal nobody declared.
 *
 * The 1.x relay dropped undeclared frames **in silence**: a firmware typo left
 * a gauge frozen with no trace anywhere, and the only way to find it was to
 * read the code. During a migration where boards move to addresses the app has
 * not declared yet, that failure becomes common — so it stops being silent.
 *
 * Rate-limited on purpose: a board in a loop would otherwise write a log line
 * per frame and drown the very diagnostic it is trying to give.
 */
object UndeclaredSignals {

    private val counts = ConcurrentHashMap<String, AtomicLong>()

    fun record(deviceId: String, deviceName: String, address: Int) {
        val key = "$deviceId:$address"
        val n = counts.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        if (n == 1L || n % 100L == 0L) {
            logger.warn(
                "Device '$deviceName' writes ${SignalTableRender.render(address)} which is not " +
                    "declared on it — frame dropped ($n so far). Declare the signal, or fix the sketch."
            )
        }
    }

    /** What the admin panel will show: the offending addresses and their count. */
    fun snapshot(): Map<String, Long> = counts.mapValues { it.value.get() }

    fun reset() = counts.clear()
}

/** Kept apart so this file does not depend on the table object for one call. */
private object SignalTableRender {
    fun render(address: Int) = "I$address"
}

/**
 * A signal's identity as the rest of the server sees it.
 *
 * The automation layer keys on `(ownerId, opaque string)`, and an address alone
 * is **not** unique: `I5` exists on every board. The composite carries the
 * board, so a rule watching `tt`'s `I5` never fires on `bb`'s.
 */
fun signalKey(deviceId: String, address: Int): String = "$deviceId:$address"

/**
 * The value path for `InstantIoT.write(I0, 23.4)`.
 *
 * Everything downstream of the gate already existed and is reused as-is — the
 * last-value cache, the three aggregators, the raw tier, the rule sinks. What is
 * new is only the first three steps: read the address, check it is declared,
 * and decide whether this signal is historised at all.
 *
 * @return true when the frame was accepted, so the caller broadcasts it live.
 */
fun ingestSignalFrame(
    frameBytes: ByteArray,
    ownerId: String,
    deviceId: String,
    deviceName: String,
    projectId: String,
    signals: SignalRepository,
    buffers: HistoryBuffers,
    lastValues: LastValueCache,
    rawAllowed: Boolean,
    sinks: EventSinks?,
    watched: (WidgetKey) -> Boolean,
    nowMs: Long
): Boolean {
    val address = SignalFrame.address(frameBytes) ?: run {
        logger.warn("Device '$deviceName' sent a SIGNAL frame with a malformed address — dropped")
        return false
    }

    // The strict gate, now per BOARD. A board writing an address declared on a
    // different board must be refused, not accepted: addresses are enumerated
    // per board precisely so two sketches never have to agree.
    val signal = signals.find(ownerId, deviceId, address) ?: run {
        UndeclaredSignals.record(deviceId, deviceName, address)
        return false
    }

    val payload = com.jeanloickdt.relay.FrameParser.extractPayload(frameBytes) ?: return false
    val payloadB64 = com.jeanloickdt.relay.FrameParser.encodePayloadToBase64(payload)
    val key = signalKey(deviceId, address)

    // Always: the current value. It costs one overwritten row and it is what a
    // widget paints on open, what a rule reads as a condition, and what the
    // stale sweeper watches. Never a history.
    lastValues.put(ownerId, key, payloadB64, nowMs)
    signals.touch(ownerId, deviceId, address, payloadB64, nowMs)

    val value = SignalFrame.numericValue(frameBytes)
    if (value != null && value.isFinite()) {
        // The rule feed is NOT conditioned on historisation: a rule must be able
        // to watch a signal nobody keeps a trace of.
        if (sinks != null && watched(WidgetKey(ownerId, key))) {
            sinks.publish(RelayEvent.WidgetValue(ownerId, key, null, value, nowMs))
        }

        if (signal.historised) {
            if (ServerConfig.historyRawEnabled && rawAllowed) {
                buffers.numericHistoryBuffer.add(
                    NumericHistoryEntry(key, projectId, ownerId, null, value, nowMs)
                )
            }
            HistoryAggregators.minute.collect(key, null, nowMs, value, projectId, ownerId)
            HistoryAggregators.hour.collect(key, null, nowMs, value, projectId, ownerId)
            HistoryAggregators.day.collect(key, null, nowMs, value, projectId, ownerId)
        }
    }

    return true
}
