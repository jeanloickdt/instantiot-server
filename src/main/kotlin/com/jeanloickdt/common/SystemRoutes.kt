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

package com.jeanloickdt.common

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String, val uptimeMs: Long)

@Serializable
data class VersionResponse(val version: String)

/**
 * Unauthenticated liveness + version endpoints, kept separate from the
 * setup-oriented `/api/status`:
 *  - `GET /health`      → `{ "status": "ok", "uptimeMs": ... }` for load
 *    balancers / uptime monitors / `systemctl` health checks.
 *  - `GET /api/version` → `{ "version": "1.2.0" }` for the app and a future
 *    auto-update check, without needing a JWT.
 */
fun Route.systemRoutes() {
    get("/health") {
        call.respond(HealthResponse(status = "ok", uptimeMs = ServerConfig.uptimeMs))
    }
    get("/api/version") {
        call.respond(VersionResponse(version = ServerConfig.version))
    }
}
