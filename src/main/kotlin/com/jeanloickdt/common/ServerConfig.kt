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

    /**
     * Nom d'affichage du serveur — visible dans l'app mDNS Discovery.
     * Permet à l'user qui a 2 serveurs sur le même LAN (Pi du salon,
     * Pi du garage…) de les distinguer dans la liste de l'app.
     *
     * Vide ou null → fallback HOSTNAME / "InstantIoT Server" au boot.
     * Restart requis pour appliquer (le mDNS est publié au boot).
     */
    var serverDisplayName: String = ""
        private set

    // ports réellement utilisés par le serveur au démarrage
    // (ne changent pas après un save — seulement après restart)
    var runningHttpPort: Int = 8080
        private set
    var runningTcpPort: Int = 9001
        private set

    /**
     * Appelé par Application.main après l'auto-bind ports — synchronise
     * les "running ports" avec ce qui est réellement bindé. Utilisé par
     * mDNS, tray, /api/status pour annoncer la bonne URL aux clients
     * même si on a fallback sur 8081/9002 etc.
     */
    fun markRunningPorts(http: Int, tcp: Int) {
        runningHttpPort = http
        runningTcpPort = tcp
    }

    // ============================================================
    // HISTORIQUE — Phase 1 (raw numeric)
    // ============================================================

    // ============================================================
    // BACKUP AUTOMATIQUE — V1
    // ============================================================

    /** Backup auto activé par défaut — protège le maker contre la perte
     *  de DB en cas de corruption SQLite, disque qui meurt, etc. */
    var backupEnabled: Boolean = true
        private set

    /** Intervalle entre 2 backups automatiques (heures). 24h = une fois
     *  par jour, low impact. Min 1h, pas de max (mais pas en dessous
     *  d'1h pour éviter les write storms sur SD cards Pi). */
    var backupIntervalHours: Int = 24
        private set

    /** Nombre de backups gardés. Au-delà, les plus vieux sont purgés
     *  par le cleanup post-snapshot. 30 = ~1 mois si daily. */
    var backupRetentionCount: Int = 30
        private set

    /** Répertoire des snapshots — sous-dossier de instantiotDir pour
     *  rester groupé avec licence.key, secret.key, instantiot.db. */
    val backupDir: File = File(instantiotDir, "backups").apply { mkdirs() }

    /** Sauvegarde la config backup. Hot-reload — pas de restart requis. */
    fun saveBackupConfig(
        enabled: Boolean? = null,
        intervalHours: Int? = null,
        retentionCount: Int? = null
    ) {
        if (enabled != null)        backupEnabled = enabled
        if (intervalHours != null)  backupIntervalHours = intervalHours.coerceAtLeast(1)
        if (retentionCount != null) backupRetentionCount = retentionCount.coerceAtLeast(1)
        writeProperties()
    }

    /**
     * Active le tier RAW (`widget_history_numeric`). Off par défaut :
     * les agrégateurs RAM (min/hour/day) suffisent à 90% des cas et
     * évitent un disque qui explose avec des capteurs rapides.
     *
     * Quand activé : chaque sample numérique est buffered et écrit
     * tel quel — pas de throttle, fidélité parfaite pour les fenêtres
     * 1h/6h en mode "live" prolongé.
     */
    var historyRawEnabled: Boolean = false
        private set

    /** Retention des rows RAW dans `widget_history_numeric` (jours). */
    var historyRetentionRawDays: Int = 7
        private set

    /** Retention des rows opaques dans `widget_history` (jours). */
    var historyRetentionOpaqueDays: Int = 1
        private set

    // ============================================================
    // HISTORIQUE — Tiers agrégés (alimentés en RAM, flush 5s)
    //
    // Architecture Blynk-style : les 3 tiers consomment directement
    // les samples bruts via les TierAggregator RAM, pas de cascade
    // SQL différée. Toujours à jour en temps réel (lag ≤ 5s).
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

            // Historique — refonte iWidgets (architecture Blynk-style)
            historyRawEnabled = props.getProperty("history.raw.enabled", "false")
                .toBooleanStrictOrNull() ?: false
            historyRetentionRawDays = props.getProperty("history.retention.raw.days", "7")
                .toIntOrNull()?.coerceAtLeast(1) ?: 7
            historyRetentionOpaqueDays = props.getProperty("history.retention.opaque.days", "1")
                .toIntOrNull()?.coerceAtLeast(1) ?: 1

            // Tiers agrégés
            historyRetentionMinDays = props.getProperty("history.retention.min.days", "90")
                .toIntOrNull()?.coerceAtLeast(1) ?: 90
            historyRetentionHourDays = props.getProperty("history.retention.hour.days", "365")
                .toIntOrNull()?.coerceAtLeast(1) ?: 365
            historyRetentionDayDays = props.getProperty("history.retention.day.days", "-1")
                .toIntOrNull() ?: -1
            serverDisplayName = props.getProperty("server.displayName", "").trim()
            backupEnabled = props.getProperty("backup.enabled", "true")
                .toBooleanStrictOrNull() ?: true
            backupIntervalHours = props.getProperty("backup.interval.hours", "24")
                .toIntOrNull()?.coerceAtLeast(1) ?: 24
            backupRetentionCount = props.getProperty("backup.retention.count", "30")
                .toIntOrNull()?.coerceAtLeast(1) ?: 30
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
    fun save(
        newHttpPort: Int? = null,
        newTcpPort: Int? = null,
        newServerDisplayName: String? = null
    ) {
        if (newHttpPort != null) httpPort = newHttpPort
        if (newTcpPort != null) tcpPort = newTcpPort
        if (newServerDisplayName != null) serverDisplayName = newServerDisplayName.trim()
        writeProperties()
    }

    /**
     * Sauvegarder les paramètres d'historique. Tout `null` est ignoré
     * (partial update). Pas de redémarrage nécessaire — les valeurs
     * sont relues à chaque cycle de cleanup / write par l'iter suivante.
     */
    fun saveHistoryConfig(
        rawEnabled: Boolean? = null,
        retentionRawDays: Int? = null,
        retentionOpaqueDays: Int? = null,
        retentionMinDays: Int? = null,
        retentionHourDays: Int? = null,
        retentionDayDays: Int? = null
    ) {
        if (rawEnabled != null)          historyRawEnabled          = rawEnabled
        if (retentionRawDays != null)    historyRetentionRawDays    = retentionRawDays.coerceAtLeast(1)
        if (retentionOpaqueDays != null) historyRetentionOpaqueDays = retentionOpaqueDays.coerceAtLeast(1)
        if (retentionMinDays != null)    historyRetentionMinDays    = retentionMinDays.coerceAtLeast(1)
        if (retentionHourDays != null)   historyRetentionHourDays   = retentionHourDays.coerceAtLeast(1)
        if (retentionDayDays != null)    historyRetentionDayDays    = retentionDayDays // -1 autorisé = infini
        writeProperties()
    }

    private fun writeProperties() {
        configFile.parentFile.mkdirs()
        val props = Properties()
        props.setProperty("http.port", httpPort.toString())
        props.setProperty("tcp.port", tcpPort.toString())
        props.setProperty("history.raw.enabled", historyRawEnabled.toString())
        props.setProperty("history.retention.raw.days", historyRetentionRawDays.toString())
        props.setProperty("history.retention.opaque.days", historyRetentionOpaqueDays.toString())
        props.setProperty("history.retention.min.days", historyRetentionMinDays.toString())
        props.setProperty("history.retention.hour.days", historyRetentionHourDays.toString())
        props.setProperty("history.retention.day.days", historyRetentionDayDays.toString())
        props.setProperty("server.displayName", serverDisplayName)
        props.setProperty("backup.enabled", backupEnabled.toString())
        props.setProperty("backup.interval.hours", backupIntervalHours.toString())
        props.setProperty("backup.retention.count", backupRetentionCount.toString())
        configFile.outputStream().use { props.store(it, "InstantIoT Server Configuration") }
    }
}
