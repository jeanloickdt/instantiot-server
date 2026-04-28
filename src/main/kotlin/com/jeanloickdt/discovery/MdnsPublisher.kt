package com.jeanloickdt.discovery

import com.jeanloickdt.common.ServerConfig
import org.slf4j.LoggerFactory
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Annonce le serveur InstantIoT en Bonjour / mDNS sur le LAN.
 *
 * Service publié :
 *  - type    : `_instantiot._tcp.local.`
 *  - port    : [ServerConfig.runningHttpPort] (utilisé par /api/status)
 *  - props   : `version`, `tcpPort` (port relay TCP des devices ESP32),
 *              `name` (nom user-facing — futur paramétrage admin).
 *
 * Le bind passe par l'IP LAN détectée par [ServerConfig.localIp]. JmDNS
 * peut écouter sur toutes les interfaces, mais on cible la LAN pour ne
 * pas annoncer sur des bridges Docker / loopback.
 *
 * Idempotent : appeler [start] après un [stop] re-publie ; appeler [stop]
 * sans démarrer est un no-op.
 */
object MdnsPublisher {

    private val log = LoggerFactory.getLogger(MdnsPublisher::class.java)

    private const val SERVICE_TYPE = "_instantiot._tcp.local."

    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null

    fun start(displayName: String) {
        // ─── macOS : déléguer à dns-sd natif ──────────────────
        // JmDNS lutte contre mDNSResponder + Chrome + ADB +
        // Arduino mdns-discovery pour le port 5353 et finit par
        // échouer ("NoRouteToHostException"). dns-sd dialogue
        // directement avec mDNSResponder, pas de conflit.
        // Linux (avahi) et Windows : JmDNS marche bien.
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
            // ⚠️ Hostname mDNS explicite — sinon collision avec le
            // `mDNSResponder` système (macOS) ou `avahi-daemon` (Linux)
            // qui ont **déjà** publié le hostname `.local.` de la machine.
            // JmDNS lance alors un probe, voit la réponse du daemon
            // système pour le même nom, croit à un conflit, tente un
            // recover, time out, et meurt avec "Could not recover we
            // are Down!".
            //
            // Le hash mélange l'IP de bind (unique par machine sur un
            // LAN sain — DHCP garantit l'unicité) ET le displayName,
            // pour qu'un même utilisateur lançant 2 serveurs sur 2
            // machines distinctes (même `displayName` "InstantIoT
            // Server" par défaut) n'aboutisse pas au même hostname.
            // Si **par malchance** collision quand même : JmDNS
            // suffixe automatiquement le **service instance name**
            // (`Name (2)._instantiot._tcp.`), donc l'app voit deux
            // entrées distinctes — pas de perte d'info pour l'user.
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
     * Résout l'adresse IPv4 LAN pour le bind JmDNS. Utilise le scoring
     * intelligent de [ServerConfig.bestLanInterface] qui filtre les
     * interfaces VPN (utun/tun/tap), virtuelles, sans multicast — sinon
     * JmDNS bind sur l'interface VPN et on prend des
     * `NoRouteToHostException` au premier paquet multicast `224.0.0.251`.
     *
     * Fallback `InetAddress.getLocalHost()` en dernier recours.
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
