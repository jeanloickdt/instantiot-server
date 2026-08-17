// ════════════════════════════════════════════════════════════════════════════
// relay/AppOutbox.kt
// ════════════════════════════════════════════════════════════════════════════

package com.jeanloickdt.relay

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AppOutbox")

/**
 * Per-app send buffer, device→app direction. Mirror of [DeviceOutbox], which
 * guards the opposite direction.
 *
 * ## Why
 *
 * `WebSocketSession.send` suspends once the outgoing buffer fills up — which is
 * what a zombie app does: a phone frozen in the background, a mobile link that
 * dropped without closing the TCP connection. Called straight from the device
 * read coroutine, that suspension stops the device from being read at all: its
 * receive buffer fills, the TCP window closes, and the board can no longer emit.
 * One dead app takes a live device down with it. Invisible on a LAN, common on
 * mobile.
 *
 * Every send towards an app session therefore goes through this outbox, and
 * **no method here ever suspends**. Producers hand over an element and move on.
 *
 * ## Two channels, on purpose
 *
 * The drop policies differ by nature, and a single `Channel` cannot express
 * both: `BufferOverflow.DROP_OLDEST` applies to the whole channel (a control
 * event could be the oldest, and `trySend` would always succeed, removing any
 * way to detect a stuck session), while the default `SUSPEND` capacity only
 * lets us drop the *newest*. Kotlin offers no producer-side API to evict the
 * oldest element selectively.
 *
 *  - **telemetry** — droppable. A stale sensor reading is worthless, so the
 *    channel evicts the oldest and keeps the freshest values.
 *  - **control** — never dropped. These events are discrete and meaningful
 *    (`device_online`, `device_offline`, `command_failed`, `bucket_updated`).
 *
 * Losing the relative order between the two is a feature here: during a stall
 * you want `device_offline` to land immediately rather than behind sixty stale
 * gauge values. That ordering was never guaranteed anyway — control events
 * originate from three coroutines other than the device's.
 *
 * ## Eviction rather than silent loss
 *
 * A session whose control buffer is full is not slow, it is gone. Rather than
 * dropping the event, the session is closed: the app notices, reconnects and
 * re-syncs its state through `/states`. Nothing is lost silently.
 *
 * Capacity is deliberately generous. Only a genuinely stuck session should
 * overflow — a legitimate burst (a device coming back online while several
 * buckets close) must never cost a healthy app its session. Eviction has to
 * stay a strong signal, not an accident.
 */
class AppOutbox(
    private val userId: String,
    private val session: WebSocketSession
) {
    private val droppedFrames = AtomicLong(0)
    private val evicted = AtomicBoolean(false)

    /**
     * Live telemetry. `DROP_OLDEST` keeps the freshest readings and makes
     * `trySend` non-failing — [onUndeliveredElement] gives us the drop counter
     * for free, as kotlinx invokes it precisely on overflow-evicted elements.
     */
    private val telemetry = Channel<ByteArray>(
        capacity = TELEMETRY_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { onFrameDropped() }
    )

    /** Control events. Default `SUSPEND` capacity so a full buffer is detectable. */
    private val control = Channel<String>(capacity = CONTROL_CAPACITY)

    // Two consumers rather than a `select`: `session.send` is backed by Ktor's
    // `outgoing` channel and is safe to call from several coroutines, so this
    // is both simpler and gives control events their own lane — they never
    // queue behind a telemetry backlog.
    private val telemetryConsumer: Job = session.launch(Dispatchers.IO) {
        consume(telemetry) { session.send(Frame.Binary(true, it)) }
    }

    private val controlConsumer: Job = session.launch(Dispatchers.IO) {
        consume(control) { session.send(Frame.Text(it)) }
    }

    /**
     * Queues a binary telemetry frame. Never suspends, never fails: under
     * pressure the oldest pending frame is evicted.
     */
    fun trySendTelemetry(frameBytes: ByteArray) {
        telemetry.trySend(frameBytes)
    }

    /**
     * Queues a control event. Never suspends.
     *
     * @return `false` when the session was evicted — the caller may then drop
     *         it from the registry.
     */
    fun trySendControl(jsonText: String): Boolean {
        if (control.trySend(jsonText).isSuccess) return true
        evict()
        return false
    }

    /** Closes both channels; the consumers end with their session. */
    fun close() {
        telemetry.close()
        control.close()
        val dropped = droppedFrames.get()
        if (dropped > 0) {
            logger.info("App outbox closed — userId=$userId droppedFrames=$dropped")
        }
    }

    // ────────────────────────────────────────────────────────────
    // Internals
    // ────────────────────────────────────────────────────────────

    private suspend fun <T> consume(channel: Channel<T>, send: suspend (T) -> Unit) {
        try {
            for (element in channel) send(element)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Broken pipe, session closed underneath us: the app relay's own
            // finally block unregisters the session. Nothing to do here.
            logger.debug("App outbox consumer ended — userId=$userId reason=${e.message}")
        }
    }

    private fun onFrameDropped() {
        val total = droppedFrames.incrementAndGet()
        // Logged on the first drop and then sparsely: a saturated session would
        // otherwise flood the log with the very symptom we are measuring.
        if (total == 1L || total % DROP_LOG_INTERVAL == 0L) {
            logger.warn("App outbox saturated — userId=$userId droppedFrames=$total")
        }
    }

    /**
     * Closes a session that can no longer absorb a control event.
     *
     * The close is **launched**, never awaited: `session.close` suspends, and
     * calling it inline would reintroduce exactly the blocking this class
     * exists to remove.
     */
    private fun evict() {
        if (!evicted.compareAndSet(false, true)) return
        logger.warn("App session unresponsive — userId=$userId, closing (control buffer full)")
        runCatching {
            session.launch {
                runCatching {
                    session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "outbox saturated"))
                }
            }
        }
    }

    companion object {
        /**
         * Generous on purpose. At a 2 s telemetry rate this absorbs two
         * minutes of backlog; at 10 Hz, six seconds — enough to ride out a
         * mobile network handover without evicting a healthy session.
         */
        private const val TELEMETRY_CAPACITY = 64

        /** Control events are rare; a full buffer means the session is stuck. */
        private const val CONTROL_CAPACITY = 64

        private const val DROP_LOG_INTERVAL = 100L
    }
}
