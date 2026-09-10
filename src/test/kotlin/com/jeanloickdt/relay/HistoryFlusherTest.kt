package com.jeanloickdt.relay

import com.jeanloickdt.signal.data.SignalBucketAccumulator
import com.jeanloickdt.signal.data.SignalMinuteAggregator
import com.jeanloickdt.signal.data.SignalRawEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Ce qui se passe quand la base ne repond pas.
 *
 * Le defaut d'origine ne se voyait pas en lisant la boucle : `drain()` vidait
 * la file, puis l'ecriture partait. Entre les deux, un `catch` qui
 * journalisait. Les lignes tenues en memoire n'existaient plus nulle part, et
 * le tour suivant repartait d'une file vide — cinq secondes d'historique
 * perdues par echec, en silence.
 *
 * Ces tests provoquent l'echec, ce qu'une boucle de fond ne sait pas faire.
 */
class HistoryFlusherTest {

    private fun entree(ms: Long) = SignalRawEntry(
        signalId = 1L,
        ownerId = "moi",
        ts = ms,
        value = ms.toDouble()
    )

    /** Un videur dont l'ecriture obeit a un drapeau. */
    private fun videur(
        buffers: HistoryBuffers,
        agregateur: SignalMinuteAggregator = SignalMinuteAggregator(),
        echoue: () -> Boolean,
        vus: MutableList<Int> = mutableListOf()
    ) = HistoryFlusher(
        buffers = buffers,
        minutes = agregateur,
        write = { raw, _ ->
            if (echoue()) throw IllegalStateException("SQLITE_BUSY simule")
            vus += raw.size
        },
        periodMs = 5_000L,
        clock = { 0L }
    )

    // ─── La garantie ────────────────────────────────────────────────

    @Test
    fun un_echec_rend_le_lot_au_lieu_de_le_perdre() {
        val buffers = HistoryBuffers()
        var enPanne = true
        val f = videur(buffers, echoue = { enPanne })

        repeat(3) { buffers.signalRawBuffer.offer(entree(it.toLong())) }
        assertEquals(3, buffers.signalRawBuffer.size)

        val issue = f.flushOnce()

        assertIs<HistoryFlusher.Outcome.Failed>(issue)
        assertEquals(
            3, buffers.signalRawBuffer.size,
            "le lot doit etre revenu dans la file, pas avoir disparu"
        )
    }

    @Test
    fun le_lot_rendu_repart_au_tour_suivant_et_dans_l_ordre() {
        val buffers = HistoryBuffers()
        var enPanne = true
        val f = videur(buffers, echoue = { enPanne })

        buffers.signalRawBuffer.offer(entree(10))
        buffers.signalRawBuffer.offer(entree(20))
        f.flushOnce()                                   // echoue, rend le lot

        // Une ligne arrive PENDANT la panne : elle est plus recente, donc
        // elle doit sortir apres les deux qu'on a rendues.
        buffers.signalRawBuffer.offer(entree(30))

        enPanne = false
        val issue = f.flushOnce()

        assertIs<HistoryFlusher.Outcome.Written>(issue)
        assertEquals(3, issue.rawRows)
        assertEquals(0, buffers.signalRawBuffer.size)
    }

    @Test
    fun les_seaux_minute_fermes_survivent_aussi() {
        // Ils n'avaient pas de file : l'agregateur les rendait et la boucle
        // les ecrivait dans la foulee. Un echec les emportait.
        val buffers = HistoryBuffers()
        val agregateur = SignalMinuteAggregator()
        var enPanne = true
        val f = HistoryFlusher(
            buffers = buffers,
            minutes = agregateur,
            write = { _, _ -> if (enPanne) throw IllegalStateException("disque plein") },
            periodMs = 5_000L,
            clock = { 0L }
        )

        buffers.minutePending.offer(seau(bucketAt = 60_000L))
        buffers.minutePending.offer(seau(bucketAt = 120_000L))

        f.flushOnce()

        assertEquals(
            2, buffers.minutePending.size,
            "les seaux fermes doivent attendre le prochain essai"
        )
    }

    // ─── Le repli ───────────────────────────────────────────────────

    @Test
    fun le_delai_double_a_chaque_echec_et_plafonne() {
        val buffers = HistoryBuffers()
        val f = videur(buffers, echoue = { true })

        assertEquals(5_000L, f.nextDelayMs, "en temps normal, la periode")

        f.flushOnce();  assertEquals(5_000L, f.nextDelayMs)   // 1er echec
        f.flushOnce();  assertEquals(10_000L, f.nextDelayMs)  // 2e
        f.flushOnce();  assertEquals(20_000L, f.nextDelayMs)  // 3e
        f.flushOnce();  assertEquals(40_000L, f.nextDelayMs)  // 4e

        repeat(20) { f.flushOnce() }
        assertEquals(
            HistoryFlusher.MAX_BACKOFF_MS, f.nextDelayMs,
            "au-dela, reessayer plus rarement n'aide plus personne"
        )
    }

    @Test
    fun une_ecriture_qui_passe_remet_le_compteur_a_zero() {
        val buffers = HistoryBuffers()
        var enPanne = true
        val f = videur(buffers, echoue = { enPanne })

        repeat(3) { f.flushOnce() }
        assertEquals(3, f.failures)

        enPanne = false
        f.flushOnce()

        assertEquals(0, f.failures)
        assertEquals(5_000L, f.nextDelayMs)
    }

    // ─── La borne tient quand meme ──────────────────────────────────

    @Test
    fun une_panne_longue_ne_fait_pas_grossir_la_file_sans_borne() {
        // La garantie n'est pas « rien ne se perd jamais » : c'est « on garde
        // ce qu'on peut, et on compte le reste ». Un serveur qui garde tout
        // finit par mourir de faim, et emporte alors TOUT.
        val buffers = HistoryBuffers()
        val f = videur(buffers, echoue = { true })
        val plafond = buffers.signalRawBuffer.capacity

        repeat(plafond) { buffers.signalRawBuffer.offer(entree(it.toLong())) }
        f.flushOnce()

        assertEquals(plafond, buffers.signalRawBuffer.size, "la file reste a son plafond")

        // Le tour suivant : la file est pleine, on lui rend un lot entier.
        // Rien ne tient, tout est refuse, et compte.
        val refusesAvant = buffers.signalRawBuffer.refusedCount
        buffers.signalRawBuffer.offer(entree(999))
        assertTrue(
            buffers.signalRawBuffer.refusedCount > refusesAvant,
            "ce qui deborde est COMPTE, pas jete en silence"
        )
    }

    @Test
    fun sans_rien_a_ecrire_le_tour_reussit() {
        val buffers = HistoryBuffers()
        val f = videur(buffers, echoue = { false })
        val issue = f.flushOnce()
        assertIs<HistoryFlusher.Outcome.Written>(issue)
        assertEquals(0, issue.rawRows)
        assertEquals(0, issue.minuteRows)
    }

    private fun seau(bucketAt: Long) = SignalBucketAccumulator.Snapshot(
        signalId = 1L,
        ownerId = "moi",
        bucketAt = bucketAt,
        minValue = 4.0,
        minAt = bucketAt,
        maxValue = 6.0,
        maxAt = bucketAt,
        avgValue = 5.0,
        sampleCount = 2
    )
}
