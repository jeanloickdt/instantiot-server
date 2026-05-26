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

package com.jeanloickdt.common

import org.slf4j.LoggerFactory
import java.net.ServerSocket

/**
 * Finds a free TCP port starting from a preferred port, trying
 * `preferred`, `preferred+1`, ... up to `preferred + maxOffset`.
 *
 * V1 use case: the InstantIoT server auto-starts via jpackage /
 * systemd. If port 8080 is taken (Tomcat, Jenkins, another app),
 * we do NOT want to crash — we try 8081, 8082, etc., to give
 * the maker a chance to use the server without touching the config.
 *
 * **Limitations**:
 *   - Theoretical race condition: between `findAvailable` and the
 *     server's actual bind, another process could grab the port. In
 *     practice we have never seen this. The server init will crash with
 *     `Address already in use` at worst.
 *   - Test = open + close ServerSocket: this frees the port ms later.
 */
object PortFinder {

    private val log = LoggerFactory.getLogger("PortFinder")

    /**
     * Returns the 1st available port starting from `preferred`.
     * Throws [IllegalStateException] if no port is free within the window.
     */
    fun findAvailable(preferred: Int, maxOffset: Int = 5, label: String = "port"): Int {
        for (offset in 0..maxOffset) {
            val candidate = preferred + offset
            if (isAvailable(candidate)) {
                if (offset > 0) {
                    log.warn(
                        "$label $preferred is busy — falling back to $candidate (offset +$offset)"
                    )
                }
                return candidate
            }
        }
        throw IllegalStateException(
            "No free $label found between $preferred and ${preferred + maxOffset}. " +
                "Edit ~/.instantiot/server.properties manually or stop conflicting services."
        )
    }

    /**
     * Tests whether a TCP port is free by trying to bind it briefly.
     */
    fun isAvailable(port: Int): Boolean = try {
        ServerSocket(port).use { /* bind ok → port free */ }
        true
    } catch (_: Exception) {
        false
    }
}