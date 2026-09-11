package com.jeanloickdt.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Le fusible d'une session app : une rafale passe, une boucle non, et le
 * seau se remplit — la dernière position d'un curseur arrive toujours.
 */
class AppInboundFuseTest {
    @Test
    fun `a burst of sixty passes, the sixty-first is refused and counted`() {
        val fuse = AppInboundFuse(ratePerSecond = 30)
        var admitted = 0
        repeat(60) { if (fuse.admit(1_000)) admitted++ }
        assertEquals(60, admitted, "la rafale — deux fois le débit — passe entière")
        assertFalse(fuse.admit(1_000))
        assertEquals(1, fuse.refused)
    }

    @Test
    fun `a loop is refused at line rate, never disconnected — and the bucket refills`() {
        val fuse = AppInboundFuse(ratePerSecond = 30)
        repeat(60) { fuse.admit(1_000) }
        repeat(1_000) { fuse.admit(1_000) }
        assertEquals(1_000, fuse.refused)
        // Une seconde plus tard, trente jetons de plus : la position finale
        // du curseur passe.
        var later = 0
        repeat(40) { if (fuse.admit(2_000)) later++ }
        assertEquals(30, later)
    }

    @Test
    fun `a finger at twenty hertz never trips it`() {
        val fuse = AppInboundFuse(ratePerSecond = 30)
        var refused = 0
        for (i in 0 until 200) if (!fuse.admit(1_000L + i * 50)) refused++   // 20 Hz pendant 10 s
        assertEquals(0, refused)
        assertTrue(fuse.refused == 0L)
    }
}
