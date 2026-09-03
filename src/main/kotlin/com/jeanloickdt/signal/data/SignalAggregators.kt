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
 * Miroir de [com.jeanloickdt.widget.data.HistoryAggregators], pour le modèle
 * signal — un singleton accessible partout, dans le même style que
 * l'existant, plutôt qu'un objet à faire voyager dans chaque signature.
 *
 * Un seul palier, pas trois : voir [SignalMinuteAggregator].
 */
object SignalAggregators {
    val minute = SignalMinuteAggregator(bucketSizeMs = 60_000L)
}
