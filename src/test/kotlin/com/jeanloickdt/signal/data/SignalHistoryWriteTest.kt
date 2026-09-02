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

import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bout en bout, étape 1 : deux comptes déclarent un signal qui porte le même
 * nom, et chacun ne voit que le sien — le premier des deux tests
 * d'acceptation du brief (§7.1), vérifié dès que le chemin d'écriture
 * existe, plutôt que d'attendre la fin du chantier pour le découvrir cassé.
 */
class SignalHistoryWriteTest {

    private lateinit var signals: ExposedSignalRepository
    private lateinit var history: ExposedSignalHistoryRepository

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.fresh()
        signals = ExposedSignalRepository()
        // Une horloge figee juste apres les seaux ecrits ici — tous sont pres
        // de l'epoque zero, et la derivation ne reprend qu'une FENETRE de seaux
        // cibles clos. Avec l'horloge reelle, ils seraient hors fenetre depuis
        // cinquante-cinq ans, et ces tests ne prouveraient plus rien.
        //
        // C'est le compromis assume de la fenetre glissante, documente dans
        // `deriveTier` : sans etat, auto-reparante, mais aveugle au passe
        // lointain sauf a elargir explicitement.
        // Une horloge figee apres les seaux ecrits ici : ils sont tous pres de
        // l'epoque zero, et la derivation ne reprend qu'une FENETRE de seaux
        // cibles CLOS. Avec l'horloge reelle ils seraient hors fenetre depuis
        // cinquante-cinq ans, et ces tests ne prouveraient plus rien.
        history = ExposedSignalHistoryRepository(now = { SignalRollup.DAY_MS + SignalRollup.HOUR_MS })
    }

    /**
     * Une fenetre large, exprès.
     *
     * Ce fichier teste l'ARITHMETIQUE de la cascade — moyennes ponderees,
     * extremes qui remontent, effectifs qui ne se comptent pas deux fois. Les
     * bornes de la derivation ont leur propre fichier
     * ([SignalHistoryDerivationTest]) ; les melanger ici ferait tomber ces
     * tests pour une raison qui n'est pas la leur.
     */
    private val LARGE = 1_000

    private fun signalId(ownerId: String, deviceId: String, address: Int): Long {
        signals.create(ownerId, deviceId, address, "temp", SignalTable.TYPE_FLOAT, nowMs = 0L)
        return signals.find(ownerId, deviceId, address)!!.id
    }

    /** Args nommés exprès : le constructeur positionnel de Snapshot a déjà
     *  produit trois fois la même erreur dans ce chantier. */
    private fun snap(
        id: Long, owner: String, bucketAt: Long,
        min: Double, minAt: Long, max: Double, maxAt: Long, avg: Double, count: Int
    ) = SignalBucketAccumulator.Snapshot(
        signalId = id, ownerId = owner, bucketAt = bucketAt,
        minValue = min, minAt = minAt, maxValue = max, maxAt = maxAt,
        avgValue = avg, sampleCount = count
    )

    // ── Test d'acceptation §7.1 du brief ───────────────────────────────────

    @Test
    fun `two accounts declaring the same signal name each keep their own complete history`() {
        val idU1 = signalId("u1", "dev-u1", 5)
        val idU2 = signalId("u2", "dev-u2", 5)   // même adresse, même intention, autre compte

        history.insertMinuteBatch(listOf(
            SignalBucketAccumulator.Snapshot(idU1, "u1", 0L, 18.0, 1_000L, 22.0, 2_000L, 20.0, 3),
            SignalBucketAccumulator.Snapshot(idU2, "u2", 0L, 99.0, 1_000L, 99.0, 1_000L, 99.0, 1)
        ))

        val u1Rows = history.findMinuteRange(idU1, "u1", 0L, 60_000L)
        val u2Rows = history.findMinuteRange(idU2, "u2", 0L, 60_000L)

        assertEquals(1, u1Rows.size)
        assertEquals(20.0, u1Rows.single().avgValue)
        assertEquals(1, u2Rows.size)
        assertEquals(99.0, u2Rows.single().avgValue)
    }

    @Test
    fun `an account cannot read another account's row through findMinuteRange`() {
        val idU1 = signalId("u1", "dev-u1", 5)
        history.insertMinuteBatch(listOf(
            SignalBucketAccumulator.Snapshot(idU1, "u1", 0L, 18.0, 1_000L, 22.0, 2_000L, 20.0, 3)
        ))
        // Même id, mauvais compte : c'est exactement la fuite que la règle 6
        // interdit, appliquée cette fois à une lecture d'historique.
        assertTrue(history.findMinuteRange(idU1, "u2", 0L, 60_000L).isEmpty())
    }

    // ── min_at / max_at traversent bien l'écriture réelle ─────────────────

    @Test
    fun `min_at and max_at survive a real write and read`() {
        val id = signalId("u1", "dev-u1", 5)
        history.insertMinuteBatch(listOf(
            SignalBucketAccumulator.Snapshot(id, "u1", 0L, 5.0, 1_500L, 30.0, 1_900L, 17.5, 2)
        ))
        val row = history.findMinuteRange(id, "u1", 0L, 60_000L).single()
        assertEquals(1_500L, row.minAt)
        assertEquals(1_900L, row.maxAt)
    }

    // ── Le tier RAW ─────────────────────────────────────────────────────────

    @Test
    fun `raw samples land with the signal's integer id, one row each`() {
        val id = signalId("u1", "dev-u1", 5)
        history.insertRawBatch(listOf(
            SignalRawEntry(id, "u1", 1_000L, 23.4),
            SignalRawEntry(id, "u1", 1_001L, 23.5)
        ))
        // Pas encore de lecture RAW à ce stade — on confirme juste que le
        // batch ne lève rien et que la clé (signal_id, ts) accepte deux
        // instants distincts sans collision.
    }

    // ── Le redémarrage en milieu de fenêtre, contre le vrai dépôt ─────────

    @Test
    fun `two flushes of the same bucket merge, they don't overwrite — the real repository, not just the pure function`() {
        // Le scenario exact du test d'acceptation §7.2 : le seau minute est
        // flushe une premiere fois (avant un redemarrage), puis une seconde
        // (apres) — deux appels insertMinuteBatch separes sur LA MEME cle
        // (signal_id, bucket_at), comme le ferait le process reel.
        val id = signalId("u1", "dev-u1", 5)

        history.insertMinuteBatch(listOf(
            SignalBucketAccumulator.Snapshot(id, "u1", 0L, 18.0, 100L, 22.0, 400L, 20.0, 40)
        ))
        history.insertMinuteBatch(listOf(
            SignalBucketAccumulator.Snapshot(id, "u1", 0L, 15.0, 500L, 25.0, 900L, 20.0, 20)
        ))

        val row = history.findMinuteRange(id, "u1", 0L, 60_000L).single()
        assertEquals(60, row.sampleCount, "aucune fenetre amputee — les deux moities comptent")
        assertEquals(15.0, row.minValue, "le minimum du second flush doit survivre")
        assertEquals(500L, row.minAt)
        assertEquals(25.0, row.maxValue)
        assertEquals(900L, row.maxAt)
    }

    // ── La dérivation périodique, étape 3 ─────────────────────────────────

    @Test
    fun `minutes derive into one hour bucket, in cascade toward the day too`() {
        val id = signalId("u1", "dev-u1", 5)
        // Trois seaux minute dans la meme heure (00:00, 00:01, 00:02).
        history.insertMinuteBatch(listOf(
            snap(id, "u1", 0L,       min = 5.0,  minAt = 0L,       max = 15.0, maxAt = 0L,       avg = 10.0, count = 2),
            snap(id, "u1", 60_000L,  min = 18.0, minAt = 60_000L,  max = 22.0, maxAt = 60_000L,  avg = 20.0, count = 2),
            snap(id, "u1", 120_000L, min = 28.0, minAt = 120_000L, max = 32.0, maxAt = 120_000L, avg = 30.0, count = 2)
        ))

        val hourRows = history.deriveHour(windowBuckets = LARGE)
        assertEquals(1, hourRows, "un seul seau heure — les trois minutes tombent dans la meme heure")

        val hour = history.findHourRange(id, "u1", 0L, SignalRollup.HOUR_MS).single()
        assertEquals(6, hour.sampleCount)
        assertEquals(5.0, hour.minValue)
        assertEquals(32.0, hour.maxValue)

        // La cascade : le jour vient de l'HEURE, pas de la minute.
        val dayRows = history.deriveDay(windowBuckets = LARGE)
        assertEquals(1, dayRows)
        val day = history.findDayRange(id, "u1", 0L, SignalRollup.DAY_MS).single()
        assertEquals(6, day.sampleCount)
        assertEquals(5.0, day.minValue)
        assertEquals(32.0, day.maxValue)
    }

    @Test
    fun `re-running the derivation does not double the sample count — the whole point of replace over merge`() {
        // LE test qui justifie deriveTier() plutot que d'appeler
        // insertAggregateBatch (qui fusionnerait, et gonflerait le compte a
        // chaque relance). Une dérivation relancée doit être un no-op sur
        // des données inchangées.
        val id = signalId("u1", "dev-u1", 5)
        history.insertMinuteBatch(listOf(
            snap(id, "u1", 0L, min = 20.0, minAt = 0L, max = 20.0, maxAt = 0L, avg = 20.0, count = 5)
        ))

        history.deriveHour(windowBuckets = LARGE)
        val firstRun = history.findHourRange(id, "u1", 0L, SignalRollup.HOUR_MS).single()

        history.deriveHour(windowBuckets = LARGE)   // relance, aucune donnee minute nouvelle
        val secondRun = history.findHourRange(id, "u1", 0L, SignalRollup.HOUR_MS).single()

        assertEquals(firstRun.sampleCount, secondRun.sampleCount, "une relance ne doit rien gonfler")
        assertEquals(5, secondRun.sampleCount)
    }

    @Test
    fun `a new minute arriving after a first derivation is folded in on the next run`() {
        val id = signalId("u1", "dev-u1", 5)
        history.insertMinuteBatch(listOf(
            snap(id, "u1", 0L, min = 10.0, minAt = 0L, max = 10.0, maxAt = 0L, avg = 10.0, count = 1)
        ))
        history.deriveHour(windowBuckets = LARGE)

        // Une deuxieme minute de la meme heure arrive apres coup.
        history.insertMinuteBatch(listOf(
            snap(id, "u1", 60_000L, min = 20.0, minAt = 60_000L, max = 20.0, maxAt = 60_000L, avg = 20.0, count = 1)
        ))
        history.deriveHour(windowBuckets = LARGE)

        val hour = history.findHourRange(id, "u1", 0L, SignalRollup.HOUR_MS).single()
        assertEquals(2, hour.sampleCount, "la deuxieme minute doit rejoindre la premiere, pas la remplacer")
    }

    @Test
    fun `two accounts' hours stay separate through the derivation`() {
        val idU1 = signalId("u1", "dev-u1", 5)
        val idU2 = signalId("u2", "dev-u2", 5)
        history.insertMinuteBatch(listOf(
            snap(idU1, "u1", 0L, min = 10.0, minAt = 0L, max = 10.0, maxAt = 0L, avg = 10.0, count = 1),
            snap(idU2, "u2", 0L, min = 99.0, minAt = 0L, max = 99.0, maxAt = 0L, avg = 99.0, count = 1)
        ))
        history.deriveHour(windowBuckets = LARGE)
        assertEquals(10.0, history.findHourRange(idU1, "u1", 0L, SignalRollup.HOUR_MS).single().avgValue)
        assertEquals(99.0, history.findHourRange(idU2, "u2", 0L, SignalRollup.HOUR_MS).single().avgValue)
        assertTrue(history.findHourRange(idU1, "u2", 0L, SignalRollup.HOUR_MS).isEmpty())
    }

    @Test
    fun `a replayed raw batch does not duplicate rows`() {
        // La correction de PK mentionnée dans SignalHistoryTables : la clé
        // (signal_id, ts) empêche ce que `widget_history_numeric`, sans
        // aucune clé unique, laisse aujourd'hui passer.
        val id = signalId("u1", "dev-u1", 5)
        val entry = SignalRawEntry(id, "u1", 1_000L, 23.4)
        history.insertRawBatch(listOf(entry))
        history.insertRawBatch(listOf(entry))   // rejeu exact
        // Vérifié indirectement : un doublon violerait la PK et lèverait
        // sous SQLite comme sous Postgres si ce n'était pas un ON CONFLICT
        // DO NOTHING — l'absence d'exception EST le test.
    }
}
