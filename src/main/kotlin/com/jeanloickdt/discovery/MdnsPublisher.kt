/*
 * InstantIoT Server — self-hosted IoT relay for makers.
 * Copyright (C) 2026 InstantIoT
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
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Announces the InstantIoT server over Bonjour / mDNS on the LAN.
 *
 * Published service:
 *  - type    : `_instantiot._tcp.local.`
 *  - port    : [ServerConfig.runningHttpPort] (used by /api/status)
 *  - props   : `version`, `tcpPort` (TCP relay port of ESP32 devices),
 *              `name` (user-facing name — future admin setting).
 *
 * The bind goes through the LAN IP detected by [ServerConfig.localIp].
 * JmDNS can listen on all interfaces, but we target the LAN to avoid
 * announcing on Docker bridges / loopback.
 *
 * Idempotent: calling [start] after a [stop] re-publishes; calling [stop]
 * without starting is a no-op.
 */
object MdnsPublisher {

    private val log = LoggerFactory.getLogger(MdnsPublisher::class.java)

    private const val SERVICE_TYPE = "_instantiot._tcp.local."

    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null

    fun start(displayName: String) {
        // ─── macOS: delegate to native dns-sd ──────────────────
        // JmDNS fights against mDNSResponder + Chrome + ADB +
        // Arduino mdns-discovery for port 5353 and ends up
        // failing ("NoRouteToHostException"). dns-sd talks
        // directly with mDNSResponder, no conflict.
        // Linux (avahi) and Windows: JmDNS works fine.
        if (isMacOs()) {
            DnsSdPublisher.start(displayName)
            return
        }

        if (jmdns != null) {
            log.warn("mDNS publisher already running — stopping previous instance first")
            stop()
        }

        val httpPort = ServerConfig.runningHttpPort
        val tcpPort = ServerConfig.runningTcpPort
        val version = ServerConfig.version

        val bindAddress = resolveBindAddress()

        try {
            // ⚠️ Explicit mDNS hostname — otherwise collision with the
            // system `mDNSResponder` (macOS) or `avahi-daemon` (Linux)
            // which have **already** published the machine's `.local.`
            // hostname. JmDNS then launches a probe, sees the system
            // daemon's reply for the same name, believes there's a
            // conflict, attempts a recover, times out, and dies with
            // "Could not recover we are Down!".
            //
            // The hash mixes the bind IP (unique per machine on a
            // healthy LAN — DHCP guarantees uniqueness) AND the
            // displayName, so that the same user running 2 servers on
            // 2 distinct machines (same default `displayName`
            // "InstantIoT Server") doesn't end up with the same
            // hostname. If a collision happens anyway **by bad luck**:
            // JmDNS automatically suffixes the **service instance name**
            // (`Name (2)._instantiot._tcp.`), so the app sees two
            // distinct entries — no info loss for the user.
            val seed = "$displayName|${bindAddress.hostAddress}"
            val mdnsHostname = "instantiot-${kotlin.math.abs(seed.hashCode())}"
            val instance = JmDNS.create(bindAddress, mdnsHostname)
            val props = mapOf(
                "version" to version,
                "tcpPort" to tcpPort.toString(),
                "name" to displayName
            )
            val info = ServiceInfo.create(
                SERVICE_TYPE,
                displayName,
                httpPort,
                0,         // weight
                0,         // priority
                props
            )
            instance.registerService(info)

            jmdns = instance
            serviceInfo = info
            log.info(
                "mDNS published: {} — name='{}', mdnsHost='{}.local.', bind={}, httpPort={}, tcpPort={}",
                SERVICE_TYPE, displayName, mdnsHostname, bindAddress.hostAddress, httpPort, tcpPort
            )
        } catch (e: Exception) {
            log.warn("mDNS publish failed (non-fatal — manual add still works): {}", e.message)
            jmdns = null
            serviceInfo = null
        }
    }

    fun stop() {
        if (isMacOs()) {
            DnsSdPublisher.stop()
            return
        }

        val info = serviceInfo
        val instance = jmdns
        serviceInfo = null
        jmdns = null
        if (instance != null) {
            try {
                if (info != null) instance.unregisterService(info)
                instance.close()
                log.info("mDNS unregistered: {}", SERVICE_TYPE)
            } catch (e: Exception) {
                log.warn("mDNS stop failed: {}", e.message)
            }
        }
    }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name").lowercase().contains("mac")

    /**
     * Resolves the LAN IPv4 address for the JmDNS bind. Uses the smart
     * scoring of [ServerConfig.bestLanInterface] which filters out VPN
     * interfaces (utun/tun/tap), virtual ones, those without multicast —
     * otherwise JmDNS binds on the VPN interface and we get
     * `NoRouteToHostException` on the first `224.0.0.251` multicast packet.
     *
     * Falls back to `InetAddress.getLocalHost()` as a last resort.
     */
    private fun resolveBindAddress(): InetAddress = try {
        val best = ServerConfig.bestLanInterface()
        if (best != null) {
            val (ip, name) = best
            log.info("mDNS bind selected interface: {} ({})", name, ip)
            InetAddress.getByName(ip)
        } else {
            log.warn("No suitable LAN interface found — falling back to localhost (mDNS may not work)")
            InetAddress.getLocalHost()
        }
    } catch (e: Exception) {
        log.warn("Failed to resolve bind address — fallback localhost: {}", e.message)
        InetAddress.getLocalHost()
    }
}