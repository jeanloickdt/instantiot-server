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


// relay/HandshakeGate.kt
package com.jeanloickdt.relay

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * How many connections ONE address may hold in the handshake at once.
 *
 * [ConnectionGate] caps the whole relay: past its limit every board is
 * refused, whoever it belongs to. That is the door a single host could
 * fill — ten thousand empty sockets, renewed every ten seconds, and no
 * legitimate board gets in. The audit of 9 September 2026 named it (H2)
 * and closed the plaintext port rather than fix it; the port is back, for
 * the boards that have no TLS stack, so the fix is due.
 *
 * A slot here is held from `accept()` until the token is verified, ten
 * seconds at most: after that the socket is either a known board, which
 * the global gate accounts for, or closed. A NAT with fifty boards behind
 * it can still reboot them all at once; a host that wants to fill the
 * relay needs two hundred addresses instead of one.
 *
 * Private and loopback addresses are not counted. On the cloud that is
 * Caddy, which terminates the TLS of every board and hands them all to the
 * relay from the same Docker address — a storm of two thousand legitimate
 * boards reconnecting would trip any per-address count. On a self-hosted
 * server it is the LAN, where the cap has nothing to protect against.
 */
class HandshakeGate(val perAddress: Int) {

    private val pending = ConcurrentHashMap<String, AtomicInteger>()
    private val refused = AtomicLong(0)

    /**
     * Claims a handshake slot for [address], or refuses.
     *
     * @return false when this address already holds [perAddress] handshakes
     *         — the caller closes the socket without reading from it.
     */
    fun tryAcquire(address: String): Boolean {
        if (isExempt(address)) return true
        val counter = pending.computeIfAbsent(address) { AtomicInteger(0) }
        val now = counter.incrementAndGet()
        if (now > perAddress) {
            releaseCounter(address, counter)
            refused.incrementAndGet()
            return false
        }
        return true
    }

    /**
     * Returns the slot — once the token is verified, or when the connection
     * ends before that. Never twice for the same connection.
     */
    fun release(address: String) {
        if (isExempt(address)) return
        val counter = pending[address] ?: return
        releaseCounter(address, counter)
    }

    private fun releaseCounter(address: String, counter: AtomicInteger) {
        if (counter.decrementAndGet() <= 0) {
            // Drop the entry so the map does not grow with every address
            // that ever connected. A racing acquire on the same address
            // recreates it: the count is then one short for an instant,
            // which errs on the side of admitting a board.
            pending.remove(address, counter)
        }
    }

    fun pendingFor(address: String): Int = pending[address]?.get() ?: 0
    val refusedCount: Long get() = refused.get()

    companion object {
        /**
         * Fifty: a classroom rebooting behind one NAT passes; a host that
         * wants to fill the relay needs two hundred addresses.
         */
        const val DEFAULT_PER_ADDRESS = 50

        /**
         * The address part of a socket's remote address, as Ktor prints it:
         * `1.2.3.4:5678`, `/1.2.3.4:5678`, `[::1]:5678`.
         */
        fun hostOf(remote: String): String {
            val s = remote.trimStart('/')
            if (s.startsWith("[")) return s.substringAfter('[').substringBefore(']')
            return if (s.count { it == ':' } == 1) s.substringBefore(':') else s.substringBeforeLast(':')
        }

        /** Loopback, RFC 1918, link-local and IPv6 unique-local: Caddy, or the LAN. */
        fun isExempt(host: String): Boolean {
            if (host.isEmpty()) return false
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress) return true
            val b = addr.address
            return b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC   // fc00::/7
        }
    }
}
