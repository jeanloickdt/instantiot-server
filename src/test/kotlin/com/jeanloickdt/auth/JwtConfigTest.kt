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
import java.util.Date
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Security tests for [JwtConfig] — the server's session-token authority.
 *
 * A flaw here means forged or expired tokens could be accepted (anyone gets in)
 * or valid tokens rejected (everyone locked out). We assert the verifier honours
 * the signature, issuer, audience and expiry, and a happy-path round-trip.
 *
 * Note: `JwtConfig.secret` lazily loads/creates ~/.instantiot/secret.key — the
 * generate/verify pair uses that same secret, so the round-trip is self-consistent.
 */
class JwtConfigTest {

    private val issuer = "instantiot-server"
    private val audience = "instantiot-app"

    @BeforeTest
    fun setup() {
        JwtConfig.init(issuer, audience)
    }

    @Test
    fun `generateToken then verify round-trips the user id, issuer and audience`() {
        val token = JwtConfig.generateToken("user-123")
        val decoded = JwtConfig.verifier.verify(token)

        assertEquals("user-123", decoded.subject)
        assertEquals(issuer, decoded.issuer)
        assertTrue(decoded.audience.contains(audience))
    }

    @Test
    fun `verify rejects a tampered signature`() {
        val token = JwtConfig.generateToken("user-123")
        // flip the last char of the signature segment → signature no longer matches
        val tampered = token.dropLast(1) + if (token.last() == 'A') 'B' else 'A'
        assertFailsWith<JWTVerificationException> { JwtConfig.verifier.verify(tampered) }
    }

    @Test
    fun `verify rejects a token signed with a different secret`() {
        val forged = JWT.create()
            .withSubject("attacker")
            .withIssuer(issuer)
            .withAudience(audience)
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256("a-totally-different-secret"))
        assertFailsWith<JWTVerificationException> { JwtConfig.verifier.verify(forged) }
    }

    @Test
    fun `verify rejects a wrong issuer even with the right secret`() {
        val wrongIssuer = JWT.create()
            .withSubject("u")
            .withIssuer("evil-issuer")
            .withAudience(audience)
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256(JwtConfig.secret))
        assertFailsWith<JWTVerificationException> { JwtConfig.verifier.verify(wrongIssuer) }
    }

    @Test
    fun `verify rejects a wrong audience even with the right secret`() {
        val wrongAudience = JWT.create()
            .withSubject("u")
            .withIssuer(issuer)
            .withAudience("some-other-app")
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256(JwtConfig.secret))
        assertFailsWith<JWTVerificationException> { JwtConfig.verifier.verify(wrongAudience) }
    }

    @Test
    fun `verify rejects an expired token`() {
        val expired = JWT.create()
            .withSubject("u")
            .withIssuer(issuer)
            .withAudience(audience)
            .withExpiresAt(Date(System.currentTimeMillis() - 60_000)) // 1 min in the past
            .sign(Algorithm.HMAC256(JwtConfig.secret))
        assertFailsWith<JWTVerificationException> { JwtConfig.verifier.verify(expired) }
    }

    @Test
    fun `generated token expiry is far in the future`() {
        val decoded = JwtConfig.verifier.verify(JwtConfig.generateToken("u"))
        val now = System.currentTimeMillis()
        // policy is 1 year; assert at least ~300 days ahead to allow slack
        assertTrue(decoded.expiresAt.time > now + 300L * 24 * 60 * 60 * 1000)
    }
}
