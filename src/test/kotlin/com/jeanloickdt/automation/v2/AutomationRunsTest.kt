package com.jeanloickdt.automation.v2

import com.jeanloickdt.database.TestDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Le tampon circulaire — la moitié visible de la révision ②.
 *
 * Ce qui doit être prouvé n'est pas qu'on sait insérer : c'est que la table
 * NE PEUT PAS grossir. Une purge planifiée est une purge qui peut ne pas
 * tourner, et on découvrirait le problème en regardant le disque plein.
 */
class AutomationRunsTest {

    private val runs = AutomationRuns()

    // Le pendant du nuage s'appelle `PostgresTestBase` et partage un pool,
    // parce que Postgres n'aime pas qu'on le rouvre. SQLite offre un fichier
    // neuf pour rien : chaque test part d'une table rase.
    @BeforeTest
    fun setup() {
        TestDatabase.fresh("automation-runs")
    }

    @Test
    fun `un passage se relit tel qu il a ete ecrit`() {
        runs.record("o1", "r1", 1_000L, RunOutcome.FIRED, "Fan on · push sent")

        val all = runs.list("o1", "r1")
        assertEquals(1, all.size)
        assertEquals(RunOutcome.FIRED, all[0].outcome)
        assertEquals("Fan on · push sent", all[0].reason)
        assertEquals(1_000L, all[0].atMs)
    }

    @Test
    fun `le tampon ne depasse jamais vingt lignes`() {
        // Cent passages, vingt lignes. C'est la seule propriete qui compte :
        // une regle sur un capteur a 1 Hz produirait 86 400 lignes par jour
        // sans cette borne.
        repeat(100) { i ->
            runs.record("o1", "r1", i.toLong(), RunOutcome.SKIPPED, "passage $i")
        }
        assertEquals(AutomationRuns.KEEP_PER_RULE, runs.list("o1", "r1").size)
    }

    @Test
    fun `ce sont les plus RECENTS qui survivent`() {
        repeat(30) { i -> runs.record("o1", "r1", i.toLong(), RunOutcome.SKIPPED, "passage $i") }

        val kept = runs.list("o1", "r1")
        // Les plus recents d'abord — l'ordre de « LAST RUNS ».
        assertEquals("passage 29", kept.first().reason)
        assertEquals("passage 10", kept.last().reason)
        assertTrue(kept.none { it.reason == "passage 9" }, "un ancien passage a survecu")
    }

    @Test
    fun `chaque regle a son propre tampon`() {
        // Vingt lignes PAR REGLE : une regle bavarde ne doit pas effacer la
        // trace d'une regle silencieuse.
        repeat(25) { i -> runs.record("o1", "bavarde", i.toLong(), RunOutcome.FIRED) }
        runs.record("o1", "silencieuse", 999L, RunOutcome.FIRED, "le seul passage")

        assertEquals(20, runs.list("o1", "bavarde").size)
        assertEquals(1, runs.list("o1", "silencieuse").size)
        assertEquals("le seul passage", runs.list("o1", "silencieuse").first().reason)
    }

    @Test
    fun `un compte ne lit pas la trace du voisin`() {
        runs.record("o1", "r1", 1L, RunOutcome.FIRED, "chez moi")
        runs.record("o2", "r1", 2L, RunOutcome.FIRED, "chez le voisin")

        // Meme identifiant de regle, deux comptes : le scoping n'est pas
        // decoratif, c'est ce qui empeche de lire la trace de quelqu'un
        // d'autre en devinant un identifiant.
        val mine = runs.list("o1", "r1")
        assertEquals(1, mine.size)
        assertEquals("chez moi", mine.first().reason)
    }

    @Test
    fun `un essai ne se confond pas avec un tir`() {
        // Sans `TESTED`, la ligne « tiree 2 fois » deviendrait fausse des
        // qu'on a appuye sur « Run actions now ».
        runs.record("o1", "r1", 1L, RunOutcome.FIRED)
        runs.record("o1", "r1", 2L, RunOutcome.TESTED)

        val all = runs.list("o1", "r1")
        assertEquals(RunOutcome.TESTED, all[0].outcome)
        assertEquals(RunOutcome.FIRED, all[1].outcome)
    }

    @Test
    fun `un motif trop long est borne`() {
        // Un motif construit avec une valeur venue de l'exterieur ne doit pas
        // pouvoir faire grossir la table.
        runs.record("o1", "r1", 1L, RunOutcome.SKIPPED, "x".repeat(5_000))
        assertEquals(AutomationRuns.REASON_MAX, runs.list("o1", "r1").first().reason!!.length)
    }

    @Test
    fun `supprimer une regle efface sa trace`() {
        repeat(5) { i -> runs.record("o1", "r1", i.toLong(), RunOutcome.FIRED) }
        runs.deleteForRule("r1")
        assertTrue(runs.list("o1", "r1").isEmpty())
    }
}
