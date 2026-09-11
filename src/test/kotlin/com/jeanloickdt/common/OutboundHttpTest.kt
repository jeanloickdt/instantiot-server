package com.jeanloickdt.common

import com.jeanloickdt.common.OutboundHttp.bounded
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Un serveur qui accepte le TCP et ne répond jamais — le cas d'un iia ou
 * d'un Brevo malade — ne garde pas le thread appelant pour toujours.
 */
class OutboundHttpTest {
    @Test
    fun `a silent server is given up on within the bound`() {
        ServerSocket().use { silent ->
            silent.bind(InetSocketAddress("127.0.0.1", 0))
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${silent.localPort}/never"))
                .bounded(Duration.ofMillis(800))
                .GET().build()
            val started = System.nanoTime()
            assertFailsWith<HttpTimeoutException> {
                OutboundHttp.client.send(req, HttpResponse.BodyHandlers.discarding())
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 5_000, "abandonné en ${elapsedMs} ms, pas « jamais »")
        }
    }

    @Test
    fun `the shared client refuses to wait for a connection beyond its bound`() {
        // 10.255.255.1 : une adresse non routée, le SYN part et rien ne revient.
        val req = HttpRequest.newBuilder(URI("http://10.255.255.1:81/"))
            .bounded(Duration.ofSeconds(2))
            .GET().build()
        val started = System.nanoTime()
        runCatching { OutboundHttp.client.send(req, HttpResponse.BodyHandlers.discarding()) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 6_000, "connexion abandonnée en ${elapsedMs} ms — CONNECT vaut ${OutboundHttp.CONNECT.seconds} s")
    }
}
