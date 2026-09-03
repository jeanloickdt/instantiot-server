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

package com.jeanloickdt.signal.data

/**
 * Réduit plusieurs seaux fins en un seau grossier — minute → heure,
 * heure → jour. Aucune arithmétique neuve : c'est [SignalMerge], appliqué à
 * des morceaux qui ne portent pas encore le même `bucket_at`.
 *
 * ## Pourquoi retagger avant de fusionner
 *
 * `SignalMerge.merge` refuse deux seaux d'instants différents — c'est sa
 * garde, et elle reste vraie ici. Les seaux minute d'une même heure ont
 * chacun leur propre `bucket_at` (00:00, 00:01, 00:02…) ; ce n'est qu'une
 * fois réétiquetés sur le seau cible (l'heure entière) qu'ils peuvent se
 * fusionner entre eux — la vérité qu'ils portent ne change pas, seule la
 * fenêtre à laquelle on les rapporte change.
 */
object SignalRollup {

    /**
     * @throws IllegalArgumentException si `pieces` est vide, ou si les
     *         morceaux n'appartiennent pas tous au même signal — fusionner
     *         les seaux de deux signaux produirait une ligne qui prétend
     *         décrire les deux à la fois.
     */
    fun combine(bucketAt: Long, pieces: List<SignalBucketAccumulator.Snapshot>): SignalBucketAccumulator.Snapshot {
        require(pieces.isNotEmpty()) { "rien à dériver pour bucketAt=$bucketAt" }
        val signalId = pieces.first().signalId
        require(pieces.all { it.signalId == signalId }) {
            "combine() refuse de mélanger des signaux — appelant en faute"
        }
        return pieces.asSequence()
            .map { it.copy(bucketAt = bucketAt) }
            .reduce(SignalMerge::merge)
    }

    /** Le début du seau grossier qui contient `ts`, dans une taille de fenêtre donnée. */
    fun truncateTo(ts: Long, bucketSizeMs: Long): Long = (ts / bucketSizeMs) * bucketSizeMs

    const val HOUR_MS = 3_600_000L
    const val DAY_MS  = 86_400_000L
}
