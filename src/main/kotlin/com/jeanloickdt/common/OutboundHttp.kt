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

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration

/**
 * Le seul client HTTP sortant du jumeau, et ses delais.
 *
 * ## Le trou que ceci ferme
 *
 * Deux clients `HttpClient.newHttpClient()`, Brevo et ntfy, sans delai de
 * connexion ni de requete. Un ntfy ou un Brevo qui accepte le TCP et ne
 * repond jamais gardait la boucle de livraison pour toujours, et avec elle
 * toutes les alertes qui suivaient. Un serveur de salon ne s'en apercoit
 * qu'a l'incident que l'alerte devait annoncer.
 *
 * ## Ce que ca fait
 *
 * Un client, partage (il est fait pour ca : pool de connexions, threads), qui
 * refuse d'attendre une connexion plus de [CONNECT] ; et chaque requete
 * bornee par [bounded] : dix secondes par defaut, ce qu'aucun appel legitime
 * n'atteint et ce que tout appel malade depasse.
 */
object OutboundHttp {
    val CONNECT: Duration = Duration.ofSeconds(3)
    val REQUEST: Duration = Duration.ofSeconds(10)

    val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT)
        .build()

    fun HttpRequest.Builder.bounded(timeout: Duration = REQUEST): HttpRequest.Builder = timeout(timeout)
}
