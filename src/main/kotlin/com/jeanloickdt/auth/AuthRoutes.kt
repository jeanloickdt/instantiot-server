package com.jeanloickdt.auth

import com.jeanloickdt.auth.domain.AuthResponse
import com.jeanloickdt.auth.domain.LoginRequest
import com.jeanloickdt.auth.domain.RegisterRequest
import com.jeanloickdt.auth.domain.UserRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.mindrot.jbcrypt.BCrypt

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
        val body = call.receive<RegisterRequest>()

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
// Utilisé quand licence valide
// ============================================================
fun Route.authRoutes(userRepository: UserRepository) {
    loginRoute(userRepository)
    registerRoute(userRepository)
}