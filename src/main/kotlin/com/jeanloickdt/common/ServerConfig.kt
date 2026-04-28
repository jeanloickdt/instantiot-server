package com.jeanloickdt.common

import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Properties

/**
 * Configuration serveur — ports HTTP et TCP.
 *
 * Les ports sont stockes dans ~/.instantiot/server.properties.
 * Si le fichier n'existe pas, les valeurs par defaut sont utilisees.
 * Les changements de ports necessitent un redemarrage du serveur.
 */
object ServerConfig {

    private const val VERSION = "0.0.1"

    /** Répertoire racine de tous les fichiers d'état du serveur. */
    val instantiotDir: File = File("${System.getProperty("user.home")}/.instantiot").apply {
        mkdirs()
    }

    /** Chemin de la DB SQLite. Centralisé ici pour qu'une migration jpackage
     *  / systemd ne perde pas la DB selon le CWD. */
    val dbFile: File = File(instantiotDir, "instantiot.db")

    private val configFile = File(instantiotDir, "server.properties")

    var httpPort: Int = 8080
        private set

    var tcpPort: Int = 9001
        private set

    // ports réellement utilisés par le serveur au démarrage
    // (ne changent pas après un save — seulement après restart)
    var runningHttpPort: Int = 8080
        private set
    var runningTcpPort: Int = 9001
        private set

    // ============================================================
    // HISTORIQUE — Phase 1 (raw numeric)
    // ============================================================

    /** Retention des rows RAW dans `widget_history_numeric` (jours). */
    var historyRetentionRawDays: Int = 7
        private set

    /** Retention des rows opaques dans `widget_history` (jours). */
    var historyRetentionOpaqueDays: Int = 1
        private set

    /**
     * Throttle d'écriture en RAM → DB par (widgetId, seriesId).
     * 5s par défaut = max 17280 rows/jour/série pour des capteurs rapides.
     * Le relay temps réel n'est **pas** affecté — c'est purement la cadence
     * de persistance.
     */
    var historyThrottleRawIntervalMs: Long = 5_000L
        private set

    // ============================================================
    // HISTORIQUE — Phase 2 (downsampling)
    // ============================================================

    /** Rétention des buckets MINUTE (jours). */
    var historyRetentionMinDays: Int = 90
        private set

    /** Rétention des buckets HOUR (jours). */
    var historyRetentionHourDays: Int = 365
        private set

    /**
     * Rétention des buckets DAY (jours).
     * `-1` = infini (jamais purgé).
     */
    var historyRetentionDayDays: Int = -1
        private set

    /** Intervalle entre deux runs du downsampler (minutes). */
    var historyDownsampleIntervalMinutes: Int = 60
        private set

    val version: String get() = VERSION
    val startedAt: Long = System.currentTimeMillis()

    val uptimeMs: Long get() = System.currentTimeMillis() - startedAt

    /**
     * Résout l'IP LAN du serveur en privilégiant la "vraie" interface
     * Wi-Fi/Ethernet, pas un VPN ou interface virtuelle.
     *
     * Pourquoi un scoring : sur macOS avec un VPN actif (Cisco, Wireguard,
     * Tailscale, etc.), `getNetworkInterfaces` peut retourner l'interface
     * tunnel `utun` AVANT l'interface Wi-Fi `en0`. Si on prend la 1ère
     * trouvée, mDNS bind sur le tunnel → multicast `224.0.0.251` → "No
     * route to host" → mDNS down.
     *
     * Filtres + scoring :
     *   - Skip : loopback, down, virtuel, point-to-point, sans multicast
     *   - Score IP : 192.168.* (LAN home) > 10.* (LAN entreprise) >
     *                172.16-31.* (souvent VPN/Docker) > autre
     *   - Tie-break : interface name (en0 < en1 < etc.)
     */
    val localIp: String get() = try {
        bestLanInterface()?.first ?: InetAddress.getLocalHost().hostAddress
    } catch (_: Exception) {
        "unknown"
    }

    /**
     * Retourne (ipAddress, interfaceName) de la "meilleure" interface LAN
     * trouvée, ou null si aucune candidat valide.
     */
    fun bestLanInterface(): Pair<String, String>? {
        val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { iface ->
                try {
                    iface.isUp &&
                        !iface.isLoopback &&
                        !iface.isVirtual &&
                        !iface.isPointToPoint &&
                        iface.supportsMulticast()
                } catch (_: Exception) { false }
            }
            .flatMap { iface ->
                iface.inetAddresses.asSequence()
                    .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
                    .map { it.hostAddress to iface.name }
            }
            .toList()

        if (candidates.isEmpty()) return null

        // Score : plus haut = mieux
        fun score(ip: String, name: String): Int {
            var s = 0
            if (ip.startsWith("192.168.")) s += 100
            else if (ip.startsWith("10."))  s += 80
            else if (ip.startsWith("172.")) {
                // 172.16-31 = privé. Souvent docker/VPN — moins prioritaire.
                val second = ip.split(".").getOrNull(1)?.toIntOrNull() ?: 0
                if (second in 16..31) s += 30
            }
            // Préfère interfaces "physiques" classiques
            if (name.startsWith("en"))  s += 10  // macOS Wi-Fi/Ethernet
            if (name.startsWith("eth")) s += 10  // Linux Ethernet
            if (name.startsWith("wl"))  s += 8   // Linux Wi-Fi
            // Pénalise interfaces tunnel / virtuelles
            if (name.startsWith("utun")) s -= 50
            if (name.startsWith("tun"))  s -= 50
            if (name.startsWith("tap"))  s -= 50
            if (name.contains("docker")) s -= 50
            if (name.startsWith("br-"))  s -= 30  // Linux bridge
            return s
        }

        return candidates.maxByOrNull { (ip, name) -> score(ip, name) }
    }

    val dbSizeBytes: Long get() = try {
        dbFile.length()
    } catch (_: Exception) {
        0L
    }

    /**
     * Charger la config depuis le fichier properties.
     * Appele au demarrage avant de lancer les serveurs.
     */
    fun load() {
        if (!configFile.exists()) {
            // premier lancement — créer le fichier avec les valeurs par défaut
            save()
            runningHttpPort = httpPort
            runningTcpPort = tcpPort
            return
        }

        try {
            val props = Properties()
            configFile.inputStream().use { props.load(it) }
            httpPort = props.getProperty("http.port", "8080").toIntOrNull() ?: 8080
            tcpPort = props.getProperty("tcp.port", "9001").toIntOrNull() ?: 9001

            // Historique — Phase 1
            historyRetentionRawDays = props.getProperty("history.retention.raw.days", "7")
                .toIntOrNull()?.coerceAtLeast(1) ?: 7
            historyRetentionOpaqueDays = props.getProperty("history.retention.opaque.days", "1")
                .toIntOrNull()?.coerceAtLeast(1) ?: 1
            historyThrottleRawIntervalMs = (props.getProperty("history.throttle.raw.intervalSeconds", "5")
                .toLongOrNull()?.coerceAtLeast(0L) ?: 5L) * 1000L

            // Phase 2 — downsampling
            historyRetentionMinDays = props.getProperty("history.retention.min.days", "90")
                .toIntOrNull()?.coerceAtLeast(1) ?: 90
            historyRetentionHourDays = props.getProperty("history.retention.hour.days", "365")
                .toIntOrNull()?.coerceAtLeast(1) ?: 365
            historyRetentionDayDays = props.getProperty("history.retention.day.days", "-1")
                .toIntOrNull() ?: -1
            historyDownsampleIntervalMinutes = props.getProperty("history.downsample.intervalMinutes", "60")
                .toIntOrNull()?.coerceAtLeast(1) ?: 60
        } catch (_: Exception) {
            // fichier corrompu — garder les valeurs par defaut
        }
        // figer les ports de démarrage — ne changent plus après un save
        runningHttpPort = httpPort
        runningTcpPort = tcpPort
    }

    /**
     * Sauvegarder les ports dans le fichier properties.
     * Le serveur doit etre redemarre pour appliquer les changements.
     */
    fun save(newHttpPort: Int? = null, newTcpPort: Int? = null) {
        if (newHttpPort != null) httpPort = newHttpPort
        if (newTcpPort != null) tcpPort = newTcpPort
        writeProperties()
    }

    /**
     * Sauvegarder les paramètres d'historique. Tout `null` est ignoré
     * (partial update). Pas de redémarrage nécessaire — les valeurs
     * sont relues à chaque cycle de cleanup / downsample / write.
     */
    fun saveHistoryConfig(
        retentionRawDays: Int? = null,
        retentionOpaqueDays: Int? = null,
        throttleRawIntervalSeconds: Long? = null,
        retentionMinDays: Int? = null,
        retentionHourDays: Int? = null,
        retentionDayDays: Int? = null,
        downsampleIntervalMinutes: Int? = null
    ) {
        if (retentionRawDays != null)          historyRetentionRawDays          = retentionRawDays.coerceAtLeast(1)
        if (retentionOpaqueDays != null)       historyRetentionOpaqueDays       = retentionOpaqueDays.coerceAtLeast(1)
        if (throttleRawIntervalSeconds != null) historyThrottleRawIntervalMs    = throttleRawIntervalSeconds.coerceAtLeast(0L) * 1000L
        if (retentionMinDays != null)          historyRetentionMinDays          = retentionMinDays.coerceAtLeast(1)
        if (retentionHourDays != null)         historyRetentionHourDays         = retentionHourDays.coerceAtLeast(1)
        if (retentionDayDays != null)          historyRetentionDayDays          = retentionDayDays // -1 autorisé = infini
        if (downsampleIntervalMinutes != null) historyDownsampleIntervalMinutes = downsampleIntervalMinutes.coerceAtLeast(1)
        writeProperties()
    }

    private fun writeProperties() {
        configFile.parentFile.mkdirs()
        val props = Properties()
        props.setProperty("http.port", httpPort.toString())
        props.setProperty("tcp.port", tcpPort.toString())
        props.setProperty("history.retention.raw.days", historyRetentionRawDays.toString())
        props.setProperty("history.retention.opaque.days", historyRetentionOpaqueDays.toString())
        props.setProperty("history.throttle.raw.intervalSeconds", (historyThrottleRawIntervalMs / 1000L).toString())
        props.setProperty("history.retention.min.days", historyRetentionMinDays.toString())
        props.setProperty("history.retention.hour.days", historyRetentionHourDays.toString())
        props.setProperty("history.retention.day.days", historyRetentionDayDays.toString())
        props.setProperty("history.downsample.intervalMinutes", historyDownsampleIntervalMinutes.toString())
        configFile.outputStream().use { props.store(it, "InstantIoT Server Configuration") }
    }
}
