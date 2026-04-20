// relay/SessionRegistry.kt
package com.jeanloickdt.relay

import com.jeanloickdt.device.domain.DeviceRow
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

// Type aliases pour clarifier les clés des maps
typealias UserId   = String
typealias DeviceId = String
typealias WidgetId = String

// Session app — une session WebSocket par connexion app
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

    // userId → liste de sessions app WebSocket (multi-device : téléphone + tablette)
    val appSessions = ConcurrentHashMap<UserId, CopyOnWriteArrayList<AppSession>>()

    // deviceId → session device TCP
    val deviceSessions = ConcurrentHashMap<DeviceId, DeviceSession>()

    // deviceId → outbox de sérialisation des writes TCP (cf. DeviceOutbox)
    val deviceOutboxes = ConcurrentHashMap<DeviceId, DeviceOutbox>()

    // widgetId → dernier payload reçu — accès rapide sans DB
    val lastPayloads = ConcurrentHashMap<WidgetId, String>()

    // buffer history — flush toutes les 5s vers SQLite WAL batch
    val historyBuffer = ConcurrentLinkedQueue<HistoryEntry>()

    // enregistrer une session app — supporte plusieurs connexions par user
    fun registerApp(userId: UserId, session: WebSocketSession): AppSession {
        val appSession = AppSession(userId, session)
        appSessions.computeIfAbsent(userId) { CopyOnWriteArrayList() }.add(appSession)
        return appSession
    }

    // changer le projet actif d'une session spécifique
    fun setActiveProject(appSession: AppSession, projectId: String) {
        appSession.activeProjectId = projectId
    }

    // retirer une session app spécifique — par référence WebSocketSession
    fun unregisterApp(userId: UserId, session: WebSocketSession) {
        val sessions = appSessions[userId] ?: return
        sessions.removeIf { it.session === session }
        if (sessions.isEmpty()) {
            appSessions.remove(userId)
        }
    }

    // toutes les apps qui regardent un projet donné — itère toutes les sessions
    fun getAppSessionsForProject(projectId: String): List<AppSession> {
        return appSessions.values.flatMap { sessions ->
            sessions.filter { it.activeProjectId == projectId }
        }
    }

    // enregistrer une session device + créer son outbox.
    // Le `scope` fourni gouverne la coroutine consommatrice de l'outbox —
    // typiquement l'`applicationScope` Ktor (long-lived).
    fun registerDevice(deviceId: DeviceId, device: DeviceRow, socket: Socket, scope: CoroutineScope) {
        deviceSessions[deviceId] = DeviceSession(device, socket)
        // Fermer une éventuelle outbox précédente (reconnect rapide du même deviceId)
        // pour éviter une fuite de coroutine consommatrice.
        deviceOutboxes.remove(deviceId)?.close()
        deviceOutboxes[deviceId] = DeviceOutbox(
            deviceId = deviceId,
            socket   = socket,
            scope    = scope
        )
    }

    // retirer une session device — déconnexion. Ferme l'outbox (→ la
    // coroutine consommatrice sort proprement) et libère les réfs.
    fun unregisterDevice(deviceId: DeviceId) {
        deviceSessions.remove(deviceId)
        deviceOutboxes.remove(deviceId)?.close()
    }

    // trouver la session d'un device par son ID
    fun getDeviceSession(deviceId: DeviceId): DeviceSession? {
        return deviceSessions[deviceId]
    }
}
