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

import com.jeanloickdt.signal.data.SignalTable
import com.jeanloickdt.signal.domain.SignalRepository
import com.jeanloickdt.signal.domain.SignalRow
import java.util.Base64
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SignalSetpoint")

/**
 * Writing a **setpoint** — the app or a rule saying what it *wants*, as opposed
 * to a measure saying what *is*.
 *
 * ## Why a setpoint is stored and a gesture is not
 *
 * A gesture is an event: replaying "the button was pressed" three days later
 * would be absurd. A setpoint is a state: restoring it is exactly right. That
 * is the whole reason the two travel on different channels — see §1 of
 * `PROTOCOLE-2.0.md`.
 *
 * So the order here matters and is deliberate: **store first, then send.** A
 * board that is offline still gets the new setpoint at its next connection, and
 * a delivery that fails leaves the intent recorded rather than lost.
 */
object SignalSetpoint {

    sealed interface Outcome {
        /** Stored, and handed to the board's outbox. */
        data class Delivered(val value: Double?) : Outcome
        /** Stored; the board was offline and will get it when it reconnects. */
        data class Stored(val value: Double?) : Outcome
        data class Refused(val reason: String) : Outcome
    }

    /**
     * Applies a setpoint to a signal.
     *
     * [send] is the seam onto the device outbox — injected so this is testable
     * without a socket, exactly like the COMMAND sender.
     */
    suspend fun write(
        signals: SignalRepository,
        ownerId: String,
        deviceId: String,
        address: Int,
        raw: Double?,
        text: String?,
        nowMs: Long,
        send: suspend (deviceId: String, frame: ByteArray) -> Boolean,
        /**
         * Every app watching this project. A signal that changed must reach
         * ALL its observers, whoever wrote it — a display bound to it is not
         * less entitled to the news because the change came from a control
         * next to it rather than from the board.
         */
        broadcast: (frame: ByteArray) -> Unit = {}
    ): Outcome {
        val signal = signals.find(ownerId, deviceId, address)
            ?: return Outcome.Refused("No signal at ${SignalTable.render(address)} on this board")

        // A measure is what the board says it IS. Letting the app write it would
        // recreate exactly the lie the model exists to avoid: a value that
        // asserts something no sensor ever reported.
        if (signal.direction == SignalTable.DIRECTION_MEASURE) {
            return Outcome.Refused(
                "${SignalTable.render(address)} is a measure — only the board writes it. " +
                    "Use direction 'setpoint' or 'both' to let the app write."
            )
        }

        val frame = encode(signal, raw, text)
            ?: return Outcome.Refused("Value does not fit type '${signal.type}'")

        val stored = clamp(signal, raw)

        // Store BEFORE sending: an offline board must still find its setpoint
        // waiting at the next connection.
        signals.touch(ownerId, deviceId, address, Base64.getEncoder().encodeToString(payloadOf(frame)), nowMs)

        // The apps hear about it whether or not the board is reachable: the
        // SIGNAL changed, and that is what an observer subscribed to. A gauge
        // showing a setpoint must not wait for a board that is asleep.
        //
        // Their copy carries the device; the board's does not — see [SignalFrame.build].
        broadcast(SignalFrame.forApps(frame, deviceId) ?: frame)

        return if (send(deviceId, frame)) Outcome.Delivered(stored) else Outcome.Stored(stored)
    }

    /**
     * Everything a freshly connected board must be told again.
     *
     * The board has just rebooted, or lost the network for an hour: it has no
     * memory of what was asked of it. Pushing the setpoints here is what makes
     * "a setpoint is a state" true in practice, and it is why the board needs
     * no SYNC request of its own — the server already knows what to replay.
     *
     * Measures are never replayed: what the board *is* is the board's to say.
     */
    suspend fun restoreOnConnect(
        signals: SignalRepository,
        ownerId: String,
        deviceId: String,
        send: suspend (deviceId: String, frame: ByteArray) -> Boolean
    ): Int {
        var sent = 0
        val toRestore = signals.listByDevice(ownerId, deviceId)
            .filter { it.direction != SignalTable.DIRECTION_MEASURE && it.lastPayload != null }
        for (signal in toRestore) {
            val payload = runCatching { Base64.getDecoder().decode(signal.lastPayload) }.getOrNull()
                ?: continue
            if (send(deviceId, SignalFrame.build(signal.address, tagOf(signal.type), payload))) sent++
        }
        if (sent > 0) logger.info("Restored $sent setpoint(s) to device $deviceId on connect")
        return sent
    }

    /** Bounds CLAMP, never reject — a value out of range is still a value. */
    private fun clamp(signal: SignalRow, raw: Double?): Double? {
        var v = raw ?: return null
        val lo = signal.minValue
        val hi = signal.maxValue
        if (lo != null && v < lo) v = lo
        if (hi != null && v > hi) v = hi
        return v
    }

    private fun tagOf(type: String): Int = when (type) {
        SignalTable.TYPE_BOOL   -> SignalFrame.TAG_BOOL
        SignalTable.TYPE_INT,
        SignalTable.TYPE_ENUM   -> SignalFrame.TAG_INT
        SignalTable.TYPE_STRING -> SignalFrame.TAG_STRING
        else                    -> SignalFrame.TAG_FLOAT
    }

    private fun encode(signal: SignalRow, raw: Double?, text: String?): ByteArray? {
        val addr = signal.address
        return when (signal.type) {
            SignalTable.TYPE_BOOL -> {
                val v = raw ?: return null
                SignalFrame.build(addr, SignalFrame.TAG_BOOL, byteArrayOf(if (v != 0.0) 1 else 0))
            }
            SignalTable.TYPE_INT, SignalTable.TYPE_ENUM -> {
                val v = clamp(signal, raw ?: return null)!!.toInt()
                SignalFrame.build(addr, SignalFrame.TAG_INT, byteArrayOf(
                    (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                    ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
                ))
            }
            SignalTable.TYPE_FLOAT -> {
                val v = clamp(signal, raw ?: return null)!!.toFloat()
                SignalFrame.build(addr, SignalFrame.TAG_FLOAT, SignalFrame.floatBytes(v))
            }
            SignalTable.TYPE_STRING -> {
                val s = text ?: return null
                SignalFrame.build(addr, SignalFrame.TAG_STRING, s.take(48).toByteArray())
            }
            else -> null
        }
    }

    /** The value bytes of a frame we just built — everything after TYPE and TAG. */
    private fun payloadOf(frame: ByteArray): ByteArray =
        com.jeanloickdt.relay.FrameParser.extractPayload(frame) ?: ByteArray(0)
}
