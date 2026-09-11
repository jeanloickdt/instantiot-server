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

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.response.header
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("InstantIoT")

/**
 * Ce qu'une erreur repond, et ce qu'elle laisse dans le journal.
 *
 * Un seul gestionnaire attrapait tout, Throwable compris : un JSON casse sur
 * /api/login repondait 500 et journalisait la trace complete, dont l'extrait
 * du corps que cite l'exception de deserialisation, c'est-a-dire le mot de
 * passe quand l'erreur est pres du champ. Desormais 400 (ou 415, 404), la
 * classe de l'exception dans le journal et jamais son message ; la trace
 * reste aux vraies pannes.
 */
fun Application.installErrorPages() {
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            // La cause porte l'extrait du corps : on ne la journalise pas.
            logger.info("Requete mal formee sur ${call.request.local.uri} (${cause.cause?.javaClass?.simpleName ?: cause.javaClass.simpleName})")
            call.respond(HttpStatusCode.BadRequest, ApiError("Malformed request"))
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("Unsupported media type"))
        }
        exception<NotFoundException> { call, _ ->
            call.respond(HttpStatusCode.NotFound, ApiError("Not found"))
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception on ${call.request.local.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("Internal Server Error"))
        }
    }
}

/**
 * Un corps de requete declare au-dela de cette taille est refuse avant
 * lecture (413). Seule la mise en page avait sa borne ; tout le reste lisait
 * le corps entier en memoire.
 */
const val MAX_REQUEST_BODY_BYTES: Long = 1L * 1024 * 1024

fun Application.limitRequestBodies(maxBytes: Long = MAX_REQUEST_BODY_BYTES) {
    intercept(ApplicationCallPipeline.Plugins) {
        val declared = call.request.contentLength() ?: return@intercept
        if (declared <= maxBytes) return@intercept
        call.respond(HttpStatusCode.PayloadTooLarge, ApiError("Request body too large (max ${maxBytes / 1024} KB)"))
        finish()
    }
}

/**
 * Ce que le navigateur doit savoir, et que rien ne lui disait.
 *
 * Le nuage les pose dans Caddy ; le jumeau sert lui-meme son panneau, en
 * Ktor, et n'a pas de Caddy devant lui. Ne pas deviner les types, ne pas se
 * laisser encadrer, ne pas raconter d'ou il vient, ne rien demander a la
 * camera ni au micro. Pas de HSTS : le jumeau parle en clair sur un LAN,
 * et un HSTS pose sur du `http` ne fait rien, sinon pieger celui qui
 * mettra un jour un TLS devant.
 */
fun Application.installBrowserHeaders() {
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
    }
}
