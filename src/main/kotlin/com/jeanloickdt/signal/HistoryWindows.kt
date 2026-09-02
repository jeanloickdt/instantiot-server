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

package com.jeanloickdt.signal

/**
 * Les quatre paliers d'historique, et ce que chacun permet.
 *
 * ## Pourquoi ce fichier existe ici, et pas dans `plan/`
 *
 * Dans le nuage, cet objet vit dans le module des plans : c'est la grille de
 * prix qui décide jusqu'où un compte remonte, palier par palier. Le module
 * des plans ne descend pas ici — un serveur auto-hébergé ne vend rien, et
 * porter la tarification pour trois constantes serait payer une dépendance
 * entière pour la forme d'une donnée.
 *
 * Ne descend donc que la FORME — [Tier], [Window], l'ordre des paliers — plus
 * la seule réponse qui ait un sens sans grille : [unlimited]. Le disque de
 * l'opérateur est la seule borne, et elle ne se déclare pas ici.
 *
 * C'est la couture entre les deux éditions. Elle est nommée, elle est petite,
 * et elle est le seul écart du paquet `signal` avec celui du nuage.
 */
object HistoryWindows {

    /**
     * Les quatre paliers, dans l'ordre où une courbe les traverse en
     * dézoomant. L'ordre est porté par la réponse : l'app affiche le sélecteur
     * dans cet ordre sans avoir à le connaître.
     */
    val TIERS: List<Tier> = listOf(
        Tier("raw"),
        Tier("min"),
        Tier("hour"),
        Tier("day")
    )

    /**
     * @param granularity la valeur du paramètre `resolution` de la route
     *        d'historique.
     */
    data class Tier(val granularity: String)

    /**
     * Ce qu'un palier permet à un instant donné.
     *
     * @param available     le palier est-il servi du tout.
     * @param retention     la durée en toutes lettres, pour l'afficher —
     *                      `"unlimited"` ici, faute de grille qui dise autre
     *                      chose.
     * @param fromMs        le plus ancien instant qu'il est utile de demander,
     *                      ou `null` quand rien ne borne.
     */
    data class Window(
        val granularity: String,
        val available: Boolean,
        val retention: String,
        val fromMs: Long?
    )

    /**
     * Tout est servi, rien n'est borné.
     *
     * Ce n'est pas une valeur de repli faute de mieux : un serveur qu'on
     * héberge chez soi n'a pas de rétention vendue, il a un disque. Ce que
     * l'opérateur veut garder, il le règle dans `server.properties`, et le
     * balayage de rétention s'en occupe — pas cette réponse-ci, qui dit
     * seulement à l'app ce qu'elle a le droit de demander.
     */
    fun unlimited(): List<Window> = TIERS.map {
        Window(it.granularity, available = true, retention = "unlimited", fromMs = null)
    }
}
