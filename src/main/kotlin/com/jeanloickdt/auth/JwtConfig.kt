/*
 * InstantIoT Server — self-hosted IoT relay for makers.
 * Copyright (C) 2026 InstantIoT
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
import com.auth0.jwt.interfaces.JWTVerifier
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Date
import java.util.UUID

object JwtConfig {

    // 1 year. Self-hosted IoT: the server belongs to the user, on their LAN —
    // no multi-tenant SaaS threat model that would justify short
    // rotation + refresh token. Aligned with the domain standard (Home
    // Assistant long-lived tokens). Avoids silently cutting off
    // always-on dashboards (kiosk tablet) after 30 days.
    private const val EXPIRY_MS = 365L * 24 * 60 * 60 * 1000

    val secret: String by lazy {
        val configFile = File("${System.getProperty("user.home")}/.instantiot/secret.key")
        if (configFile.exists()) {
            configFile.readText().trim()
        } else {
            val generated = UUID.randomUUID().toString() + UUID.randomUUID().toString()
            configFile.parentFile.mkdirs()
            configFile.writeText(generated)
            // owner-only permissions — protects against other users of the system
            try {
                Files.setPosixFilePermissions(
                    configFile.toPath(),
                    PosixFilePermissions.fromString("rw-------")
                )
            } catch (_: UnsupportedOperationException) {
                // Windows — POSIX permissions not supported
            }
            generated
        }
    }

    private lateinit var issuer: String
    private lateinit var audience: String

    fun init(issuer: String, audience: String) {
        this.issuer   = issuer
        this.audience = audience
    }

    val verifier: JWTVerifier
        get() = JWT
            .require(Algorithm.HMAC256(secret))
            .withIssuer(issuer)
            .withAudience(audience)
            .build()

    fun generateToken(userId: String): String = JWT.create()
        .withSubject(userId)
        .withIssuer(issuer)
        .withAudience(audience)
        .withExpiresAt(Date(System.currentTimeMillis() + EXPIRY_MS))
        .sign(Algorithm.HMAC256(secret))
}