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
 * La fusion de deux seaux — écrite une fois, utilisée à trois endroits :
 * sur conflit dans `signal_min`, dérivation minute → heure, dérivation
 * heure → jour.
 *
 * ## Pourquoi Kotlin, et pas `ON CONFLICT DO UPDATE`
 *
 * Un seul `UPDATE ... SET` avec des `CASE WHEN` aurait porté cette même
 * arithmétique une DEUXIÈME fois, en SQL — exactement le défaut que ce
 * fichier existe pour éviter. Ici, elle vit une fois, testée une fois, et
 * la même fonction servira à la dérivation périodique de l'étape 3 sans
 * qu'une ligne d'elle ne change.
 *
 * ## Associative et commutative — et pourquoi ça compte
 *
 * `merge(a, b) == merge(b, a)`, et `merge(merge(a, b), c) == merge(a,
 * merge(b, c))`. C'est cette propriété qui rend sûrs un batch rejoué, un
 * flush coupé en deux, un rollup relancé — l'ordre dans lequel les
 * morceaux arrivent ne doit jamais changer le résultat.
 *
 * `count`, `avg` (pondérée), `min`, `max` ont cette propriété nativement.
 * `min_at`/`max_at` ne l'auraient pas SANS la règle suivante.
 *
 * ## Le piège de l'égalité
 *
 * Si les deux côtés ont exactement le même minimum, il faut trancher de
 * façon déterministe — **le plus ancien des deux instants** — sinon
 * `merge(a, b)` et `merge(b, a)` divergent sur `min_at` alors qu'ils
 * s'accordent sur `min_value`, et deux exécutions du même rollup
 * produisent deux horodatages différents pour la même valeur.
 */
object SignalMerge {

    /**
     * @throws IllegalArgumentException si `a` et `b` ne sont pas le même
     *         seau — fusionner deux instants ou deux comptes différents
     *         n'a pas de sens, et le laisser passer produirait une ligne
     *         qui prétend décrire les deux à la fois.
     */
    fun merge(a: SignalBucketAccumulator.Snapshot, b: SignalBucketAccumulator.Snapshot): SignalBucketAccumulator.Snapshot {
        require(a.signalId == b.signalId && a.bucketAt == b.bucketAt) {
            "fusion refusée : seaux différents (${a.signalId}@${a.bucketAt} vs ${b.signalId}@${b.bucketAt})"
        }

        val count = a.sampleCount + b.sampleCount

        // Moyenne pondérée, jamais moyenne des moyennes — voir le document
        // du modèle cible pour l'exemple à 24% d'erreur. count=0 des deux
        // côtés ne devrait jamais arriver (un seau vide n'est pas flushé),
        // mais la division par zéro serait une exception plus tard, à un
        // endroit qui n'explique rien : on retombe sur 0.0 ici plutôt que
        // de propager un NaN silencieux.
        val avg = if (count > 0) {
            (a.avgValue * a.sampleCount + b.avgValue * b.sampleCount) / count
        } else 0.0

        // Égalité stricte sur les Double : les deux valeurs viennent de
        // MIN()/MAX() sur des échantillons réels, jamais d'un calcul — pas
        // de dérive à virgule flottante à craindre ici, contrairement à une
        // somme ou une moyenne.
        val (minValue, minAt) = when {
            a.minValue < b.minValue -> a.minValue to a.minAt
            b.minValue < a.minValue -> b.minValue to b.minAt
            else                    -> a.minValue to minOf(a.minAt, b.minAt)
        }
        val (maxValue, maxAt) = when {
            a.maxValue > b.maxValue -> a.maxValue to a.maxAt
            b.maxValue > a.maxValue -> b.maxValue to b.maxAt
            else                    -> a.maxValue to minOf(a.maxAt, b.maxAt)
        }

        return SignalBucketAccumulator.Snapshot(
            signalId    = a.signalId,
            ownerId     = a.ownerId,
            bucketAt    = a.bucketAt,
            avgValue    = avg,
            minValue    = minValue,
            minAt       = minAt,
            maxValue    = maxValue,
            maxAt       = maxAt,
            sampleCount = count
        )
    }
}
