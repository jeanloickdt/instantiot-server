package com.jeanloickdt.common

import java.io.File
import java.net.InetAddress
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

    private val configFile = File("${System.getProperty("user.home")}/.instantiot/server.properties")

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

    val version: String get() = VERSION
    val startedAt: Long = System.currentTimeMillis()

    val uptimeMs: Long get() = System.currentTimeMillis() - startedAt

    val localIp: String get() = try {
        InetAddress.getLocalHost().hostAddress
    } catch (_: Exception) {
        "unknown"
    }

    val dbSizeBytes: Long get() = try {
        File("./instantiot.db").length()
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

        configFile.parentFile.mkdirs()
        val props = Properties()
        props.setProperty("http.port", httpPort.toString())
        props.setProperty("tcp.port", tcpPort.toString())
        configFile.outputStream().use { props.store(it, "InstantIoT Server Configuration") }
    }
}
