package com.jeanloickdt.discovery

import com.jeanloickdt.common.ServerConfig
import org.slf4j.LoggerFactory

/**
 * Publisher mDNS macOS-natif via la commande `dns-sd -R`.
 *
 * Sur macOS, JmDNS lutte contre `mDNSResponder` + tous les autres
 * apps mDNS (Chrome, ADB, Arduino mdns-discovery…) pour le port
 * 5353 et finit par échouer avec `NoRouteToHostException`.
 * `dns-sd` est l'outil natif macOS qui dialogue directement avec
 * mDNSResponder — pas de conflit, marche dans toutes les
 * configurations.
 *
 * Le process `dns-sd -R` reste vivant et publie tant qu'il tourne.
 * On le tient en vie pour la durée de la JVM, on le kill au
 * shutdown (cf. [stop]).
 */
object DnsSdPublisher {

    private val log = LoggerFactory.getLogger(DnsSdPublisher::class.java)

    private var process: Process? = null

    /**
     * Démarre l'annonce mDNS via dns-sd.
     * Le process tourne jusqu'à ce qu'on le tue (stop).
     */
    fun start(displayName: String) {
        if (process?.isAlive == true) {
            log.warn("dns-sd publisher already running — stopping previous instance first")
            stop()
        }

        val httpPort = ServerConfig.runningHttpPort
        val tcpPort = ServerConfig.runningTcpPort
        val version = ServerConfig.version

        try {
            // dns-sd -R "Name" "_type._tcp" "domain" port [TXT_records...]
            //
            // - Domain = "local" (= local. = mDNS multicast)
            // - TXT records = "key=value" args supplémentaires
            //
            // Le sub-process reste vivant et republish tant qu'il tourne.
            // Notre `process` keep-alive le maintient pour toute la durée
            // de la JVM.
            val pb = ProcessBuilder(
                "dns-sd", "-R",
                displayName,
                "_instantiot._tcp",
                "local",
                httpPort.toString(),
                "version=$version",
                "tcpPort=$tcpPort",
                "name=$displayName"
            ).redirectErrorStream(true)

            val p = pb.start()
            process = p

            // Drain stdout dans un thread daemon — sinon le sub-process
            // bloque quand son buffer stdout est plein. On log juste les
            // lignes Registered/Updated pour debug, on ignore le reste.
            Thread({
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank() &&
                            (line.contains("Registered") || line.contains("Got a reply"))) {
                            log.debug("dns-sd: {}", line.trim())
                        }
                    }
                }
            }, "dns-sd-reader").apply { isDaemon = true }.start()

            log.info(
                "mDNS published via dns-sd: name='{}', httpPort={}, tcpPort={}",
                displayName, httpPort, tcpPort
            )
        } catch (e: Exception) {
            log.warn("dns-sd publish failed (non-fatal — manual add still works): {}", e.message)
            process = null
        }
    }

    fun stop() {
        val p = process
        process = null
        if (p != null && p.isAlive) {
            try {
                p.destroy()
                if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                }
                log.info("mDNS unregistered via dns-sd")
            } catch (e: Exception) {
                log.warn("dns-sd stop failed: {}", e.message)
            }
        }
    }
}
