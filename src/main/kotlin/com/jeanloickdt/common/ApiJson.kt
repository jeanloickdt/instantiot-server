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

import kotlinx.serialization.json.Json

/**
 * Le JSON de l'API HTTP — un seul, pour que le banc d'essai teste la vraie
 * configuration.
 *
 * ## Pourquoi il est tolérant
 *
 * `Json.Default` est STRICT : une clé que ce serveur ne connaît pas fait
 * échouer la désérialisation du **corps entier**, donc une 500. Ce n'était
 * pas « le champ nouveau est refusé », c'était « la requête est illisible » —
 * un renommage parti dans la même requête disparaissait avec lui, et le
 * message rendu ne disait rien de la cause.
 *
 * Or le décalage de versions n'est pas un incident ici, c'est l'état normal :
 * un relais auto-hébergé se met à jour quand son propriétaire le décide,
 * jamais en même temps que les téléphones qui s'y connectent. Une app plus
 * récente que son relais est la règle, pas l'exception.
 *
 * Le champ inconnu est donc perdu — et c'est exact. Un serveur qui ne sait
 * pas le stocker ne doit pas prétendre le stocker ; il doit appliquer ce
 * qu'il comprend et ignorer le reste.
 *
 * ## Pourquoi il vit ici et pas dans `Application.kt`
 *
 * Le banc d'essai des routes montait son propre `install(ContentNegotiation)`
 * avec `json()`. Il reproduisait donc la configuration de production **par
 * coïncidence**, et n'aurait pas remarqué qu'elle change. Une seule valeur,
 * citée des deux côtés, fait qu'un test sur la tolérance teste bien celle qui
 * tourne en vrai.
 */
val apiJson: Json = Json { ignoreUnknownKeys = true }
