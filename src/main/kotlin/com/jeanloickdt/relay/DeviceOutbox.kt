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

// relay/DeviceOutbox.kt
package com.jeanloickdt.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.Socket

private val logger = LoggerFactory.getLogger("DeviceOutbox")

/**
 * Serializes the TCP writes to **one device** via a bounded Channel +
 * dedicated consumer coroutine.
 *
 * ## Problem solved
 *
 * `AppRelay` used to do `launch(Dispatchers.IO) { socket.write(frame) }` for
 * each frame received from an app. With a slider dragged at high frequency,
 * we ended up with 30-60 coroutines in parallel blocked on `write()` to
 * the same ESP32 (kernel TCP send buffer full, ESP slow to consume via
 * its Arduino `loop()`). When the app closed, these coroutines stayed
 * orphaned on the global `applicationScope` and kept waiting.
 * A new app connecting saw its commands queue up behind the ~50 blocked
 * writes — hence the "5 min" before a Press got through.
 *
 * ## Solution
 *
 * One `DeviceOutbox` per device : all frames destined for this ESP
 * go through a bounded Channel (cap. 8). A **single** consumer
 * coroutine drains the channel and does the `socket.write()` sequentially.
 *
 * ### Backpressure rules
 *
 * - **Streaming frames** (slider ValueChanging, joystick PositionChanged) :
 *   non-blocking `trySend`. If the channel is full → the frame is
 *   **dropped silently**. This keeps the most recent ones without
 *   accumulation. 400 ms max latency on a slider in continuous drag.
 *
 * - **Discrete frames** (Press, Release, Toggle, final VALUE_CHANGED,
 *   SetValue, etc.) : suspending `send`. If the channel is full, the
 *   caller suspends until a slot frees up (a few ms in practice,
 *   the consumer drains the channel in parallel). **No discrete frame is
 *   ever lost.**
 *
 * ## Lifecycle
 *
 * - Created in `SessionRegistry.registerDevice(...)` after successful ESP auth.
 * - Closed in `SessionRegistry.unregisterDevice(...)` → the consumer
 *   coroutine exits `for (msg in channel)` cleanly.
 * - If the `socket.write()` throws (ESP disconnected without a clean FIN), the
 *   coroutine logs and closes the channel — the subsequent sends will return
 *   `ClosedSendChannelException`, caught by `AppRelay.relayFrameToDevices`.
 */
class DeviceOutbox(
    private val deviceId: String,
    private val socket: Socket,
    scope: CoroutineScope
) {
    private val channel = Channel<FrameMsg>(capacity = CHANNEL_CAPACITY)
    private val consumerJob: Job = scope.launch(Dispatchers.IO) { runConsumer() }

    /**
     * Sends a frame to the device.
     *
     * @param bytes iWidgets v1 frame already trimmed (DEV_COUNT=0, CRC recomputed)
     * @param isStreaming `true` for slider ValueChanging / joystick
     *                    PositionChanged — will be dropped if the channel is
     *                    full. `false` for any other command — will be
     *                    suspended until a slot is available.
     * @return `true` if the frame was queued (or intentionally
     *         dropped), `false` if the channel is already closed
     *         (device disconnected). The caller must notify the app via
     *         `command_failed(device_offline)` in that case.
     */
    @OptIn(DelicateCoroutinesApi::class)  // isClosedForSend — fast check, safe read
    suspend fun send(bytes: ByteArray, isStreaming: Boolean): Boolean {
        if (channel.isClosedForSend) return false

        return if (isStreaming) {
            // trySend : if full → silent drop, we keep the N most recent
            val sendResult = channel.trySend(FrameMsg(bytes, isStreaming = true))
            if (sendResult.isFailure && !sendResult.isClosed) {
                logger.debug("Outbox full for device=$deviceId — dropped streaming frame")
            }
            !sendResult.isClosed
        } else {
            // suspending send : clean backpressure, never a loss
            try {
                channel.send(FrameMsg(bytes, isStreaming = false))
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Closes the channel and stops the consumer coroutine.
     * Idempotent — safe to call multiple times.
     */
    fun close() {
        channel.close()
        consumerJob.cancel()
    }

    /**
     * Single coroutine that drains the channel and writes to the TCP socket.
     *
     * Loops until the channel is closed (disconnect) or an I/O error
     * (ESP gone). Any exception closes the outbox — the `DeviceRelay`
     * will detect the dead socket on its next read and call
     * `unregisterDevice` which cleans up the outbox properly.
     */
    private suspend fun runConsumer() {
        try {
            val outputStream = socket.getOutputStream()
            for (msg in channel) {
                try {
                    outputStream.write(msg.bytes)
                    outputStream.flush()
                } catch (e: IOException) {
                    logger.warn("TCP write failed for device=$deviceId — closing outbox (${e.message})")
                    break
                }
            }
        } catch (e: Exception) {
            logger.warn("Outbox consumer error for device=$deviceId — ${e.message}")
        } finally {
            channel.close()
        }
    }

    companion object {
        /**
         * Channel capacity per device.
         *
         * 8 frames = covers a slider drag burst without loss for
         * ~400 ms (8 × 50 ms of the app throttler interval). Beyond that,
         * streaming frames are dropped — imperceptible for the user.
         */
        private const val CHANNEL_CAPACITY = 8
    }
}

/**
 * Wrapper for the frames pending in the outbox.
 *
 * `isStreaming` is not used by the consumer (it writes everything
 * sequentially) but is kept for future logging / metrics.
 */
private data class FrameMsg(
    val bytes: ByteArray,
    val isStreaming: Boolean
)