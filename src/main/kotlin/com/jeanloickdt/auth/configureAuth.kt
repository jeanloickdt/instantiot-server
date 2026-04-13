package com.jeanloickdt.auth

import com.jeanloickdt.auth.domain.UserRepository
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

fun Application.configureAuth(userRepository: UserRepository) {
    val jwtAudience = environment.config.property("jwt.audience").getString()
    val jwtIssuer   = environment.config.property("jwt.issuer").getString()
    val jwtRealm    = environment.config.property("jwt.realm").getString()

    JwtConfig.init(jwtIssuer, jwtAudience)

    authentication {
        jwt("jwt") {
            realm = jwtRealm
            verifier(JwtConfig.verifier)
            validate { credential ->
                val userId = credential.payload.subject ?: return@validate null
                // vérifie que le user existe encore en DB
                userRepository.findById(userId)?.let { JWTPrincipal(credential.payload) }
            }
        }
    }
}