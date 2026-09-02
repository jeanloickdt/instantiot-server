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

import java.util.concurrent.ConcurrentHashMap

/**
 * L'accumulateur RAM de la minute — un seul palier, pas trois.
 *
 * `HistoryAggregators` (le modèle widget) tient minute, heure et jour en
 * parallèle. Ici, seulement minute : dans le modèle cible, heure et jour ne
 * sont plus accumulés échantillon par échantillon — ils sont **dérivés** par
 * un travail périodique qui lit les seaux minute fermés (§3 du brief). Tant
 * que ce travail n'existe pas, `signal_hour`/`signal_day` restent vides —
 * assumé, pas un oubli.
 *
 * Le reste est la même mécanique que [TierAggregator][com.jeanloickdt.widget.data.TierAggregator],
 * simplifiée : pas de `seriesId` (mort avec `TYPE_CHART`), pas d'`ownerId`
 * dans la clé du seau (`signalId` est déjà global et scope-par-compte).
 */
class SignalMinuteAggregator(val bucketSizeMs: Long = 60_000L) {

    private data class BucketKey(val signalId: Long, val bucketAt: Long)

    private val buckets = ConcurrentHashMap<BucketKey, SignalBucketAccumulator>()

    fun collect(signalId: Long, ownerId: String, ts: Long, value: Double) {
        val bucketAt = (ts / bucketSizeMs) * bucketSizeMs
        val key = BucketKey(signalId, bucketAt)
        val bucket = buckets.computeIfAbsent(key) {
            SignalBucketAccumulator(signalId, ownerId, bucketAt)
        }
        bucket.addSample(value, ts)
    }

    /** Les seaux dont la fenêtre est close à `now` — retirés de la map. */
    fun extractClosedBuckets(now: Long): List<SignalBucketAccumulator.Snapshot> {
        val closed = mutableListOf<SignalBucketAccumulator.Snapshot>()
        val toRemove = mutableListOf<BucketKey>()
        for ((key, bucket) in buckets) {
            if (key.bucketAt + bucketSizeMs <= now) {
                closed += bucket.snapshot()
                toRemove += key
            }
        }
        for (key in toRemove) buckets.remove(key)
        return closed
    }

    /** Tous les seaux, y compris le courant — pour un arrêt propre. */
    fun extractAllBuckets(): List<SignalBucketAccumulator.Snapshot> {
        val all = mutableListOf<SignalBucketAccumulator.Snapshot>()
        val keys = ArrayList(buckets.keys)
        for (key in keys) buckets.remove(key)?.let { all += it.snapshot() }
        return all
    }

    fun size(): Int = buckets.size
}
