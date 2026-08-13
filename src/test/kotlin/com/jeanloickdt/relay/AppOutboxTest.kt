package com.jeanloickdt.relay

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A zombie app session: connected, but no longer reading.
 *
 * `outgoing` is a rendezvous channel with nobody receiving, so the very first
 * `send` suspends forever — exactly what a frozen phone or a dropped mobile
 * link does to the outgoing WebSocket buffer.
 */
private class StalledSession : WebSocketSession {
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job
    override val incoming: ReceiveChannel<Frame> = Channel()
    override val outgoing: SendChannel<Frame> = Channel(Channel.RENDEZVOUS)
    override val extensions: List<WebSocketExtension<*>> = emptyList()
    override var masking: Boolean = false
    override var maxFrameSize: Long = Long.MAX_VALUE
    override suspend fun flush() = Unit

    @Deprecated("Use cancel().", level = DeprecationLevel.ERROR)
    override fun terminate() {
        job.cancel()
    }
}

class AppOutboxTest {

    /**
     * The point of the whole class: the producer — the device read coroutine —
     * must never be held up by an app that stopped reading. Before the outbox,
     * this call chain suspended on the first frame and the device stopped being
     * read: its receive buffer filled, the TCP window closed, and the board
     * could no longer emit.
     */
    @Test
    fun `telemetry never blocks the producer even when the session is stalled`() = runBlocking {
        val session = StalledSession()
        val outbox = AppOutbox("zombie-user", session)

        // Far beyond the buffer: if a single call suspended, the timeout fires.
        withTimeout(5_000) {
            repeat(10_000) { i ->
                outbox.trySendTelemetry(byteArrayOf(i.toByte()))
            }
        }

        outbox.close()
    }

    /**
     * A session that cannot absorb a discrete control event is not slow, it is
     * gone. It gets evicted rather than dropping the event silently — the app
     * reconnects and re-syncs through `/states`.
     */
    @Test
    fun `control events evict a session that stopped draining`() = runBlocking {
        val session = StalledSession()
        val outbox = AppOutbox("zombie-user", session)

        var evictedAt = -1
        withTimeout(5_000) {
            for (i in 0 until 500) {
                if (!outbox.trySendControl("""{"type":"device_offline","n":$i}""")) {
                    evictedAt = i
                    break
                }
            }
        }

        assertTrue(evictedAt > 0, "the session should have been evicted once its control buffer filled")
        assertFalse(
            outbox.trySendControl("""{"type":"device_online"}"""),
            "an evicted session must keep refusing — the caller drops it from the registry"
        )

        outbox.close()
    }

    /**
     * A healthy session must never be evicted by a legitimate burst — a device
     * coming back online while several buckets close at once. Eviction has to
     * stay a strong signal, not an accident, which is why the buffers are sized
     * generously.
     */
    @Test
    fun `a draining session absorbs a burst without being evicted`() = runBlocking {
        val session = DrainingSession()
        val outbox = AppOutbox("healthy-user", session)

        withTimeout(5_000) {
            repeat(32) { i ->
                assertTrue(
                    outbox.trySendControl("""{"type":"bucket_updated","n":$i}"""),
                    "a healthy session must not be evicted by a burst"
                )
            }
        }

        outbox.close()
    }
}

/** A session that keeps reading — the healthy case. */
private class DrainingSession : WebSocketSession {
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job
    override val incoming: ReceiveChannel<Frame> = Channel()
    override val outgoing: SendChannel<Frame> = Channel<Frame>(Channel.UNLIMITED)
    override val extensions: List<WebSocketExtension<*>> = emptyList()
    override var masking: Boolean = false
    override var maxFrameSize: Long = Long.MAX_VALUE
    override suspend fun flush() = Unit

    @Deprecated("Use cancel().", level = DeprecationLevel.ERROR)
    override fun terminate() {
        job.cancel()
    }
}

