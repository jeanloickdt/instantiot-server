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
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.StatementContext
import org.jetbrains.exposed.sql.statements.StatementInterceptor
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La dérivation, ses bornes, et ce que coûtent les écritures.
 *
 * ## Ce que ce fichier garde
 *
 * **La borne haute.** Un seau cible EN COURS n'est pas fermé : ses sources
 * sont incomplètes. Le dériver écrirait une heure fausse — et la
 * remplacerait à chaque passage, donc l'app verrait une valeur qui saute
 * jusqu'à la fin de l'heure.
 *
 * **La borne basse.** Sans elle, la dérivation relisait TOUTE la table source
 * à chaque appel : quelques millions de lignes minute chargées dans une liste
 * Kotlin, groupées en mémoire, réécrites en entier. Ce n'était pas une
 * optimisation en attente, c'était un travail qui finit par ne plus s'exécuter
 * du tout — et comme la purge doit tourner APRÈS la dérivation, une panne en
 * cachait une seconde : le disque grossissait sans limite pendant que rien ne
 * signalait rien.
 *
 * **Le coût en allers-retours.** Le chemin le plus chaud du serveur — toutes
 * les minutes se ferment à la seconde 00 — faisait un SELECT puis un
 * INSERT/UPDATE par seau. Ce fichier compte les instructions plutôt que de
 * faire confiance à la lecture du code.
 */
class SignalHistoryDerivationTest {

    private lateinit var signals: ExposedSignalRepository
    private lateinit var history: ExposedSignalHistoryRepository

    /** Une horloge figée : sans elle, « le seau en cours » n'est pas testable. */
    private var clock = 0L

    private val minute = 60_000L
    private val hour = 3_600_000L

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.fresh()
        signals = ExposedSignalRepository()
        history = ExposedSignalHistoryRepository(now = { clock })
        // Un instant bien après l'époque, aligné sur une heure pile : les
        // seaux calculés sont alors lisibles à l'œil dans les messages d'échec.
        clock = 1_700_000_000_000L / hour * hour
    }

    private fun signalId(owner: String, device: String, address: Int): Long {
        signals.create(owner, device, address, "temp", SignalTable.TYPE_FLOAT, nowMs = 0L)
        return signals.find(owner, device, address)!!.id
    }

    private fun snap(id: Long, owner: String, bucketAt: Long, avg: Double, count: Int) =
        SignalBucketAccumulator.Snapshot(
            signalId = id, ownerId = owner, bucketAt = bucketAt,
            minValue = avg, minAt = bucketAt, maxValue = avg, maxAt = bucketAt,
            avgValue = avg, sampleCount = count
        )

    // ── La borne haute : le seau en cours ─────────────────────────────────

    @Test
    fun `the target bucket still in progress is never derived`() {
        // LE test que le défaut rend nécessaire. L'heure courante n'est pas
        // finie : dériver maintenant écrirait une moyenne calculée sur les
        // quelques minutes déjà arrivées, et la présenterait comme l'heure
        // entière.
        val id = signalId("u1", "dev", 1)
        val heureEnCours = clock                       // clock est aligné sur l'heure
        history.insertMinuteBatch(listOf(
            snap(id, "u1", heureEnCours, avg = 10.0, count = 60),
            snap(id, "u1", heureEnCours + minute, avg = 20.0, count = 60),
        ))
        clock = heureEnCours + 2 * minute              // on est à H+2min

        history.deriveHour()

        val heures = history.findHourRange(id, "u1", 0, Long.MAX_VALUE)
        assertTrue(
            heures.isEmpty(),
            "une heure non close ne doit pas être dérivée — trouvé : ${heures.map { it.bucketAt to it.sampleCount }}"
        )
    }

    @Test
    fun `a closed target bucket IS derived`() {
        // Le pendant : une borne qui ne dérive plus rien serait aussi fausse.
        val id = signalId("u1", "dev", 1)
        val heurePrecedente = clock - hour
        history.insertMinuteBatch(listOf(
            snap(id, "u1", heurePrecedente, avg = 10.0, count = 60),
            snap(id, "u1", heurePrecedente + minute, avg = 20.0, count = 60),
        ))

        history.deriveHour()

        val heures = history.findHourRange(id, "u1", 0, Long.MAX_VALUE)
        assertEquals(1, heures.size, "l'heure close doit être dérivée")
        assertEquals(heurePrecedente, heures.single().bucketAt)
        assertEquals(120, heures.single().sampleCount)
        assertEquals(15.0, heures.single().avgValue, 0.001, "moyenne pondérée des deux minutes")
    }

    // ── Relancer ne doit rien gonfler ─────────────────────────────────────

    @Test
    fun `running the derivation twice does not inflate the sample count`() {
        // La propriété qui rend la fenêtre glissante viable : la dérivation
        // REMPLACE la ligne cible au lieu de la fusionner. Fusionner
        // compterait deux fois les mêmes échantillons à chaque relance, et le
        // `sample_count` de l'heure gonflerait sans fin — un compteur qui ment
        // de plus en plus, sans qu'aucune erreur ne se produise.
        val id = signalId("u1", "dev", 1)
        val heurePrecedente = clock - hour
        history.insertMinuteBatch(listOf(
            snap(id, "u1", heurePrecedente, avg = 10.0, count = 60),
            snap(id, "u1", heurePrecedente + minute, avg = 20.0, count = 60),
        ))

        history.deriveHour()
        history.deriveHour()
        history.deriveHour()

        val heure = history.findHourRange(id, "u1", 0, Long.MAX_VALUE).single()
        assertEquals(120, heure.sampleCount, "trois passages, un seul effectif")
        assertEquals(15.0, heure.avgValue, 0.001)
    }

    // ── La borne basse, et son compromis assumé ───────────────────────────

    @Test
    fun `a bucket older than the window is left alone by default, and caught up on demand`() {
        // La fenêtre glissante est sans état, et c'est ce qui la rend simple.
        // Son prix : une panne plus longue que la fenêtre laisserait un trou.
        //
        // Ce test dit les deux moitiés — ce que le passage courant ignore, et
        // comment on va le chercher quand on en a besoin. Sans la seconde,
        // le compromis serait un défaut.
        val id = signalId("u1", "dev", 1)
        val vieilleHeure = clock - 100 * hour
        history.insertMinuteBatch(listOf(snap(id, "u1", vieilleHeure, avg = 42.0, count = 60)))

        history.deriveHour()   // fenêtre par défaut
        assertTrue(
            history.findHourRange(id, "u1", 0, Long.MAX_VALUE).isEmpty(),
            "hors fenêtre : le passage courant ne la voit pas"
        )

        history.deriveHour(windowBuckets = 200)
        val rattrapee = history.findHourRange(id, "u1", 0, Long.MAX_VALUE).single()
        assertEquals(vieilleHeure, rattrapee.bucketAt, "élargir la fenêtre rattrape, sans migration")
        assertEquals(60, rattrapee.sampleCount)
    }

    // ── Le coût en allers-retours ─────────────────────────────────────────

    /**
     * Compte les ALLERS-RETOURS vers la base dans un bloc.
     *
     * ## Le piège de l'instrument, et pourquoi c'est `afterStatementPrepared`
     *
     * `beforeExecution` se déclenche une fois par **jeu d'arguments**, donc une
     * fois par LIGNE d'un lot. Un `batchInsert` de cinquante lignes le
     * déclenche cinquante fois alors qu'Exposed ne fait qu'un seul aller-retour
     * — `prepared()` une fois, `addBatch()` par ligne, un `executeInternal`.
     *
     * Compter là aurait fait tomber le test sur du code correct, et m'aurait
     * poussé à « corriger » ce qui marchait. `afterStatementPrepared` se
     * déclenche une fois par instruction préparée : c'est la grandeur qui
     * compte.
     *
     * ## Ce que l'instrument ne voit pas
     *
     * Les écritures en JDBC brut (`insertRawBatch`, `updateAggregateRows`)
     * court-circuitent la couche d'Exposed : elles sont invisibles ici. Elles
     * sont un `addBatch`/`executeBatch` par construction, donc un aller-retour
     * chacune — mais ce test ne le prouve pas, il le suppose. Ce qu'il prouve,
     * c'est qu'aucune boucle Exposed par seau n'est revenue.
     */
    private fun countRoundTrips(block: () -> Unit): Int {
        var n = 0
        val counter = object : StatementInterceptor {
            override fun afterStatementPrepared(
                transaction: Transaction,
                preparedStatement: org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
            ) {
                n++
            }
        }
        transaction {
            registerInterceptor(counter)
            block()
        }
        return n
    }

    @Test
    fun `writing a batch of new buckets does not cost one round trip per bucket`() {
        // Le chemin le plus chaud du serveur : toutes les minutes se ferment à
        // la seconde 00, donc ce lot est aussi large qu'il y a de signaux
        // actifs. Un SELECT puis un INSERT par seau, c'est 2N instructions
        // dans une seule transaction, toutes les minutes.
        val id = signalId("u1", "dev", 1)
        val base = clock - hour
        val lot = (0 until 50).map { snap(id, "u1", base + it * minute, avg = it.toDouble(), count = 60) }

        val instructions = countRoundTrips { history.insertMinuteBatch(lot) }

        assertTrue(
            instructions <= 10,
            "50 seaux ne doivent pas coûter un aller-retour chacun — mesuré : $instructions allers-retours"
        )
        assertEquals(50, history.findMinuteRange(id, "u1", 0, Long.MAX_VALUE).size)
    }

    @Test
    fun `merging a batch onto existing buckets stays correct while batching`() {
        // Le lot devient un mélange : la moitié existe déjà, l'autre non. La
        // correction de l'arithmétique ne doit pas être payée par le
        // regroupement — c'est tout l'intérêt de garder [SignalMerge] seul
        // responsable du calcul.
        val id = signalId("u1", "dev", 1)
        val base = clock - hour
        history.insertMinuteBatch((0 until 10).map { snap(id, "u1", base + it * minute, avg = 10.0, count = 30) })

        val instructions = countRoundTrips {
            history.insertMinuteBatch((0 until 20).map { snap(id, "u1", base + it * minute, avg = 20.0, count = 30) })
        }

        assertTrue(
            instructions <= 10,
            "un lot moitié neuf moitié fusionné ne doit pas coûter un aller-retour par seau — mesuré : $instructions"
        )
        val lignes = history.findMinuteRange(id, "u1", 0, Long.MAX_VALUE).sortedBy { it.bucketAt }
        assertEquals(20, lignes.size)
        // Les dix premiers ont fusionné : 30 + 30 échantillons, moyenne pondérée.
        assertEquals(60, lignes.first().sampleCount, "la fusion compte les deux moitiés")
        assertEquals(15.0, lignes.first().avgValue, 0.001, "moyenne pondérée, pas moyenne de moyennes")
        // Les dix suivants sont neufs.
        assertEquals(30, lignes.last().sampleCount)
        assertEquals(20.0, lignes.last().avgValue, 0.001)
    }

    // ── Les lectures sont plafonnées DANS la requête ──────────────────────

    @Test
    fun `a raw range does not materialise more rows than the cap`() {
        // Le plafond existait déjà — mais dans la route, qui coupait après
        // coup. Les 86 400 lignes d'une journée à 1 Hz étaient lues, converties
        // en 86 400 objets, puis 5 000 survivaient. On économisait la
        // sérialisation, pas la lecture : la moitié chère.
        val id = signalId("u1", "dev", 1)
        val cap = com.jeanloickdt.signal.SignalHistoryQuery.MAX_ROWS
        history.insertRawBatch((0 until cap + 500).map {
            SignalRawEntry(signalId = id, ownerId = "u1", ts = it.toLong(), value = it.toDouble())
        })

        val lus = history.findRawRange(id, "u1", 0, Long.MAX_VALUE)

        assertTrue(
            lus.size <= cap + 1,
            "la requête doit porter le plafond — lu : ${lus.size} lignes pour un plafond de $cap"
        )
        assertTrue(
            lus.size > cap,
            "il en faut UNE de plus que le plafond, sinon la route ne peut plus dire qu'elle a tronqué"
        )
    }

    @Test
    fun `an aggregate range is capped too`() {
        val id = signalId("u1", "dev", 1)
        val cap = com.jeanloickdt.signal.SignalHistoryQuery.MAX_ROWS
        history.insertMinuteBatch((0 until cap + 500).map {
            snap(id, "u1", it * minute, avg = it.toDouble(), count = 1)
        })

        val lus = history.findMinuteRange(id, "u1", 0, Long.MAX_VALUE)

        assertTrue(lus.size <= cap + 1, "lu : ${lus.size} pour un plafond de $cap")
        assertTrue(lus.size > cap, "une de plus que le plafond, pour que la troncature reste détectable")
    }
}
