package com.jeanloickdt.auth

import com.jeanloickdt.auth.domain.AuthResponse
import com.jeanloickdt.auth.LicenceValidator
import com.jeanloickdt.auth.domain.LoginRequest
import com.jeanloickdt.auth.domain.RegisterRequest
import com.jeanloickdt.auth.domain.UserRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.mindrot.jbcrypt.BCrypt

// ============================================================
// Validation — contraintes username et password
// ============================================================
private val USERNAME_REGEX = Regex("^[a-zA-Z0-9_]{3,32}$")
private const val PASSWORD_MIN_LENGTH = 8
private const val PASSWORD_MAX_LENGTH = 128

// ============================================================
// 🔓 LOGIN — toujours accessible, même sans licence
// ============================================================
fun Route.loginRoute(userRepository: UserRepository) {
    post("/api/login") {
        val body = call.receive<LoginRequest>()

        val user = userRepository.findByUsername(body.username)
        if (user == null || !BCrypt.checkpw(body.password, user.pwdHash)) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
            return@post
        }

        val token = JwtConfig.generateToken(user.id)
        call.respond(AuthResponse(token = token))
    }
}

// ============================================================
// 📝 REGISTER — bloqué si licence invalide
// ============================================================
fun Route.registerRoute(userRepository: UserRepository) {
    post("/api/register") {
        // licence requise pour créer un compte
        if (!LicenceValidator.isActivated()) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Licence required"))
            return@post
        }

        val body = call.receive<RegisterRequest>()

        // validation username — 3-32 caractères alphanumériques + underscore
        if (!USERNAME_REGEX.matches(body.username)) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Username must be 3-32 characters, alphanumeric and underscores only"
            ))
            return@post
        }

        // validation password — 8-128 caractères
        if (body.password.length < PASSWORD_MIN_LENGTH || body.password.length > PASSWORD_MAX_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Password must be between $PASSWORD_MIN_LENGTH and $PASSWORD_MAX_LENGTH characters"
            ))
            return@post
        }

        if (userRepository.findByUsername(body.username) != null) {
            call.respond(HttpStatusCode.Conflict, "Username already exists")
            return@post
        }

        val hash   = BCrypt.hashpw(body.password, BCrypt.gensalt())
        val userId = userRepository.create(body.username, hash)
        val token  = JwtConfig.generateToken(userId)

        call.respond(HttpStatusCode.Created, AuthResponse(token = token))
    }
}

// ============================================================
// 🔐 AUTH ROUTES COMPLÈTES — login + register
// Rate limited par IP — 10 requêtes / minute
// ============================================================
fun Route.authRoutes(userRepository: UserRepository) {
    rateLimit(RateLimitName("auth")) {
        loginRoute(userRepository)
        registerRoute(userRepository)
    }
}
