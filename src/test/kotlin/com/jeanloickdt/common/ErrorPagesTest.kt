package com.jeanloickdt.common

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Une requête mal formée est la faute du client : 400, et le journal ne
 * garde pas le corps. Le cas qui compte est le mot de passe d'un login
 * cassé, qui finissait dans une trace de 500.
 */
class ErrorPagesTest {
    @Serializable
    private data class Login(val username: String, val password: String)

    private fun io.ktor.server.testing.ApplicationTestBuilder.app() {
        application {
            install(ContentNegotiation) { json(apiJson) }
            installErrorPages()
            limitRequestBodies(maxBytes = 64)
            routing {
                post("/api/login") {
                    val body = call.receive<Login>()
                    call.respondText("hello ${body.username}")
                }
            }
        }
    }

    private fun captureLog(): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        (LoggerFactory.getLogger("InstantIoT") as Logger).addAppender(appender)
        return appender
    }

    @Test
    fun `malformed JSON is a 400, and the password never reaches the log`() = testApplication {
        app()
        val log = captureLog()
        val secret = "hunter2-tres-secret"
        val r = client.post("/api/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"username":"alice","password":"$secret""")   // accolade manquante
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertFalse(r.bodyAsText().contains(secret), "la réponse ne renvoie pas le corps")
        val everything = log.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
        assertFalse(everything.contains(secret), "le journal ne doit jamais porter le corps : $everything")
        assertTrue(log.list.none { it.level.levelStr == "ERROR" }, "une faute du client n'est pas une panne du serveur")
    }

    @Test
    fun `a body over the limit is refused before being read`() = testApplication {
        app()
        val r = client.post("/api/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"username":"alice","password":"${"x".repeat(200)}"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
    }

    @Test
    fun `a well-formed request still passes`() = testApplication {
        app()
        val r = client.post("/api/login") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"username":"alice","password":"ok"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("hello alice", r.bodyAsText())
    }
}

/**
 * Les en-tetes que le navigateur attend. Le nuage les pose dans Caddy ; le
 * jumeau n'a pas de Caddy et sert lui-meme son panneau.
 */
class BrowserHeadersTest {
    @Test
    fun `every response carries the browser headers`() = testApplication {
        application {
            installBrowserHeaders()
            routing { get("/") { call.respondText("panneau") } }
        }
        val r = client.get("/")
        assertEquals("nosniff", r.headers["X-Content-Type-Options"])
        assertEquals("DENY", r.headers["X-Frame-Options"])
        assertEquals("no-referrer", r.headers["Referrer-Policy"])
        assertTrue(r.headers["Permissions-Policy"]!!.contains("camera=()"))
        assertEquals(null, r.headers["Strict-Transport-Security"], "pas de HSTS sur un serveur qui parle en clair")
    }
}
