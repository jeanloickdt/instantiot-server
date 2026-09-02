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

package com.jeanloickdt.auth

import com.jeanloickdt.deviceRepository
import com.jeanloickdt.projectRepository
import com.jeanloickdt.signalRepository
import com.jeanloickdt.signalHistoryRepository
import com.jeanloickdt.userRepository
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.mindrot.jbcrypt.BCrypt
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deletion is a legal promise: after it, NO table holds a row keyed by this
 * owner — and, just as binding, every OTHER account's rows are untouched.
 * Both halves are asserted straight from SQLite: what matters is what is left
 * in the tables, not what a finder chooses to return.
 */
class AccountDeletionTest {


    /** Every table that carries an owner_id, and the users table itself. */
    private val OWNED_TABLES = listOf(
        "projects", "devices", "signals",
        "signal_raw", "signal_min", "signal_hour", "signal_day",
        "automation_rules", "pending_actions", "push_tokens", "message_usage"
    )

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.fresh()
    }

    private fun ApplicationTestBuilder.installTestApp() {
        application {
            install(ContentNegotiation) { json() }
            install(io.ktor.server.plugins.ratelimit.RateLimit) {
                register(io.ktor.server.plugins.ratelimit.RateLimitName("auth")) {
                    rateLimiter(limit = 100, refillPeriod = kotlin.time.Duration.parse("1m"))
                    requestKey { it.request.local.remoteAddress }
                }
            }
            configureAuth(userRepository, com.jeanloickdt.auth.LocalTestAuth.service)
            val connections = com.jeanloickdt.relay.ConnectionRegistry()
            val buffers     = com.jeanloickdt.relay.HistoryBuffers()
            val lastValues  = com.jeanloickdt.relay.InMemoryLastValueCache()
            val events      = com.jeanloickdt.relay.ControlEventBroadcaster(connections)
            val purge = AccountPurge(
                userRepository, projectRepository, deviceRepository, 
                signalRepository, signalHistoryRepository,
                connections, events
            )
            routing {
                // iia est simule : ce fichier eprouve la ROUTE, pas l'appel
                // au service d'identite — CloudAccountDeletionTest s'en charge.
                authRoutes(
                    userRepository, projectRepository, deviceRepository, connections,
                    com.jeanloickdt.auth.LocalTestAuth.service, purge
                )
            }
        }
    }

    // ── seeding : un compte COMPLET, chaque table peuplée ─────────────────

    private fun account(username: String, role: String = "user"): Pair<String, String> {
        val id = userRepository.create(username, BCrypt.hashpw("secret123", BCrypt.gensalt()), role, true)
        return id to com.jeanloickdt.auth.LocalTestAuth.token(id, tokenVersion = 0)
    }

    private fun seedFullAccount(ownerId: String) {
        val projectId = projectRepository.create(ownerId, "p-$ownerId").id
        deviceRepository.create(
            name         = "board", projectId = projectId, ownerId = ownerId,
            tokenHash = "hash-$ownerId",
            deviceType = com.jeanloickdt.device.domain.DeviceType.ESP32,
            connectivity = com.jeanloickdt.device.domain.DeviceConnectivity.WIFI
        ).id
        val deviceId = deviceRepository.findAllByProject(ownerId, projectId).first().id
        signalRepository.create(ownerId, deviceId, 0, "s0", "float", nowMs = 1L)
        val signalId = signalRepository.find(ownerId, deviceId, 0)!!.id
        exec("INSERT INTO signal_raw (signal_id, owner_id, ts, value) VALUES ($signalId,'$ownerId',1,1.0)")
        for (tier in listOf("min", "hour", "day")) {
            exec("""INSERT INTO signal_$tier
                    (signal_id, owner_id, bucket_at, avg_value, min_value, min_at, max_value, max_at, sample_count)
                    VALUES ($signalId,'$ownerId',1,1.0,1.0,1,1.0,1,1)""")
        }
        exec("""INSERT INTO automation_rules (id, owner_id, name, enabled, trigger_kind, definition, created_at, updated_at)
                VALUES ('r-$ownerId','$ownerId','rule',true,'value','{}',1,1)""")
        exec("INSERT INTO automation_state (rule_id, triggered, updated_at) VALUES ('r-$ownerId',false,1)")
        exec("INSERT INTO scheduled_jobs (rule_id, next_run_at, timezone) VALUES ('r-$ownerId',1,'UTC')")
        exec("""INSERT INTO pending_actions
                (idempotency_key, owner_id, type, payload, status, attempts, next_attempt_at, occurred_at, created_at)
                VALUES ('k-$ownerId','$ownerId','PUSH','{}','PENDING',0,0,0,0)""")
        exec("INSERT INTO push_tokens (token, owner_id, platform, updated_at) VALUES ('t-$ownerId','$ownerId','android',1)")
        exec("INSERT INTO message_usage (owner_id, period, count) VALUES ('$ownerId','2026-08',42)")
    }

    private fun exec(sql: String) = transaction {
        exec(sql)
    }

    private fun countOf(sql: String): Int = transaction {
        exec(sql) { rs -> rs.next(); rs.getInt(1) }!!
    }

    private fun rowsOf(ownerId: String): Map<String, Int> =
        OWNED_TABLES.associateWith { table ->
            // Toutes ces tables portent `owner_id` — c'est ce qui rend la
            // purge possible sans jointure, et ce que ce test vérifie.
            countOf("SELECT count(*) FROM $table WHERE owner_id = '$ownerId'")
        } + mapOf(
            "users" to countOf("SELECT count(*) FROM users WHERE id = '$ownerId'"),
            "automation_state" to
                countOf("SELECT count(*) FROM automation_state WHERE rule_id = 'r-$ownerId'"),
            "scheduled_jobs" to
                countOf("SELECT count(*) FROM scheduled_jobs WHERE rule_id = 'r-$ownerId'"),
        )

    // ── LA promesse ───────────────────────────────────────────────────────

    @Test
    fun `deleting my account erases every row I own — and nothing of anyone else`() = testApplication {
        installTestApp()
        val (aliceId, aliceToken) = account("alice")
        val (bobId, _) = account("bob")
        seedFullAccount(aliceId)
        seedFullAccount(bobId)

        // Precondition: every table genuinely holds a row for both.
        rowsOf(aliceId).forEach { (table, n) ->
            assertTrue(n >= 1, "seed must populate $table (got $n)")
        }

        val res = client.delete("/api/users/me") {
            header(HttpHeaders.Authorization, "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())

        // La promesse, table par table — lue dans la base, pas via un
        // depot qui choisirait ce qu'il renvoie.
        // La promesse, table par table — `users` comprise.
        //
        // C'est ici que les deux editions divergent, et il faut le savoir : le
        // nuage GARDE la ligne, anonymisee, parce qu'un jeton iia encore
        // valide reprovisionnerait le compte a la volee. Ici l'autorite EST ce
        // serveur : plus de ligne, plus de compte, et le jeton meurt avec.
        rowsOf(aliceId).forEach { (table, n) ->
            assertEquals(0, n, "$table must hold nothing of the deleted account")
        }

        // Et l'autre moitie, tout aussi engageante.
        rowsOf(bobId).forEach { (table, n) ->
            assertTrue(n >= 1, "$table must still hold bob's rows — deletion leaked ($n)")
        }
    }

    /**
     * Le jeton du compte supprime est mort DANS L'INSTANT.
     *
     * La validation locale relit le compte a chaque requete : plus de ligne,
     * plus d'entree. La revocation est donc immediate ici — le fantome de sept
     * jours est un probleme du NUAGE, ou la ligne survit anonyme pour que la
     * suppression d'identite puisse etre retentee.
     */
    @Test
    fun `le jeton du compte supprime est mort dans l instant`() = testApplication {
        installTestApp()
        val (id, token) = account("carol")
        seedFullAccount(id)

        client.delete("/api/users/me") { header(HttpHeaders.Authorization, "Bearer $token") }

        val after = client.delete("/api/users/me") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Unauthorized, after.status)
    }

    // ── Les gardes ────────────────────────────────────────────────────────

    @Test
    fun `the last admin cannot delete itself`() = testApplication {
        installTestApp()
        val (_, adminToken) = account("root", role = "admin")

        val res = client.delete("/api/users/me") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.Conflict, res.status,
            "orphaning the panel is a footgun, not a freedom")
    }

    @Test
    fun `an admin can delete itself when another admin remains`() = testApplication {
        installTestApp()
        val (_, adminToken) = account("root", role = "admin")
        account("root2", role = "admin")

        val res = client.delete("/api/users/me") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `an admin deletes another user, with the same full cascade`() = testApplication {
        installTestApp()
        val (_, adminToken) = account("root", role = "admin")
        val (targetId, _) = account("dave")
        seedFullAccount(targetId)

        val res = client.delete("/api/admin/users/$targetId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        // Meme cascade que la suppression de son propre compte, `users`
        // comprise : ici l'autorite est ce serveur, donc la ligne part.
        rowsOf(targetId).forEach { (table, n) ->
            assertEquals(0, n, "$table must hold nothing of the deleted account")
        }
    }

    @Test
    fun `the admin route refuses self-deletion — that act stays explicit`() = testApplication {
        installTestApp()
        val (adminId, adminToken) = account("root", role = "admin")

        val res = client.delete("/api/admin/users/$adminId") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `a non-admin cannot delete anyone else`() = testApplication {
        installTestApp()
        val (_, userToken) = account("eve")
        val (victimId, _) = account("victim")

        val res = client.delete("/api/admin/users/$victimId") {
            header(HttpHeaders.Authorization, "Bearer $userToken")
        }
        assertTrue(res.status == HttpStatusCode.Forbidden || res.status == HttpStatusCode.Unauthorized)
        assertEquals(1, rowsOf(victimId)["users"])
    }

    @Test
    fun `deleting an unknown user is a 404`() = testApplication {
        installTestApp()
        val (_, adminToken) = account("root", role = "admin")
        val res = client.delete("/api/admin/users/ghost") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }
}
