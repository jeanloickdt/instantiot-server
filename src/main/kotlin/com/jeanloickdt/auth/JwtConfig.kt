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

    // 1 an. Self-hosted IoT : le serveur appartient à l'user, sur son LAN —
    // pas de threat model SaaS multi-tenant qui justifierait une rotation
    // courte + refresh token. Aligné sur le standard du domaine (Home
    // Assistant long-lived tokens). Évite la coupure silencieuse des
    // dashboards always-on (tablette kiosk) au bout de 30j.
    private const val EXPIRY_MS = 365L * 24 * 60 * 60 * 1000

    val secret: String by lazy {
        val configFile = File("${System.getProperty("user.home")}/.instantiot/secret.key")
        if (configFile.exists()) {
            configFile.readText().trim()
        } else {
            val generated = UUID.randomUUID().toString() + UUID.randomUUID().toString()
            configFile.parentFile.mkdirs()
            configFile.writeText(generated)
            // permissions owner-only — protège contre les autres utilisateurs du système
            try {
                Files.setPosixFilePermissions(
                    configFile.toPath(),
                    PosixFilePermissions.fromString("rw-------")
                )
            } catch (_: UnsupportedOperationException) {
                // Windows — POSIX permissions non supportées
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