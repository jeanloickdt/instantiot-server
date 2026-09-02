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

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Senders")

/** What the email sender needs — read fresh on every send, so a key pasted in
 *  the panel works on the NEXT delivery, no restart. */
data class EmailConfig(
    val apiKey: String,
    val fromEmail: String,
    val fromName: String,
    /** Le destinataire de repli, regle dans le panneau. */
    val defaultTo: String
) {
    val configured: Boolean get() = apiKey.isNotBlank() && fromEmail.isNotBlank()
}

/**
 * EMAIL over Brevo's transactional HTTP API — the same channel iia already
 * uses in production, so the domain is authenticated and the failure modes
 * are known.
 *
 * ## Recipient resolution, in order
 *
 *  1. `to` in the action params — the rule says where
 *  2. the account's email ([accountEmail]) — the JIT username IS the iia
 *     email, so this is nearly always the answer
 *  3. the configured default ([EmailConfig.defaultTo])
 *
 * No recipient at the end → [SendResult.Fatal]: retrying an email that has
 * nowhere to go is noise, and the DEAD row says exactly why.
 */
class EmailActionSender(
    private val config: () -> EmailConfig,
    /** ownerId → account email, or null when the username is not an email. */
    private val accountEmail: (String) -> String?,
    /** (url, apiKey, jsonBody) → HTTP status. Injectable: tests fake Brevo. */
    private val transport: (String, String, String) -> Int = ::brevoHttp
) : ActionSender {

    override suspend fun send(action: PendingAction): SendResult {
        val cfg = config()
        if (!cfg.configured) {
            return SendResult.Fatal(
                "email is not configured — set the Brevo key and sender in the admin panel"
            )
        }

        val params = runCatching { Json.parseToJsonElement(action.payload).jsonObject }
            .getOrElse { return SendResult.Fatal("unparseable payload") }
        val to = params["to"]?.jsonPrimitive?.content
            ?: accountEmail(action.ownerId)
            ?: cfg.defaultTo.takeIf { it.isNotBlank() }
            ?: return SendResult.Fatal(
                "no recipient: no 'to' in the rule, the account has no email, and no default is configured"
            )

        val subject = params["subject"]?.jsonPrimitive?.content
            ?: params["title"]?.jsonPrimitive?.content
            ?: "InstantIoT — alert"
        val body = params["body"]?.jsonPrimitive?.content ?: ""

        val payload = buildJsonObject {
            putJsonObject("sender") { put("email", cfg.fromEmail); put("name", cfg.fromName) }
            putJsonArray("to") { add(buildJsonObject { put("email", to) }) }
            put("subject", subject)
            put("htmlContent", "<p>${body.replace("<", "&lt;")}</p>")
        }

        val status = try {
            transport(BREVO_URL, cfg.apiKey, payload.toString())
        } catch (e: Exception) {
            // Network trouble is Brevo unreachable, not the email unsendable.
            return SendResult.Retry(e.message ?: "network error")
        }
        return when (status) {
            in 200..299 -> SendResult.Ok
            401, 403    -> SendResult.Fatal("Brevo refused the API key ($status) — check the panel, and Brevo's authorized-IP list")
            in 400..499 -> SendResult.Fatal("Brevo rejected the email ($status)")
            else        -> SendResult.Retry("Brevo answered $status")
        }
    }

    companion object {
        const val BREVO_URL = "https://api.brevo.com/v3/smtp/email"

        private val http = HttpClient.newHttpClient()
        fun brevoHttp(url: String, apiKey: String, body: String): Int =
            http.send(
                HttpRequest.newBuilder(URI(url))
                    .header("api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.discarding()
            ).statusCode()
    }
}

/**
 * COMMAND to the board, through the same outbox every app command rides.
 *
 * The narrow [sendToDevice] seam keeps this testable and keeps the sender
 * ignorant of sockets: Application wires it to the ConnectionRegistry's
 * outboxes.
 *
 * The ownership is re-checked HERE too — engine, API and sender all verify,
 * because each can be reached without the others (a row inserted by hand,
 * a rule mutated between fire and delivery). Never an identifier without its
 * owner.
 *
 * A board offline at delivery time is [SendResult.Fatal], not Retry: the
 * worker's at-most-once contract already marked the row SENT, and "the valve
 * command was lost because the board was off" is the honest, loudly-logged
 * outcome the contract chose.
 */
class CommandActionSender(
    /**
     * « Cette carte appartient-elle a ce compte ? »
     *
     * La couture posait la question a l'envers — « a qui est cette carte ? » —
     * et resolvait donc une ligne par identifiant seul, sans proprietaire.
     * Formulee ainsi, elle passe par `findById(ownerId, id)` et ne peut rien
     * apprendre sur la carte d'un autre, meme si l'appelant se trompe.
     */
    private val ownsDevice: (ownerId: String, deviceId: String) -> Boolean,
    private val sendToDevice: suspend (deviceId: String, frame: ByteArray) -> Boolean
) : ActionSender {

    override suspend fun send(action: PendingAction): SendResult {
        val params = runCatching { Json.parseToJsonElement(action.payload).jsonObject }
            .getOrElse { return SendResult.Fatal("unparseable payload") }
        val deviceId = params["deviceId"]?.jsonPrimitive?.content
            ?: return SendResult.Fatal("COMMAND without deviceId")
        val frame = runCatching {
            Base64.getDecoder().decode(params["payloadB64"]?.jsonPrimitive?.content ?: "")
        }.getOrElse { return SendResult.Fatal("payloadB64 is not base64") }
        if (frame.isEmpty()) return SendResult.Fatal("empty command frame")

        if (!ownsDevice(action.ownerId, deviceId)) {
            logger.error(
                "COMMAND ${action.id} targets device $deviceId not owned by ${action.ownerId} — REFUSED"
            )
            return SendResult.Fatal("cross-tenant command refused")
        }

        return if (sendToDevice(deviceId, frame)) SendResult.Ok
        else SendResult.Fatal("device offline — command lost (at-most-once)")
    }
}
