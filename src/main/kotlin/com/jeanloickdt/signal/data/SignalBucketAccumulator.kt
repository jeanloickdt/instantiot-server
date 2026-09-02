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
 * Un seau d'une minute, en RAM, pour un signal.
 *
 * ## Ce qui diffère de `BucketAccumulator` (le modèle widget)
 *
 * Pas de `seriesId` : un signal ne porte qu'une valeur, la notion appartient
 * à l'ancien modèle (`TYPE_CHART`). Pas d'`ownerId` dans la clé du seau non
 * plus — `signalId` est déjà global et scope-par-compte via sa clé étrangère
 * ; le répéter ici serait la même redondance que celle retirée de
 * `signals.id` à l'étape 0.
 *
 * Et surtout : **`min_at`/`max_at`**, que l'ancien accumulateur n'avait pas.
 * Voir le document du modèle cible pour pourquoi — en une phrase, une
 * courbe en résolution jour peut dire *« minimum de l'année : 12,3°C le 3
 * janvier à 04:17:23 »* même quand les lignes minute de ce jour-là sont
 * purgées depuis longtemps, parce que l'instant remonte inchangé à travers
 * la cascade au lieu de se perdre à chaque montée de palier.
 */
class SignalBucketAccumulator(
    val signalId: Long,
    val ownerId: String,
    val bucketAt: Long
) {
    private var _minValue: Double = Double.POSITIVE_INFINITY
    private var _minAt: Long = 0L
    private var _maxValue: Double = Double.NEGATIVE_INFINITY
    private var _maxAt: Long = 0L
    private var _sumValue: Double = 0.0
    private var _sampleCount: Int = 0

    /**
     * @param atMs l'instant de CET échantillon — pas celui du seau. C'est ce
     *             qui permet à `min_at`/`max_at` de pointer sur la
     *             milliseconde exacte plutôt que sur le début de la minute.
     */
    fun addSample(value: Double, atMs: Long) {
        // Même garde que BucketAccumulator, même raison : un NaN comparé à
        // n'importe quoi rend toujours faux, donc un seul échantillon
        // non-fini empoisonnerait min/max pour le reste de la vie du seau.
        if (!value.isFinite()) return
        synchronized(this) {
            if (value < _minValue) { _minValue = value; _minAt = atMs }
            if (value > _maxValue) { _maxValue = value; _maxAt = atMs }
            _sumValue += value
            _sampleCount++
        }
    }

    fun snapshot(): Snapshot = synchronized(this) {
        Snapshot(
            signalId    = signalId,
            ownerId     = ownerId,
            bucketAt    = bucketAt,
            minValue    = _minValue,
            minAt       = _minAt,
            maxValue    = _maxValue,
            maxAt       = _maxAt,
            avgValue    = if (_sampleCount > 0) _sumValue / _sampleCount else 0.0,
            sampleCount = _sampleCount
        )
    }

    data class Snapshot(
        val signalId: Long,
        val ownerId: String,
        val bucketAt: Long,
        val minValue: Double,
        val minAt: Long,
        val maxValue: Double,
        val maxAt: Long,
        val avgValue: Double,
        val sampleCount: Int
    )
}
