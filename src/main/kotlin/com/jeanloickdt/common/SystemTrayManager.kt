package com.jeanloickdt.common

import org.slf4j.LoggerFactory
import java.awt.*
import java.awt.image.BufferedImage
import java.net.URI

/**
 * System Tray — icone dans la barre des taches.
 *
 * Affiche une icone avec un menu pour controler le serveur :
 *   - Open Admin Panel → ouvre le navigateur
 *   - Restart Server → arret propre (process manager relance)
 *   - Quit → arret definitif
 *
 * Si le system tray n'est pas supporte (serveur headless, VPS),
 * le serveur tourne en mode console sans GUI.
 */
object SystemTrayManager {

    private val logger = LoggerFactory.getLogger("SystemTray")
    private var trayIcon: TrayIcon? = null

    /**
     * Initialiser le system tray.
     * Appele dans main() AVANT embeddedServer.start().
     * Retourne true si le tray est actif, false si fallback console.
     */
    fun init(httpPort: Int): Boolean {
        // ⚠️ catch(Throwable) — PAS catch(Exception). Sur Linux headless
        // avec un DISPLAY cassé/partiel, `SystemTray.isSupported()` lui-même
        // (init du Toolkit AWT) lève `java.awt.AWTError: Can't connect to
        // X11 window server` qui est un `Error`, pas une `Exception`. Le
        // serveur tourne typiquement headless (Pi OS Lite, Ubuntu Server,
        // VPS, systemd) → ce chemin DOIT être incassable. Tout échec tray
        // = fallback console silencieux, jamais un crash boot.
        return try {
            if (!SystemTray.isSupported()) {
                logger.info("System tray not supported — running in console mode")
                return false
            }

            // icone generee programmatiquement — pas de fichier externe
            val icon = createIcon()
            val popup = createMenu(httpPort)

            trayIcon = TrayIcon(icon, "InstantIoT Server", popup).apply {
                isImageAutoSize = true
                toolTip = "InstantIoT Server — Running on port $httpPort"
            }

            SystemTray.getSystemTray().add(trayIcon)
            logger.info("System tray initialized — port $httpPort")

            // ouvrir le navigateur automatiquement au premier lancement
            openBrowser(httpPort)

            // notification systeme post-boot — utile sur Pi headless
            // ou serveur lancé via systemd où l'user ne voit pas la
            // console. Cliquer dessus ouvre aussi l'admin panel.
            trayIcon?.displayMessage(
                "InstantIoT Server",
                "Server ready on http://localhost:$httpPort\nClick the tray icon to manage.",
                TrayIcon.MessageType.INFO
            )

            true
        } catch (t: Throwable) {
            logger.info("System tray unavailable (headless?) — console mode: ${t.message}")
            false
        }
    }

    /**
     * Creer le menu du tray.
     */
    private fun createMenu(httpPort: Int): PopupMenu {
        val popup = PopupMenu()

        // titre
        val titleItem = MenuItem("InstantIoT Server v${ServerConfig.version}")
        titleItem.isEnabled = false
        popup.add(titleItem)

        // status
        val statusItem = MenuItem("Running on port $httpPort")
        statusItem.isEnabled = false
        popup.add(statusItem)

        popup.addSeparator()

        // ouvrir le dashboard
        val openItem = MenuItem("Open Admin Panel")
        openItem.addActionListener { openBrowser(httpPort) }
        popup.add(openItem)

        // afficher les infos serveur (IP, ports, version) en notif système
        val infoItem = MenuItem("Show Server Info")
        infoItem.addActionListener { showServerInfo(httpPort) }
        popup.add(infoItem)

        popup.addSeparator()

        // restart — `Restart=always` côté systemd / process manager re-lance
        val restartItem = MenuItem("Restart Server")
        restartItem.addActionListener {
            logger.info("Restart requested from system tray")
            System.exit(0)
        }
        popup.add(restartItem)

        // quit
        val quitItem = MenuItem("Quit")
        quitItem.addActionListener {
            logger.info("Shutdown requested from system tray")
            System.exit(0)
        }
        popup.add(quitItem)

        return popup
    }

    /**
     * Affiche les infos serveur en notification système.
     * Inclut l'IP locale (utile pour configurer un device ESP),
     * les ports HTTP+TCP, la version, et l'état de la licence.
     */
    private fun showServerInfo(httpPort: Int) {
        val ip = detectLocalIp()
        val tcpPort = ServerConfig.tcpPort
        val version = ServerConfig.version
        val licenceStatus = if (com.jeanloickdt.auth.LicenceValidator.isActivated())
            "activated" else "not activated"

        val text = buildString {
            append("Local IP: $ip\n")
            append("HTTP: $httpPort  •  TCP: $tcpPort\n")
            append("Version: $version  •  Licence: $licenceStatus")
        }

        trayIcon?.displayMessage(
            "InstantIoT Server",
            text,
            TrayIcon.MessageType.INFO
        )
        logger.info("Server info shown via tray notification")
    }

    /**
     * Détecte l'IP locale via [ServerConfig.localIp] qui applique le
     * scoring intelligent (filtre VPN/virtuels, privilège Wi-Fi/Ethernet).
     */
    private fun detectLocalIp(): String =
        ServerConfig.localIp.takeUnless { it == "unknown" || it.isBlank() } ?: "localhost"

    /**
     * Generer une icone 16x16 programmatiquement.
     * Carre avec gradient violet → teal (brand InstantIoT).
     */
    private fun createIcon(): Image {
        val size = 16
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()

        // gradient brand : violet (#6C63FF) → teal (#4ECDC4)
        g.paint = GradientPaint(
            0f, 0f, Color(0x6C, 0x63, 0xFF),
            size.toFloat(), size.toFloat(), Color(0x4E, 0xCD, 0xC4)
        )
        g.fillRoundRect(0, 0, size, size, 4, 4)

        // lettre "I" au centre en blanc
        g.color = Color.WHITE
        g.font = Font("SansSerif", Font.BOLD, 11)
        val fm = g.fontMetrics
        val x = (size - fm.stringWidth("I")) / 2
        val y = (size + fm.ascent - fm.descent) / 2
        g.drawString("I", x, y)

        g.dispose()
        return img
    }

    /**
     * Ouvrir le navigateur sur le dashboard admin.
     */
    private fun openBrowser(httpPort: Int) {
        try {
            val url = "http://localhost:$httpPort"
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                logger.info("Browser opened: $url")
            } else {
                // fallback par OS
                val os = System.getProperty("os.name").lowercase()
                when {
                    os.contains("mac") -> Runtime.getRuntime().exec(arrayOf("open", url))
                    os.contains("win") -> Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", url))
                    else -> Runtime.getRuntime().exec(arrayOf("xdg-open", url))
                }
                logger.info("Browser opened: $url")
            }
        } catch (e: Exception) {
            logger.info("Open http://localhost:$httpPort in your browser")
        }
    }
}
