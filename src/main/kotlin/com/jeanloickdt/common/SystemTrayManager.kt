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

package com.jeanloickdt.common

import org.slf4j.LoggerFactory
import java.awt.*
import java.awt.image.BufferedImage
import java.net.URI

/**
 * System Tray — icon in the taskbar.
 *
 * Shows an icon with a menu to control the server:
 *   - Open Admin Panel → opens the browser
 *   - Restart Server → clean shutdown (process manager restarts it)
 *   - Quit → permanent shutdown
 *
 * If the system tray is not supported (headless server, VPS),
 * the server runs in console mode without a GUI.
 */
object SystemTrayManager {

    private val logger = LoggerFactory.getLogger("SystemTray")
    private var trayIcon: TrayIcon? = null

    /**
     * Initialize the system tray.
     * Called in main() BEFORE embeddedServer.start().
     * Returns true if the tray is active, false if falling back to console.
     */
    fun init(httpPort: Int): Boolean {
        // ⚠️ catch(Throwable) — NOT catch(Exception). On headless Linux
        // with a broken/partial DISPLAY, `SystemTray.isSupported()` itself
        // (AWT Toolkit init) throws `java.awt.AWTError: Can't connect to
        // X11 window server` which is an `Error`, not an `Exception`. The
        // server typically runs headless (Pi OS Lite, Ubuntu Server,
        // VPS, systemd) → this path MUST be unbreakable. Any tray failure
        // = silent console fallback, never a boot crash.
        return try {
            if (!SystemTray.isSupported()) {
                logger.info("System tray not supported — running in console mode")
                return false
            }

            // icon generated programmatically — no external file
            val icon = createIcon()
            val popup = createMenu(httpPort)

            trayIcon = TrayIcon(icon, "InstantIoT Server", popup).apply {
                isImageAutoSize = true
                toolTip = "InstantIoT Server — Running on port $httpPort"
            }

            SystemTray.getSystemTray().add(trayIcon)
            logger.info("System tray initialized — port $httpPort")

            // open the browser automatically on first launch
            openBrowser(httpPort)

            // post-boot system notification — useful on a headless Pi
            // or a server started via systemd where the user can't see
            // the console. Clicking it also opens the admin panel.
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
     * Create the tray menu.
     */
    private fun createMenu(httpPort: Int): PopupMenu {
        val popup = PopupMenu()

        // title
        val titleItem = MenuItem("InstantIoT Server v${ServerConfig.version}")
        titleItem.isEnabled = false
        popup.add(titleItem)

        // status
        val statusItem = MenuItem("Running on port $httpPort")
        statusItem.isEnabled = false
        popup.add(statusItem)

        popup.addSeparator()

        // open the dashboard
        val openItem = MenuItem("Open Admin Panel")
        openItem.addActionListener { openBrowser(httpPort) }
        popup.add(openItem)

        // show server info (IP, ports, version) as a system notification
        val infoItem = MenuItem("Show Server Info")
        infoItem.addActionListener { showServerInfo(httpPort) }
        popup.add(infoItem)

        popup.addSeparator()

        // restart — `Restart=always` on the systemd / process manager side restarts it
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
     * Shows server info as a system notification.
     * Includes the local IP (useful to configure an ESP device),
     * the HTTP+TCP ports and the version.
     */
    private fun showServerInfo(httpPort: Int) {
        val ip = detectLocalIp()
        val tcpPort = ServerConfig.tcpPort
        val version = ServerConfig.version

        val text = buildString {
            append("Local IP: $ip\n")
            append("HTTP: $httpPort  •  TCP: $tcpPort\n")
            append("Version: $version")
        }

        trayIcon?.displayMessage(
            "InstantIoT Server",
            text,
            TrayIcon.MessageType.INFO
        )
        logger.info("Server info shown via tray notification")
    }

    /**
     * Detects the local IP via [ServerConfig.localIp] which applies the
     * smart scoring (filters VPN/virtual interfaces, prefers Wi-Fi/Ethernet).
     */
    private fun detectLocalIp(): String =
        ServerConfig.localIp.takeUnless { it == "unknown" || it.isBlank() } ?: "localhost"

    /**
     * Generate a 16x16 icon programmatically.
     * Square with a purple → teal gradient (InstantIoT brand).
     */
    private fun createIcon(): Image {
        val size = 16
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()

        // brand gradient: purple (#6C63FF) → teal (#4ECDC4)
        g.paint = GradientPaint(
            0f, 0f, Color(0x6C, 0x63, 0xFF),
            size.toFloat(), size.toFloat(), Color(0x4E, 0xCD, 0xC4)
        )
        g.fillRoundRect(0, 0, size, size, 4, 4)

        // letter "I" centered in white
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
     * Open the browser on the admin dashboard.
     */
    private fun openBrowser(httpPort: Int) {
        try {
            val url = "http://localhost:$httpPort"
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                logger.info("Browser opened: $url")
            } else {
                // per-OS fallback
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