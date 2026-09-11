package com.jeanloickdt.relay

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Le registre des sessions app : indexé par projet, borné par compte.
 *
 * Chaque trame de carte demandait « qui regarde ce projet ? » en balayant
 * TOUTES les sessions du nœud ; et un compte pouvait en ouvrir sans limite.
 */
class ConnectionRegistryTest {
    private class Session : WebSocketSession {
        private val job = SupervisorJob()
        override val coroutineContext: CoroutineContext = job
        override val incoming: ReceiveChannel<Frame> = Channel()
        override val outgoing: SendChannel<Frame> = Channel(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE
        override suspend fun flush() = Unit
        @Deprecated("Use cancel().", level = DeprecationLevel.ERROR)
        override fun terminate() { job.cancel() }
    }

    @Test
    fun `sessions are found by project, and only those`() {
        val r = ConnectionRegistry()
        val a = r.registerApp("alice", Session(), "i1")!!; r.setActiveProject(a, "P1")
        val b = r.registerApp("bob", Session(), "i2")!!; r.setActiveProject(b, "P1")
        val c = r.registerApp("carol", Session(), "i3")!!; r.setActiveProject(c, "P2")

        assertEquals(setOf(a, b), r.getAppSessionsForProject("P1").toSet())
        assertEquals(listOf(c), r.getAppSessionsForProject("P2"))
        assertTrue(r.getAppSessionsForProject("P9").isEmpty())
    }

    @Test
    fun `leaving a session removes it from its project, and a project change moves it`() {
        val r = ConnectionRegistry()
        val s = Session()
        val a = r.registerApp("alice", s, "i1")!!
        r.setActiveProject(a, "P1")
        r.setActiveProject(a, "P2")
        assertTrue(r.getAppSessionsForProject("P1").isEmpty(), "plus dans l'ancien projet")
        assertEquals(listOf(a), r.getAppSessionsForProject("P2"))

        r.unregisterApp("alice", s)
        assertTrue(r.getAppSessionsForProject("P2").isEmpty())
        assertNull(r.appSessions["alice"])
    }

    @Test
    fun `a single account cannot open more than the per-account cap`() {
        val r = ConnectionRegistry()
        repeat(ConnectionRegistry.MAX_SESSIONS_PER_USER) {
            assertNotNull(r.registerApp("alice", Session(), "i$it"), "la ${it + 1}e passe")
        }
        assertNull(r.registerApp("alice", Session(), "trop"), "la suivante est refusée")
        assertNotNull(r.registerApp("bob", Session(), "b1"), "le voisin n'est pas puni")
    }
}
