package com.jeanloickdt.relay

import io.ktor.websocket.Frame
import io.ktor.websocket.readText
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
                    outbox.trySendControl("""{"type":"device_online","n":$i}"""),
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


/**
 * Une session qui lit, et dont on peut relire ce qu'elle a recu.
 */
private class ReadableSession : WebSocketSession {
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job
    override val incoming: ReceiveChannel<Frame> = Channel()
    val received = Channel<Frame>(Channel.UNLIMITED)
    override val outgoing: SendChannel<Frame> = received
    override val extensions: List<WebSocketExtension<*>> = emptyList()
    override var masking: Boolean = false
    override var maxFrameSize: Long = Long.MAX_VALUE
    override suspend fun flush() = Unit

    @Deprecated("Use cancel().", level = DeprecationLevel.ERROR)
    override fun terminate() {
        job.cancel()
    }
}

/**
 * Une deconnexion massive, mille cartes qui tombent ensemble, n'est plus une
 * raison de fermer la session : la presence se coalesce par carte, et la
 * derniere annonce de chaque carte finit par partir.
 */
class AppOutboxPresenceTest {
    @Test
    fun `a mass offline never evicts, even a stalled session`() = runBlocking {
        val session = StalledSession()
        val outbox = AppOutbox("u1", session)
        repeat(2_000) { i ->
            assertTrue(
                outbox.trySendControl("""{"type":"device_offline","deviceId":"d$i"}""", coalesceKey = "presence:d$i"),
                "la presence de la carte $i ne doit jamais expulser la session"
            )
        }
        assertTrue(outbox.coalescedPresence > 0, "au-dela de la file, la presence attend dans sa case par carte")
        outbox.close()
        session.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }

    @Test
    fun `the latest state per board is what finally reaches the app`() = runBlocking {
        val session = ReadableSession()
        val outbox = AppOutbox("u1", session)
        // Une rafale bien au-dela de la file : tout le monde tombe, puis d7 revient.
        repeat(500) { i -> outbox.trySendControl("""{"type":"device_offline","deviceId":"d$i"}""", coalesceKey = "presence:d$i") }
        outbox.trySendControl("""{"type":"device_online","deviceId":"d7"}""", coalesceKey = "presence:d7")

        val seen = mutableListOf<String>()
        withTimeout(5_000) {
            while (seen.count { it.contains("\"d7\"") } < 1 || outbox.coalescedPresence > 0 || seen.size < 500) {
                val f = session.received.receive() as Frame.Text
                seen += f.readText()
            }
        }
        val lastForD7 = seen.last { it.contains("\"deviceId\":\"d7\"") }
        assertTrue(lastForD7.contains("device_online"), "la derniere annonce de d7 est celle qui reste : $lastForD7")
        outbox.close()
        session.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }
}
