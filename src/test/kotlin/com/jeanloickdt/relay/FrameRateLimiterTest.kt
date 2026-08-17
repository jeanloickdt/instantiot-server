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

package com.jeanloickdt.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fuse is pure arithmetic over timestamps the caller provides, so every
 * boundary — burst edge, refill rate, the 30-second abuse window, the streak
 * reset — is provable without a socket or a clock.
 */
class FrameRateLimiterTest {

    private val T0 = 1_000_000L

    // ── Le seau ───────────────────────────────────────────────────────────

    @Test
    fun `a healthy cadence is never throttled`() {
        val fuse = FrameRateLimiter(ratePerSecond = 50)
        // 4 widgets at 10 Hz = 40 frames/s, under the rate — forever.
        repeat(400) { i ->
            assertTrue(fuse.tryAcquire(T0 + i * 25L), "frame $i at 40/s must pass")
        }
        assertEquals(0, fuse.dropped)
    }

    @Test
    fun `the burst is twice the rate, then the gate closes`() {
        val fuse = FrameRateLimiter(ratePerSecond = 50)
        // A board flushing a backlog in one instant: 100 pass, the 101st does not.
        var accepted = 0
        repeat(150) { if (fuse.tryAcquire(T0)) accepted++ }

        assertEquals(100, accepted)
        assertEquals(50, fuse.dropped)
    }

    @Test
    fun `tokens refill at the configured rate`() {
        val fuse = FrameRateLimiter(ratePerSecond = 50)
        repeat(100) { fuse.tryAcquire(T0) }          // bucket emptied
        assertFalse(fuse.tryAcquire(T0))

        // 200 ms later: 10 tokens back — the 11th frame is refused.
        var accepted = 0
        repeat(20) { if (fuse.tryAcquire(T0 + 200)) accepted++ }
        assertEquals(10, accepted)
    }

    @Test
    fun `the bucket never grows past the burst`() {
        val fuse = FrameRateLimiter(ratePerSecond = 50)
        // An hour of silence must not bank an hour of credit.
        fuse.tryAcquire(T0)
        var accepted = 0
        repeat(10_000) { if (fuse.tryAcquire(T0 + 3600_000L)) accepted++ }
        assertTrue(accepted <= 100, "silence banked $accepted frames of credit")
    }

    @Test
    fun `a clock going backwards does not mint tokens`() {
        val fuse = FrameRateLimiter(ratePerSecond = 50)
        repeat(100) { fuse.tryAcquire(T0) }
        assertFalse(fuse.tryAcquire(T0 - 60_000L), "a backwards clock must not refill")
    }

    // ── La déconnexion ────────────────────────────────────────────────────

    @Test
    fun `thirty seconds of uninterrupted refusal disconnects`() {
        val fuse = FrameRateLimiter(ratePerSecond = 50)
        // A loop() without delay(): far past the refill rate, continuously.
        var t = T0
        while (t < T0 + 31_000L) {
            repeat(3) { fuse.tryAcquire(t) }   // 300/s against a refill of 50/s
            t += 10
        }
        assertTrue(fuse.shouldDisconnect(t))
    }

    @Test
    fun `a burst is not abuse — the streak resets on the first accepted frame`() {
        val fuse = FrameRateLimiter(ratePerSecond = 50, disconnectAfterMs = 30_000L)
        // Refused at T0…
        repeat(150) { fuse.tryAcquire(T0) }
        assertFalse(fuse.shouldDisconnect(T0 + 29_000L))

        // …then the board slows down and a frame passes: streak over.
        assertTrue(fuse.tryAcquire(T0 + 29_000L))
        assertFalse(
            fuse.shouldDisconnect(T0 + 40_000L),
            "an accepted frame must end the abuse streak"
        )
    }

    @Test
    fun `a mildly noisy board is limited for life, never evicted`() {
        // 60/s against a 50/s ceiling: over budget forever, but drops average
        // only 10/s — far under the 5x severity bar. Evicting it would turn
        // near-free dropped frames into a TLS handshake generator every 30 s,
        // the most expensive CPU item the server has. It stays connected and
        // simply never exceeds 50/s of accepted frames.
        val fuse = FrameRateLimiter(ratePerSecond = 50)
        var t = T0
        while (t < T0 + 120_000L) {          // two full minutes of mild overflow
            fuse.tryAcquire(t)
            t += 16                          // ~60 frames per second
        }
        assertFalse(fuse.shouldDisconnect(t), "a 1.2x board must never be evicted")
        assertTrue(fuse.dropped > 0, "but it must have been throttled")
    }

    @Test
    fun `severity is measured on the streak average, not the total`() {
        val fuse = FrameRateLimiter(ratePerSecond = 50)
        // 500/s for 31 s: drops ≈ 450/s, way past the (5-1)x50 = 200/s bar.
        var t = T0
        while (t < T0 + 31_000L) {
            repeat(5) { fuse.tryAcquire(t) }
            t += 10
        }
        assertTrue(fuse.shouldDisconnect(t))
    }

    @Test
    fun `a legitimate heartbeat cadence never trips the heartbeat fuse`() {
        // One heartbeat every 5 s against a 10/s ceiling — two orders of
        // magnitude of slack, so a limitation can never masquerade as a
        // network timeout.
        val fuse = FrameRateLimiter(FrameRateLimiter.HEARTBEAT_RATE_PER_SECOND)
        repeat(1_000) { i ->
            assertTrue(fuse.tryAcquire(T0 + i * 5_000L), "heartbeat $i must pass")
        }
        assertEquals(0, fuse.dropped)
    }

    @Test
    fun `a healthy board never disconnects, whatever the uptime`() {
        val fuse = FrameRateLimiter(ratePerSecond = 50)
        repeat(4_000) { i -> fuse.tryAcquire(T0 + i * 25L) }
        assertFalse(fuse.shouldDisconnect(T0 + 100_000L))
    }

    @Test
    fun `the drop counter is the observability hook`() {
        val fuse = FrameRateLimiter(ratePerSecond = 50)
        repeat(250) { fuse.tryAcquire(T0) }
        assertEquals(150, fuse.dropped)
    }
}
