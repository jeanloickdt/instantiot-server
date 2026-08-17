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

import com.jeanloickdt.auth.domain.UserRepository
import com.jeanloickdt.auth.requireAdmin
import com.jeanloickdt.common.ApiError
import com.jeanloickdt.common.ServerConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class EmailConfigResponse(
    val configured: Boolean,
    /** The panel shows only the tail — a config screen must never re-leak a secret. */
    val apiKeyLast4: String?,
    val from: String,
    val fromName: String,
    val alertTo: String,
    /** Cloud: the key comes from the environment; the panel is read-only. */
    val managedByEnv: Boolean
)

@Serializable
data class UpdateEmailConfigRequest(
    val apiKey: String? = null,
    val from: String? = null,
    val fromName: String? = null,
    val alertTo: String? = null
)

@Serializable
data class TestEmailRequest(val to: String? = null)

/**
 * The self-host email setup, panel-first: paste the Brevo key, click "send a
 * test email", receive it — no SSH, no file editing, no restart (the sender
 * reads the config on every delivery).
 */
fun Route.emailConfigRoutes(
    userRepository: UserRepository,
    /** The REAL sender — the test button must exercise the exact production path. */
    sender: EmailActionSender
) {
    authenticate("jwt") {

        get("/api/admin/email-config") {
            call.requireAdmin(userRepository) ?: return@get
            val key = ServerConfig.emailBrevoApiKey
            call.respond(HttpStatusCode.OK, EmailConfigResponse(
                configured   = key.isNotBlank() && ServerConfig.emailFrom.isNotBlank(),
                apiKeyLast4  = key.takeIf { it.isNotBlank() }?.takeLast(4),
                from         = ServerConfig.emailFrom,
                fromName     = ServerConfig.emailFromName,
                alertTo      = ServerConfig.emailAlertTo,
                managedByEnv = ServerConfig.emailManagedByEnv
            ))
        }

        patch("/api/admin/email-config") {
            call.requireAdmin(userRepository) ?: return@patch
            if (ServerConfig.emailManagedByEnv) {
                // An edit silently overwritten at the next restart is worse
                // than a clear refusal.
                return@patch call.respond(HttpStatusCode.Conflict,
                    ApiError("Email is managed by the server environment (BREVO_API_KEY)"))
            }
            val body = call.receive<UpdateEmailConfigRequest>()
            if (body.from != null && body.from.isNotBlank() && "@" !in body.from) {
                return@patch call.respond(HttpStatusCode.BadRequest, ApiError("'from' must be an email address"))
            }
            if (body.alertTo != null && body.alertTo.isNotBlank() && "@" !in body.alertTo) {
                return@patch call.respond(HttpStatusCode.BadRequest, ApiError("'alertTo' must be an email address"))
            }
            ServerConfig.saveEmailConfig(body.apiKey, body.from, body.fromName, body.alertTo)
            call.respond(HttpStatusCode.OK, mapOf("message" to "Email config saved"))
        }

        // The test button — a pasted key without proof is a support ticket
        // deferred. Runs the exact production sender.
        post("/api/admin/email-config/test") {
            val admin = call.requireAdmin(userRepository) ?: return@post
            val body = runCatching { call.receive<TestEmailRequest>() }.getOrElse { TestEmailRequest() }
            val to = body.to
                ?: ServerConfig.emailAlertTo.takeIf { it.isNotBlank() }
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    ApiError("No recipient: set alertTo first, or pass {\"to\": …}"))

            val probe = PendingAction(
                id = -1, idempotencyKey = "test", ownerId = admin.id, ruleId = null,
                type = DeliveryWorker.TYPE_EMAIL,
                payload = """{"to":"$to","subject":"InstantIoT — email de test","body":"La configuration email fonctionne."}""",
                status = PendingAction.PENDING, attempts = 0, nextAttemptAt = 0, occurredAt = 0
            )
            when (val r = sender.send(probe)) {
                is SendResult.Ok    -> call.respond(HttpStatusCode.OK, mapOf("message" to "Test email sent to $to"))
                is SendResult.Fatal -> call.respond(HttpStatusCode.BadRequest, ApiError(r.reason))
                is SendResult.Retry -> call.respond(HttpStatusCode.BadGateway, ApiError("Brevo unreachable: ${r.reason}"))
            }
        }
    }
}
