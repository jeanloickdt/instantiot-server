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

package com.jeanloickdt.relay

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Ce qu'on sacrifie quand l'écrivain ne suit plus.
 *
 * [BoundedIngestQueue] refuse quand elle est pleine, et c'est honnête — mais
 * elle refuse **uniformément**, chaque file pour son compte. La question
 * qu'elle ne pose pas est celle qui compte : *lequel des deux paliers doit
 * souffrir ?*
 *
 * La réponse ne se discute pas. **Le brut est un confort, la courbe est le
 * produit.** Un compte qui perd son zoom fin sur les dernières heures a
 * toujours ses courbes ; un compte qui perd des seaux minute a des moyennes
 * fausses et jolies, et ne le saura jamais. Entre les deux, la contre-pression
 * jette le brut, toujours, et d'abord.
 *
 * ## Pourquoi la file pleine ne suffisait pas
 *
 * La saturation du brut arrive **après** que le mal est fait : la file ne se
 * remplit que parce que le tour de vidage a pris trop de temps, et pendant ce
 * temps le même tour peinait déjà à écrire les seaux minute. Attendre qu'elle
 * déborde, c'est attendre un symptôme pour traiter la cause.
 *
 * D'où la mesure prise à la source : **la durée du tour**. Un tour qui coûte
 * sa propre période ne rattrapera pas — la boucle est `delay(période)` PUIS le
 * travail, donc la période effective s'étire, les tampons prennent davantage,
 * et la dérive s'accumule. C'est le moment de lâcher le brut, avant que la
 * file en parle.
 *
 * ## L'hystérésis, et pourquoi elle n'est pas du zèle
 *
 * Un seul tour lent n'est pas une saturation : c'est une sauvegarde, un
 * point de contrôle de la base, un voisin bruyant sur le VPS. Engager sur un tour
 * unique ferait clignoter le brut plusieurs fois par minute, et le résultat
 * serait un palier troué — pire qu'un palier absent, parce qu'un trou se
 * confond avec un capteur muet.
 *
 * D'où deux seuils différents : [slowRoundsToEngage] tours lents d'affilée
 * pour lâcher, [healthyRoundsToRelease] tours sains d'affilée pour reprendre.
 * Reprendre plus lentement qu'on ne lâche est délibéré — la reprise coûte, et
 * l'écrivain doit avoir rattrapé pour de bon, pas juste soufflé un tour.
 *
 * ## Ce qui n'est PAS ici
 *
 * Aucun réglage exposé. Un opérateur ne doit pas pouvoir choisir de sacrifier
 * ses courbes pour garder son brut : ce serait un choix qui a l'air raisonnable
 * et qui produit des données fausses.
 */
class IngestBackPressure(
    private val slowRoundsToEngage: Int = 2,
    private val healthyRoundsToRelease: Int = 3
) {
    private val suspended = AtomicBoolean(false)
    private val dropped = AtomicLong(0)

    // Comptés uniquement depuis la boucle de vidage, qui est mono-coroutine :
    // pas d'atomique là où il n'y a pas de concurrence.
    private var slowStreak = 0
    private var healthyStreak = 0

    /** Le brut est-il lâché en ce moment. Lu une fois par trame. */
    val isRawSuspended: Boolean get() = suspended.get()

    /** Combien d'échantillons bruts la contre-pression a écartés depuis le boot. */
    val droppedRaw: Long get() = dropped.get()

    /**
     * Un tour de vidage vient de finir. Appelé par la boucle, jamais par le
     * chemin chaud.
     *
     * @param roundTookMs sa durée
     * @param periodMs    la cadence configurée
     * @return `true` si l'état vient de changer — l'appelant peut alors le
     *         dire une fois, plutôt qu'à chaque tour.
     */
    fun record(roundTookMs: Long, periodMs: Long): Boolean {
        val slow = roundTookMs >= periodMs
        if (slow) {
            healthyStreak = 0
            slowStreak++
            if (!suspended.get() && slowStreak >= slowRoundsToEngage) {
                suspended.set(true)
                return true
            }
        } else {
            slowStreak = 0
            healthyStreak++
            if (suspended.get() && healthyStreak >= healthyRoundsToRelease) {
                suspended.set(false)
                return true
            }
        }
        return false
    }

    /**
     * Le chemin chaud demande s'il peut écrire du brut.
     *
     * Compte le refus au passage : une dégradation que personne ne mesure est
     * une dégradation qu'on découvre par une plainte.
     *
     * @return `true` si l'échantillon peut aller au tampon.
     */
    fun allowRaw(): Boolean {
        if (!suspended.get()) return true
        dropped.incrementAndGet()
        return false
    }
}
