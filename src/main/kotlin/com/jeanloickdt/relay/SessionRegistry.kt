// relay/SessionRegistry.kt
package com.jeanloickdt.relay

import com.jeanloickdt.device.domain.DeviceRow
import io.ktor.websocket.*
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

// Type aliases pour clarifier les clés des maps
typealias UserId    = String
typealias TokenHash = String
typealias WidgetId  = String

// Session app — une session WebSocket par app connectée
data class AppSession(
    val userId: UserId,
    val session: WebSocketSession,
    var activeProjectId: String? = null  // projet actuellement ouvert — change dynamiquement
)

// Session device — une session TCP par device connecté
data class DeviceSession(
    val device: DeviceRow,
    val socket: Socket
)

// Entry history — buffer avant flush SQLite
data class HistoryEntry(
    val widgetId: WidgetId,
    val projectId: String,
    val ownerId: String,
    val payload: String,
    val recordedAt: Long
)

object SessionRegistry {

    // userId → session app WebSocket
    val appSessions = ConcurrentHashMap<UserId, AppSession>()

    // tokenHash → session device TCP
    val deviceSessions = ConcurrentHashMap<TokenHash, DeviceSession>()

    // widgetId → dernier payload reçu — accès rapide sans DB
    val lastPayloads = ConcurrentHashMap<WidgetId, String>()

    // buffer history — flush toutes les 5s vers SQLite WAL batch
    val historyBuffer = ConcurrentLinkedQueue<HistoryEntry>()

    // enregistrer une session app
    fun registerApp(userId: UserId, session: WebSocketSession) {
        appSessions[userId] = AppSession(userId, session)
    }

    // changer le projet actif — quand user ouvre un projet
    fun setActiveProject(userId: UserId, projectId: String) {
        appSessions[userId]?.activeProjectId = projectId
    }

    // retirer une session app — déconnexion
    fun unregisterApp(userId: UserId) {
        appSessions.remove(userId)
    }

    // toutes les apps qui regardent un projet donné
    fun getAppSessionsForProject(projectId: String): List<AppSession> {
        return appSessions.values.filter { it.activeProjectId == projectId }
    }

    // enregistrer une session device
    fun registerDevice(tokenHash: TokenHash, device: DeviceRow, socket: Socket) {
        deviceSessions[tokenHash] = DeviceSession(device, socket)
    }

    // retirer une session device — déconnexion
    fun unregisterDevice(tokenHash: TokenHash) {
        deviceSessions.remove(tokenHash)
    }

    // trouver la session d'un device par son token hash
    fun getDeviceSession(tokenHash: TokenHash): DeviceSession? {
        return deviceSessions[tokenHash]
    }
}