package com.jeanloickdt.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Le plafond de poignées de main par adresse.
 *
 * Ce qui compte : une adresse ne remplit pas le relais à elle seule, une
 * autre adresse n'en souffre pas, et Caddy — une adresse privée qui porte
 * toutes les cartes en TLS — n'est jamais compté.
 */
class HandshakeGateTest {

    private val attacker = "203.0.113.7"
    private val neighbour = "198.51.100.9"

    @Test
    fun `une adresse tient ses poignees de main jusqu'au plafond, pas au-dela`() {
        val gate = HandshakeGate(perAddress = 3)
        repeat(3) { assertTrue(gate.tryAcquire(attacker), "la poignee $it doit passer") }
        assertFalse(gate.tryAcquire(attacker))
        assertEquals(3, gate.pendingFor(attacker))
        assertEquals(1L, gate.refusedCount)
    }

    @Test
    fun `une adresse pleine ne ferme pas la porte aux autres`() {
        val gate = HandshakeGate(perAddress = 2)
        repeat(2) { gate.tryAcquire(attacker) }
        assertFalse(gate.tryAcquire(attacker))
        assertTrue(gate.tryAcquire(neighbour))
    }

    @Test
    fun `une poignee de main aboutie rend sa place`() {
        val gate = HandshakeGate(perAddress = 1)
        assertTrue(gate.tryAcquire(attacker))
        assertFalse(gate.tryAcquire(attacker))
        gate.release(attacker)
        assertEquals(0, gate.pendingFor(attacker))
        assertTrue(gate.tryAcquire(attacker))
    }

    @Test
    fun `rendre une place jamais prise ne cree pas de credit`() {
        val gate = HandshakeGate(perAddress = 1)
        gate.release(attacker)
        gate.release(attacker)
        assertTrue(gate.tryAcquire(attacker))
        assertFalse(gate.tryAcquire(attacker))
    }

    @Test
    fun `les adresses privees et la boucle locale ne sont pas comptees`() {
        val gate = HandshakeGate(perAddress = 1)
        for (caddyOrLan in listOf("172.18.0.4", "10.0.0.2", "192.168.1.20", "127.0.0.1", "::1", "fd12::1", "fe80::1")) {
            repeat(5) { assertTrue(gate.tryAcquire(caddyOrLan), "$caddyOrLan ne doit jamais etre refusee") }
            assertEquals(0, gate.pendingFor(caddyOrLan))
        }
        assertEquals(0L, gate.refusedCount)
    }

    @Test
    fun `l'adresse se lit dans ce que Ktor imprime`() {
        assertEquals("203.0.113.7", HandshakeGate.hostOf("/203.0.113.7:51234"))
        assertEquals("203.0.113.7", HandshakeGate.hostOf("203.0.113.7:51234"))
        assertEquals("2001:db8::1", HandshakeGate.hostOf("[2001:db8::1]:51234"))
        assertEquals("2001:db8::1", HandshakeGate.hostOf("/[2001:db8::1]:51234"))
    }

    @Test
    fun `une adresse publique IPv6 est comptee comme une IPv4`() {
        val gate = HandshakeGate(perAddress = 1)
        assertTrue(gate.tryAcquire("2001:db8::1"))
        assertFalse(gate.tryAcquire("2001:db8::1"))
    }
}
