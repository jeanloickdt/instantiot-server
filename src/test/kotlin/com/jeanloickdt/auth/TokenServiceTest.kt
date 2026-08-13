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

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.jeanloickdt.auth.domain.UserRow
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [HmacTokenService] — the session-token authority. Two concerns:
 *  1. the verifier (signature / issuer / audience / expiry), and
 *  2. the token_version revocation primitive.
 */
class TokenServiceTest {

    private val secret = "a-fixed-test-secret"
    private val issuer = "instantiot-server"
    private val audience = "instantiot-app"
    private val svc = HmacTokenService(secret, issuer, audience)

    private fun user(tokenVersion: Int) = UserRow(
        id = "u", username = "alice", pwdHash = "x", role = "user",
        passwordChanged = true, tokenVersion = tokenVersion, createdAt = 0
    )

    // ════════════════════════════════════════════════════════════
    // Revocation proof — the core of this feature
    //   token v0 valid @v0  →  bump  →  v0 REJECTED @v1  →  v1 re-issued accepted
    // ════════════════════════════════════════════════════════════

    @Test
    fun `a token is valid for the version it was issued at`() {
        val t0 = svc.verifier.verify(svc.issue("u", 0))
        assertTrue(svc.isValid(t0, user(0)))
    }

    @Test
    fun `bumping token_version revokes a previously-issued token`() {
        val t0 = svc.verifier.verify(svc.issue("u", 0))
        // user's token_version was incremented (e.g. password change) → old token rejected
        assertFalse(svc.isValid(t0, user(1)))
    }

    @Test
    fun `a token re-issued at the new version is accepted`() {
        val t1 = svc.verifier.verify(svc.issue("u", 1))
        assertTrue(svc.isValid(t1, user(1)))
    }

    @Test
    fun `a legacy token without the ver claim counts as version 0`() {
        // backward compatibility: tokens minted before this feature have no `ver`
        val legacy = JWT.create()
            .withSubject("u").withIssuer(issuer).withAudience(audience)
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256(secret))
        val decoded = svc.verifier.verify(legacy)
        assertTrue(svc.isValid(decoded, user(0)), "legacy token valid while user still at v0")
        assertFalse(svc.isValid(decoded, user(1)), "legacy token revoked once the user is bumped")
    }

    // ════════════════════════════════════════════════════════════
    // Verifier — signature / issuer / audience / expiry
    // ════════════════════════════════════════════════════════════

    @Test
    fun `issue then verify round-trips subject, issuer and audience`() {
        val decoded = svc.verifier.verify(svc.issue("user-123", 0))
        assertEquals("user-123", decoded.subject)
        assertEquals(issuer, decoded.issuer)
        assertTrue(decoded.audience.contains(audience))
        assertEquals(0, decoded.getClaim("ver").asInt())
    }

    @Test
    fun `verify rejects a tampered signature`() {
        val token = svc.issue("u", 0)
        // Flip a character in the MIDDLE of the signature, not the last one.
        // A 256-bit HMAC signature is 43 base64url characters: the final one
        // carries only 4 significant bits, its 2 low bits being padding. 'A'
        // and 'B' differ solely by one of those, so they decode to the SAME
        // bytes — the token stays valid and the test fails intermittently.
        val sigStart = token.lastIndexOf('.') + 1
        val at = sigStart + 5
        val tampered = token.substring(0, at) +
            (if (token[at] == 'A') 'B' else 'A') +
            token.substring(at + 1)
        assertFailsWith<JWTVerificationException> { svc.verifier.verify(tampered) }
    }

    @Test
    fun `verify rejects a token signed with a different secret`() {
        val forged = JWT.create()
            .withSubject("attacker").withIssuer(issuer).withAudience(audience)
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256("a-totally-different-secret"))
        assertFailsWith<JWTVerificationException> { svc.verifier.verify(forged) }
    }

    @Test
    fun `verify rejects a wrong issuer even with the right secret`() {
        val wrongIssuer = JWT.create()
            .withSubject("u").withIssuer("evil-issuer").withAudience(audience)
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256(secret))
        assertFailsWith<JWTVerificationException> { svc.verifier.verify(wrongIssuer) }
    }

    @Test
    fun `verify rejects a wrong audience even with the right secret`() {
        val wrongAudience = JWT.create()
            .withSubject("u").withIssuer(issuer).withAudience("some-other-app")
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256(secret))
        assertFailsWith<JWTVerificationException> { svc.verifier.verify(wrongAudience) }
    }

    @Test
    fun `verify rejects an expired token`() {
        val expired = JWT.create()
            .withSubject("u").withIssuer(issuer).withAudience(audience)
            .withClaim("ver", 0)
            .withExpiresAt(Date(System.currentTimeMillis() - 60_000))
            .sign(Algorithm.HMAC256(secret))
        assertFailsWith<JWTVerificationException> { svc.verifier.verify(expired) }
    }
}
