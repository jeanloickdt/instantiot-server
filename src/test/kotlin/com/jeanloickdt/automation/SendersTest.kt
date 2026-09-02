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

package com.jeanloickdt.automation

import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two senders that make automation REAL for self-host and cloud alike.
 * Brevo is faked at the transport seam — the tests exercise everything up to
 * the HTTP boundary, and the panel's test button exercises the boundary
 * itself in production.
 */
class SendersTest {

    private fun action(type: String, payload: String, owner: String = "u1") = PendingAction(
        id = 1, idempotencyKey = "k", ownerId = owner, ruleId = "r1",
        type = type, payload = payload, status = "PENDING",
        attempts = 0, nextAttemptAt = 0, occurredAt = 0
    )

    private fun emailSender(
        config: EmailConfig = EmailConfig("key-123", "noreply@instantiot.io", "InstantIoT", ""),
        accountEmail: (String) -> String? = { null },
        transport: (String, String, String) -> Int
    ) = EmailActionSender({ config }, accountEmail, transport)

    // ── EMAIL : le destinataire, dans l'ordre ─────────────────────────────

    @Test
    fun `the rule's 'to' wins, and the payload is shaped for Brevo`() = runBlocking {
        var sent: Triple<String, String, String>? = null
        val sender = emailSender(transport = { url, key, body -> sent = Triple(url, key, body); 201 })

        val r = sender.send(action("EMAIL", """{"to":"loick@example.com","subject":"Cuve","body":"92 %"}"""))

        assertTrue(r is SendResult.Ok)
        val (url, key, body) = sent!!
        assertEquals(EmailActionSender.BREVO_URL, url)
        assertEquals("key-123", key)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("loick@example.com",
            json["to"]!!.jsonArray[0].jsonObject["email"]!!.jsonPrimitive.content)
        assertEquals("Cuve", json["subject"]!!.jsonPrimitive.content)
        assertEquals("noreply@instantiot.io", json["sender"]!!.jsonObject["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no 'to' — the account email is used, in cloud the iia username IS the email`() = runBlocking {
        var body = ""
        val sender = emailSender(
            accountEmail = { owner -> "$owner@example.com" },
            transport = { _, _, b -> body = b; 200 }
        )
        sender.send(action("EMAIL", """{"subject":"s","body":"b"}""", owner = "alice"))
        assertTrue("alice@example.com" in body)
    }

    @Test
    fun `self-host has no account email — the panel's alert address is the fallback`() = runBlocking {
        var body = ""
        val sender = emailSender(
            config = EmailConfig("key", "from@x.io", "X", defaultTo = "admin@maison.fr"),
            accountEmail = { null },   // self-host usernames are not emails
            transport = { _, _, b -> body = b; 200 }
        )
        sender.send(action("EMAIL", """{"subject":"s","body":"b"}"""))
        assertTrue("admin@maison.fr" in body)
    }

    @Test
    fun `nowhere to send — Fatal with the reason, retrying would be noise`() = runBlocking {
        val r = emailSender(transport = { _, _, _ -> 200 })
            .send(action("EMAIL", """{"subject":"s","body":"b"}"""))
        assertTrue(r is SendResult.Fatal && "no recipient" in r.reason)
    }

    @Test
    fun `not configured — Fatal that points at the panel`() = runBlocking {
        val r = emailSender(config = EmailConfig("", "", "X", ""), transport = { _, _, _ -> 200 })
            .send(action("EMAIL", """{"to":"a@b.c","body":"b"}"""))
        assertTrue(r is SendResult.Fatal && "admin panel" in r.reason)
    }

    // ── EMAIL : la traduction des réponses Brevo ──────────────────────────

    @Test
    fun `Brevo statuses map to the worker's semantics`() = runBlocking {
        suspend fun with(status: Int): SendResult =
            emailSender(transport = { _, _, _ -> status })
                .send(action("EMAIL", """{"to":"a@b.c","body":"b"}"""))

        assertTrue(with(201) is SendResult.Ok)
        assertTrue((with(401) as SendResult.Fatal).reason.contains("API key"),
            "a bad key must say so — and mention Brevo's authorized-IP list")
        assertTrue(with(422) is SendResult.Fatal, "a 4xx will never work — DEAD, not a spin")
        assertTrue(with(503) is SendResult.Retry, "a 5xx is Brevo down — backoff and retry")
    }

    @Test
    fun `a network failure is Retry — Brevo unreachable is not the email unsendable`() = runBlocking {
        val r = emailSender(transport = { _, _, _ -> throw java.io.IOException("timeout") })
            .send(action("EMAIL", """{"to":"a@b.c","body":"b"}"""))
        assertTrue(r is SendResult.Retry)
    }

    // ── COMMAND ───────────────────────────────────────────────────────────

    private val frame = Base64.getEncoder().encodeToString(byteArrayOf(0xAA.toByte(), 1, 2, 3))

    @Test
    fun `a COMMAND reaches the board through the outbox seam`() = runBlocking {
        var delivered: Pair<String, ByteArray>? = null
        val sender = CommandActionSender(
            ownsDevice = { owner, _ -> owner == "u1" },
            sendToDevice = { id, f -> delivered = id to f; true }
        )
        val r = sender.send(action("COMMAND", """{"deviceId":"d1","payloadB64":"$frame"}"""))

        assertTrue(r is SendResult.Ok)
        assertEquals("d1", delivered!!.first)
        assertEquals(0xAA.toByte(), delivered!!.second[0])
    }

    @Test
    fun `cross-tenant at DELIVERY time is refused too — three checks, three doors`() = runBlocking {
        // Engine and API both check; this is the third door, for rows that
        // reached the table without either (mutation between fire and send).
        var called = false
        val sender = CommandActionSender(
            ownsDevice = { owner, _ -> owner == "somebody-else" },
            sendToDevice = { _, _ -> called = true; true }
        )
        val r = sender.send(action("COMMAND", """{"deviceId":"d1","payloadB64":"$frame"}""", owner = "u1"))

        assertTrue(r is SendResult.Fatal && "cross-tenant" in r.reason)
        assertTrue(!called, "the frame must never leave")
    }

    @Test
    fun `a board offline at delivery is a LOST command, said loudly — never a retry`() = runBlocking {
        val sender = CommandActionSender(
            ownsDevice = { owner, _ -> owner == "u1" },
            sendToDevice = { _, _ -> false }   // no outbox = offline
        )
        val r = sender.send(action("COMMAND", """{"deviceId":"d1","payloadB64":"$frame"}"""))
        assertTrue(r is SendResult.Fatal && "offline" in r.reason,
            "at-most-once already marked the row SENT — a retry would reopen the double-execution window")
    }

    @Test
    fun `garbage payloads die cleanly`() = runBlocking {
        val sender = CommandActionSender({ _, _ -> true }, { _, _ -> true })
        assertTrue(sender.send(action("COMMAND", "not json")) is SendResult.Fatal)
        assertTrue(sender.send(action("COMMAND", """{"payloadB64":"$frame"}""")) is SendResult.Fatal)
        assertTrue(sender.send(action("COMMAND", """{"deviceId":"d1","payloadB64":"!!!"}""")) is SendResult.Fatal)
    }
}
