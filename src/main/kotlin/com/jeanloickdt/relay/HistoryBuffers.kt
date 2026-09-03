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

// relay/HistoryBuffers.kt
package com.jeanloickdt.relay

/**
 * RAM staging area of the ingest pipeline: what the device read-loop writes
 * per frame, and what the 5s flush job drains into the database. The read path
 * never touches the DB — this queue is the decoupling.
 *
 * Il n'en reste qu'une. Le modèle widget en tenait trois : un palier opaque
 * (`widget_history`), un palier brut (`widget_history_numeric`) et un cache
 * `knownWidgetIds` pour l'auto-enregistrement. Les deux premiers n'ont plus de
 * table, et le troisième plus de raison d'être : un signal se déclare, il ne
 * s'invente pas à la première trame.
 */
class HistoryBuffers(
    /**
     * What the writer sustains, in rows per second.
     *
     * The default is deliberately modest — a floor any machine holds, and a
     * ceiling that protects a small heap. The composition root raises it.
     */
    drainRatePerSecond: Int = 2_000,
    flushPeriodMs: Long = 5_000L
) {
    private val capacity = BoundedIngestQueue.capacityFor(drainRatePerSecond, flushPeriodMs)

    /**
     * Le palier brut, seconde par seconde. Écrit uniquement quand l'opérateur
     * a activé RAW *et* que le plan l'accorde — donc normalement vide.
     */
    val signalRawBuffer =
        BoundedIngestQueue<com.jeanloickdt.signal.data.SignalRawEntry>("signal-raw", capacity)

    /**
     * Qui souffre quand l'ecrivain ne suit plus — voir [IngestBackPressure].
     *
     * Elle vit ici, avec la file qu'elle protege : l'objet qui detient le
     * tampon detient sa politique de pression, et aucun appelant n'a de
     * parametre supplementaire a faire passer.
     */
    val backPressure = IngestBackPressure()

    /**
     * Toute entrée que la file a refusée depuis le démarrage.
     *
     * Lue par la boucle de vidage, seule à pouvoir le dire à voix haute : une
     * saturation que personne ne signale est pire qu'un plafond bas.
     */
    fun refusedTotal(): Long = signalRawBuffer.refusedCount + backPressure.droppedRaw

    /** Pour la ligne de log du vidage — l'état de la pression, pas juste le débit. */
    fun pressure(): String =
        "$signalRawBuffer" + if (backPressure.isRawSuspended) " raw=SUSPENDU" else ""
}
