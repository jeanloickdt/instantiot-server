package com.jeanloickdt.auth

import com.jeanloickdt.auth.domain.UserRepository
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

// ============================================================
// JWT config — valeurs fixes, pas besoin de YAML
// ============================================================
private const val JWT_ISSUER   = "instantiot-server"
private const val JWT_AUDIENCE = "instantiot-app"
private const val JWT_REALM    = "instantiot"

fun Application.configureAuth(userRepository: UserRepository) {

    JwtConfig.init(JWT_ISSUER, JWT_AUDIENCE)

    authentication {
        jwt("jwt") {
            realm = JWT_REALM
            verifier(JwtConfig.verifier)
            validate { credential ->
                val userId = credential.payload.subject ?: return@validate null
                // vérifie que le user existe encore en DB
                userRepository.findById(userId)?.let { JWTPrincipal(credential.payload) }
            }
        }
    }
}
