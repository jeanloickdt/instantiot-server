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

// relay/HistoryFlusher.kt
package com.jeanloickdt.relay

import com.jeanloickdt.signal.data.SignalBucketAccumulator
import com.jeanloickdt.signal.data.SignalMinuteAggregator
import com.jeanloickdt.signal.data.SignalRawEntry
import org.slf4j.LoggerFactory

/**
 * Un tour de vidage de l'historique, et ce qui se passe quand la base ne
 * repond pas.
 *
 * ## Le defaut que ceci ferme
 *
 * La boucle ecrivait ainsi :
 *
 * ```
 * rawRows = buffers.signalRawBuffer.drain()
 *     .also { repository.insertRawBatch(it) }.size
 * ```
 *
 * `drain()` vide la file AVANT que l'ecriture ne parte. Si elle echoue, les
 * lignes tenues en memoire n'existent plus nulle part : le `catch` les
 * journalise, et le tour suivant repart d'une file vide. Meme chose pour les
 * seaux minute, qu'`extractClosedBuckets` retirait de l'agregateur juste
 * avant de tenter l'ecriture.
 *
 * Ce n'est pas theorique sur une machine chez soi. Une carte SD pleine, une
 * erreur d'entree-sortie, un `SQLITE_BUSY` pendant une restauration : chaque
 * cas coute cinq secondes d'historique, en silence. La sauvegarde, elle, est
 * hors de cause — `VACUUM INTO` ne bloque pas les ecritures en cours.
 *
 * ## Ce qui se passe maintenant
 *
 * Le lot retourne EN TETE de sa file, plus ancien que ce qui est entre
 * pendant l'essai, et le tour suivant reessaie avec un delai qui double
 * jusqu'a [MAX_BACKOFF_MS]. La borne des files s'applique quand meme : si la
 * base reste injoignable, ce qui deborde part et **il est compte**. Mieux
 * vaut perdre des lignes anciennes que tout perdre avec le processus.
 *
 * L'ingestion, elle, ne s'arrete jamais. Les cartes continuent d'etre lues,
 * les seaux continuent de s'accumuler, et [BoundedIngestQueue.offer] ne
 * suspend personne.
 *
 * ## Pourquoi une classe, et pas trois lignes de plus dans la boucle
 *
 * Parce que le comportement qui compte est celui de l'ECHEC, et qu'un echec
 * de base ne se provoque pas depuis une boucle de fond. Ici, [write] est un
 * parametre : un test lui fait lever une exception, verifie que le lot est
 * revenu, et que le delai a double. C'est la seule facon d'eprouver la
 * garantie plutot que de l'esperer.
 */
class HistoryFlusher(
    private val buffers: HistoryBuffers,
    private val minutes: SignalMinuteAggregator,
    private val write: (raw: List<SignalRawEntry>, minutes: List<SignalBucketAccumulator.Snapshot>) -> Unit,
    private val periodMs: Long,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: org.slf4j.Logger = LoggerFactory.getLogger("HistoryFlusher")
) {

    sealed interface Outcome {
        /** Ce qui est parti en base. */
        data class Written(val rawRows: Int, val minuteRows: Int) : Outcome

        /** Ce qui est reste dans les files, en attente du prochain essai. */
        data class Failed(
            val cause: Throwable,
            val keptRaw: Int,
            val keptMinutes: Int
        ) : Outcome
    }

    @Volatile
    private var consecutiveFailures = 0

    /** Combien d'echecs d'affilee. Zero des qu'une ecriture passe. */
    val failures: Int get() = consecutiveFailures

    /**
     * Le delai avant le prochain tour : la periode en temps normal, un delai
     * croissant tant que la base refuse.
     *
     * Le decalage est borne a vingt pour que `shl` ne deborde pas — bien
     * au-dela de [maxBackoffMs], qui plafonne de toute facon.
     */
    val nextDelayMs: Long
        get() = if (consecutiveFailures == 0) periodMs
        else minOf(maxBackoffMs, periodMs shl (consecutiveFailures - 1).coerceAtMost(20))

    /**
     * Un tour : les seaux fermes rejoignent leur file, puis tout part.
     *
     * L'extraction passe par la file meme quand l'ecriture va reussir. Sans
     * ce detour, un echec laisserait les seaux dans une variable locale, et
     * c'est exactement le defaut qu'on ferme.
     */
    fun flushOnce(): Outcome {
        for (seau in minutes.extractClosedBuckets(clock())) {
            buffers.minutePending.offer(seau)
        }
        return attempt(buffers.signalRawBuffer.drain(), buffers.minutePending.drain())
    }

    /**
     * L'arret propre : tout, y compris le seau minute EN COURS, qui part
     * comme un delta partiel.
     *
     * Un echec ici ne se reessaie pas — le processus s'en va. Il est dit.
     */
    fun flushAll(): Outcome {
        for (seau in minutes.extractAllBuckets()) {
            buffers.minutePending.offer(seau)
        }
        return attempt(buffers.signalRawBuffer.drain(), buffers.minutePending.drain())
    }

    private fun attempt(
        raw: List<SignalRawEntry>,
        deltas: List<SignalBucketAccumulator.Snapshot>
    ): Outcome = try {
        write(raw, deltas)
        if (consecutiveFailures > 0) {
            log.info(
                "Base de retour — ${raw.size} ligne(s) brute(s) et ${deltas.size} seau(x) minute " +
                    "ecrits apres $consecutiveFailures echec(s)."
            )
        }
        consecutiveFailures = 0
        Outcome.Written(raw.size, deltas.size)
    } catch (e: Exception) {
        consecutiveFailures++
        // Le lot retourne en tete — plus ancien que ce qui est entre pendant
        // l'essai. La borne decide de ce qui survit, et le dit.
        buffers.signalRawBuffer.restore(raw)
        buffers.minutePending.restore(deltas)
        log.error(
            "Ecriture de l'historique ECHOUEE ($consecutiveFailures d'affilee) — lot garde " +
                "(${buffers.signalRawBuffer}, ${buffers.minutePending}), " +
                "reessai dans ${nextDelayMs / 1000} s. L'ingestion continue ; au-dela de la " +
                "borne, le plus ancien est jete et compte.",
            e
        )
        Outcome.Failed(e, buffers.signalRawBuffer.size, buffers.minutePending.size)
    }

    companion object {
        /** Une minute : au-dela, reessayer plus rarement n'aide plus personne. */
        const val MAX_BACKOFF_MS: Long = 60_000L
    }
}
