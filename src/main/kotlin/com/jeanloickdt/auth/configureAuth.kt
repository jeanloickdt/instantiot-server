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

import com.jeanloickdt.auth.domain.UserRepository
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

// ============================================================
// JWT config — fixed values, no need for YAML
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
                // verifies that the user still exists in the DB
                userRepository.findById(userId)?.let { JWTPrincipal(credential.payload) }
            }
        }
    }
}