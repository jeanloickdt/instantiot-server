package com.jeanloickdt.common

import org.slf4j.LoggerFactory
import java.net.ServerSocket

/**
 * Trouve un port TCP libre à partir d'un port préféré, en essayant
 * `preferred`, `preferred+1`, ... jusqu'à `preferred + maxOffset`.
 *
 * Use case V1 : le serveur InstantIoT auto-démarre via jpackage /
 * systemd. Si le port 8080 est occupé (Tomcat, Jenkins, autre app),
 * on ne veut PAS planter — on essaie 8081, 8082, etc., pour donner
 * une chance au maker d'utiliser le serveur sans toucher la config.
 *
 * **Limitations** :
 *   - Race condition théorique : entre `findAvailable` et le vrai
 *     bind du serveur, un autre process peut grab le port. En
 *     pratique on n'a jamais vu ça. L'init serveur va planter avec
 *     `Address already in use` au pire.
 *   - Test = open + close ServerSocket : ça libère le port ms après.
 */
object PortFinder {

    private val log = LoggerFactory.getLogger("PortFinder")

    /**
     * Retourne le 1er port disponible à partir de `preferred`.
     * Throws [IllegalStateException] si aucun port libre dans la fenêtre.
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
     * Test si un port TCP est libre en essayant de le bind brièvement.
     */
    fun isAvailable(port: Int): Boolean = try {
        ServerSocket(port).use { /* bind ok → port libre */ }
        true
    } catch (_: Exception) {
        false
    }
}
