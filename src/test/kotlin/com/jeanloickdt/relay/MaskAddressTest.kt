package com.jeanloickdt.relay

import kotlin.test.Test
import kotlin.test.assertEquals

/** Le journal garde le réseau, pas le foyer. */
class MaskAddressTest {
    @Test
    fun `ipv4 keeps the slash-24, ipv6 the slash-48`() {
        assertEquals("203.0.113.0/24", maskAddress("/203.0.113.42:51234"))
        assertEquals("2001:db8:1::/48", maskAddress("/[2001:db8:1:2:3:4:5:6]:51234"))
        assertEquals("?", maskAddress("n'importe quoi"))
    }
}
