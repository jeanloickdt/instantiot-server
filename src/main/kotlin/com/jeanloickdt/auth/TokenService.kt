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

// auth/TokenService.kt
package com.jeanloickdt.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import com.auth0.jwt.interfaces.Payload
import com.jeanloickdt.auth.domain.UserRow
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Date
import java.util.UUID

/**
 * Issues and validates session tokens. Injected by constructor (no global
 * singleton) so the cloud evolution is additive: a future impl (refresh
 * tokens, per-token `jti` blocklist, asymmetric keys, external IdP) is ADDED
 * behind this seam without touching the call sites — exactly the discipline
 * applied to the relay seams.
 *
 * Today: [HmacTokenService] (HS256 + a `token_version` revocation claim).
 */
interface TokenService {
    /** The verifier wired into the Ktor `jwt` plugin (signature/issuer/audience). */
    val verifier: JWTVerifier

    /** Issues a token for [userId] carrying the user's current [tokenVersion]. */
    fun issue(userId: String, tokenVersion: Int): String

    /**
     * True if the (signature-verified) token is still valid for [user] — i.e.
     * its embedded version is not below the user's current `token_version`.
     * Incrementing `token_version` (any password change) therefore revokes
     * every previously-issued token at once.
     */
    fun isValid(payload: Payload, user: UserRow): Boolean
}

/**
 * HMAC-SHA256 token service. Mono-node is correct here: a single process signs
 * and verifies with the same secret (asymmetric RS256/JWKS would only matter
 * once several distinct services must verify without sharing the secret — a
 * deferred multi-node concern).
 */
class HmacTokenService(
    private val secret: String,
    private val issuer: String,
    private val audience: String,
    // 1 year. LAN appliance: the server belongs to the user; no SaaS threat
    // model justifying short rotation + refresh. Avoids cutting off always-on
    // kiosk dashboards. (Short access + refresh is a named, deferred cloud item.)
    private val expiryMs: Long = 365L * 24 * 60 * 60 * 1000
) : TokenService {

    private val algorithm = Algorithm.HMAC256(secret)

    override val verifier: JWTVerifier =
        JWT.require(algorithm).withIssuer(issuer).withAudience(audience).build()

    override fun issue(userId: String, tokenVersion: Int): String =
        JWT.create()
            .withSubject(userId)
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaim(CLAIM_VERSION, tokenVersion)
            .withExpiresAt(Date(System.currentTimeMillis() + expiryMs))
            .sign(algorithm)

    override fun isValid(payload: Payload, user: UserRow): Boolean {
        // Backward-compatible: a token minted before this feature has no `ver`
        // claim → treated as version 0. It stays valid until the first
        // revocation bumps the user past 0.
        val tokenVersion = payload.getClaim(CLAIM_VERSION).asInt() ?: 0
        return tokenVersion >= user.tokenVersion
    }

    companion object {
        const val CLAIM_VERSION = "ver"
    }
}

/**
 * Loads (or first-time creates) the HMAC secret at `~/.instantiot/secret.key`,
 * with owner-only permissions. Unchanged from the previous JwtConfig logic.
 */
fun loadOrCreateJwtSecret(): String {
    val file = File("${System.getProperty("user.home")}/.instantiot/secret.key")
    if (file.exists()) return file.readText().trim()
    val generated = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    file.parentFile.mkdirs()
    file.writeText(generated)
    try {
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
    } catch (_: UnsupportedOperationException) {
        // Windows — POSIX permissions not supported
    }
    return generated
}
