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
import com.jeanloickdt.relay.SignalRef
import com.jeanloickdt.signal.domain.SignalRepository
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
 * Diagnostic for frames whose value does not fit the slot they are addressed to.
 *
 * This is the other half of [UndeclaredSignals], and it exists for the same
 * reason: the frame is dropped, so the user must be able to learn why. The
 * common case is a board that has not been reflashed after a type change —
 * it keeps sending the old encoding, and without this it would look like the
 * board simply stopped reporting.
 *
 * What is kept is what the app needs to write the sentence: how many, what the
 * slot expects, what the board actually sent, and when it last happened.
 * Counting lives in RAM because this sits on the hot path — a refused frame
 * must never cost a write.
 */
object TypeMismatches {

    data class State(
        val count: Long,
        val expectedType: String,
        val receivedType: String,
        val lastAtMs: Long
    )

    private val states = ConcurrentHashMap<String, State>()

    fun record(
        deviceId: String,
        deviceName: String,
        address: Int,
        expectedType: String,
        receivedType: String,
        nowMs: Long
    ): State {
        val key = signalKey(deviceId, address)
        val next = states.compute(key) { _, previous ->
            State(
                count = (previous?.count ?: 0L) + 1L,
                expectedType = expectedType,
                receivedType = receivedType,
                lastAtMs = nowMs
            )
        }!!
        // Same discipline as UndeclaredSignals: a board in a loop would write a
        // log line per frame and drown the diagnostic it is trying to give.
        if (next.count == 1L || next.count % 100L == 0L) {
            logger.warn(
                "Device '$deviceName' writes ${SignalTableRender.render(address)} as " +
                    "'$receivedType' but it is declared '$expectedType' — frame refused " +
                    "(${next.count} so far). Reflash the board, or change the declared type."
            )
        }
        return next
    }

    /** What the app shows on the device, and what the admin panel lists. */
    fun snapshot(): Map<String, State> = states.toMap()

    /** The state of one signal, `null` when it has never sent a bad frame. */
    fun stateOf(deviceId: String, address: Int): State? = states[signalKey(deviceId, address)]

    /**
     * Forgets one signal's mismatches.
     *
     * Called when the declared type changes: the previous complaint described
     * the previous declaration, and keeping it would show the user a warning
     * about a problem they have just fixed.
     */
    fun clear(deviceId: String, address: Int) {
        states.remove(signalKey(deviceId, address))
    }

    fun reset() = states.clear()
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
 * L'inverse de [signalKey] — reconnaît `deviceId:adresse`.
 *
 * Découpe sur le **dernier** deux-points : un identifiant de carte est un
 * UUID qui peut en contenir, l'adresse jamais. Rend `null` quand la partie
 * droite n'est pas une adresse valide — ce n'est alors pas une clé de signal
 * et l'appelant doit refuser plutôt que deviner.
 *
 * La même règle vit côté app, dans son fetcher d'historique. Elle est écrite
 * aux deux bouts parce que les deux doivent reconnaître la forme ; c'est le
 * genre de duplication que le module de protocole partagé (question ouverte
 * du brief) supprimerait.
 */
fun parseSignalKey(key: String): Pair<String, Int>? {
    val i = key.lastIndexOf(':')
    if (i <= 0 || i == key.lastIndex) return null
    val address = key.substring(i + 1).toIntOrNull() ?: return null
    if (address !in 0..255) return null
    return key.substring(0, i) to address
}

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
    watched: (SignalRef) -> Boolean,
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

    // The value must fit the slot. A frame that does not is refused here —
    // before RAM, before disk, before the live relay — because a float
    // truncated into an int is a wrong number that looks legitimate, and that
    // is worse than a hole in the data. The hole is honest: it says the board
    // and the declaration disagree, and it disappears the moment they agree
    // again. See SignalTypeCompat for why this is a rank and not a table.
    val frameTag = SignalFrame.tag(frameBytes)
    if (frameTag == null || !SignalTypeCompat.accepts(signal.type, frameTag)) {
        val received = frameTag?.let { SignalTypeCompat.nameOfTag(it) } ?: "unknown"
        TypeMismatches.record(deviceId, deviceName, address, signal.type, received, nowMs)
        return false
    }

    val payload = com.jeanloickdt.relay.FrameParser.extractPayload(frameBytes) ?: return false
    val payloadB64 = com.jeanloickdt.relay.FrameParser.encodePayloadToBase64(payload)
    val key = signalKey(deviceId, address)

    // Always: the current value. It costs one overwritten row and it is what a
    // widget paints on open, what a rule reads as a condition, and what the
    // stale sweeper watches. Never a history.
    lastValues.put(ownerId, key, payloadB64, nowMs)
    // Buffered, not written through: this is telemetry on the hot path. The
    // setpoint route uses touch() and keeps its guarantee — see the contract.
    signals.touchBuffered(ownerId, deviceId, address, payloadB64, nowMs)

    val value = SignalFrame.numericValue(frameBytes)
    if (value != null && value.isFinite()) {
        // The rule feed is NOT conditioned on historisation: a rule must be able
        // to watch a signal nobody keeps a trace of.
        if (sinks != null && watched(SignalRef(ownerId, key))) {
            sinks.publish(RelayEvent.SignalValue(ownerId, key, null, value, nowMs))
        }

        if (signal.historised) {
            // Le modèle signal, pas l'ancien : `signal.id` (l'entier de
            // l'étape 0) plutôt que `key` (la chaîne `"$deviceId:$address"`
            // qui indexait encore `HistoryAggregators`/`widget_history_*`).
            // Un seul palier accumulé ici, pas trois — heure et jour restent
            // vides tant que l'étape 3 (dérivation périodique) n'existe pas ;
            // voir SignalMinuteAggregator.
            // L'ordre des trois conditions dit la hierarchie : l'operateur a
            // le dernier mot, puis le plan, puis la machine. La contre-pression
            // est consultee EN DERNIER et compte ses refus — sans quoi une
            // degradation ne se decouvrirait que par une plainte.
            if (ServerConfig.historyRawEnabled && rawAllowed && buffers.backPressure.allowRaw()) {
                buffers.signalRawBuffer.offer(
                    com.jeanloickdt.signal.data.SignalRawEntry(signal.id, ownerId, nowMs, value)
                )
            }
            com.jeanloickdt.signal.data.SignalAggregators.minute.collect(signal.id, ownerId, nowMs, value)
        }
    }

    return true
}
