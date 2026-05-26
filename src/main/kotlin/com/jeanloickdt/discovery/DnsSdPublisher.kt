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

package com.jeanloickdt.discovery

import com.jeanloickdt.common.ServerConfig
import org.slf4j.LoggerFactory

/**
 * macOS-native mDNS publisher via the `dns-sd -R` command.
 *
 * On macOS, JmDNS fights against `mDNSResponder` + all the other
 * mDNS apps (Chrome, ADB, Arduino mdns-discovery…) for port 5353
 * and ends up failing with `NoRouteToHostException`.
 * `dns-sd` is the native macOS tool that talks directly with
 * mDNSResponder — no conflict, works in all configurations.
 *
 * The `dns-sd -R` process stays alive and publishes as long as it
 * runs. We keep it alive for the lifetime of the JVM and kill it
 * on shutdown (cf. [stop]).
 */
object DnsSdPublisher {

    private val log = LoggerFactory.getLogger(DnsSdPublisher::class.java)

    private var process: Process? = null

    /**
     * Starts the mDNS announcement via dns-sd.
     * The process runs until we kill it (stop).
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
            // - TXT records = "key=value" additional args
            //
            // The sub-process stays alive and republishes as long as it
            // runs. Our `process` keep-alive holds it for the entire
            // lifetime of the JVM.
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

            // Drain stdout in a daemon thread — otherwise the sub-process
            // blocks when its stdout buffer is full. We just log the
            // Registered/Updated lines for debug, ignore the rest.
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