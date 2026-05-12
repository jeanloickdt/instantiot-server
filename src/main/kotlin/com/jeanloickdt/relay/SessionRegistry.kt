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
    var activeProjectId: String? = null,  // projet actuellement ouvert — change dynamiquement
    // Subscriptions historique : map widgetId → granularité ("minute" | "hour" | "day").
    // Set par l'app via message {"type":"subscribe_history","widgets":[...]}. Filtré
    // par le broadcaster bucket_updated pour ne diffuser que les buckets que l'app
    // a explicitement demandés (= charts en mode preset avec source FromWidget +
    // bottom sheets d'historique ouverts). Évite le gaspillage réseau quand des
    // widgets n'ont aucun chart actif côté UI.
    //
    // Thread-safe : Map mutable accédée depuis le read loop (write) et le bucket
    // broadcaster (read). Garde-fou : on encapsule dans synchronized blocks ou on
    // utilise ConcurrentHashMap. Choix : ConcurrentHashMap pour rester simple.
    val historySubs: java.util.concurrent.ConcurrentHashMap<String, String> = java.util.concurrent.ConcurrentHashMap()
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

// Entry history **numérique** — buffer avant flush SQLite
// Populée en parallèle de HistoryEntry quand FrameParser.extractNumericValue
// retourne un échantillon décodable (gauge/metric/level/slider/chart).
data class NumericHistoryEntry(
    val widgetId: WidgetId,
    val projectId: String,
    val ownerId: String,
    val seriesId: String?,
    val value: Double,
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

    // buffer history numérique — alimenté SEULEMENT si l'admin a activé
    // le tier raw (ServerConfig.historyRawEnabled). Flush 5s vers
    // widget_history_numeric.
    //
    // Plus de throttle : depuis la refonte historique iWidgets, on
    // privilégie une architecture Blynk-style où les tiers min/hour/day
    // (toujours actifs via les agrégateurs RAM) suffisent pour
    // visualiser l'enveloppe du signal. Le tier raw, quand activé,
    // garde TOUS les samples sans filtre serveur — la protection
    // contre l'abus côté sketch est documentée, pas imposée.
    val numericHistoryBuffer = ConcurrentLinkedQueue<NumericHistoryEntry>()

    // Cache RAM des widgetId (= protocolId) déjà connus en DB.
    // Utilisé par l'auto-register dans DeviceRelay : un widgetId déjà dans
    // le Set → pas de DB hit, sinon INSERT OR IGNORE + ajout au Set.
    // Peuplé au démarrage via `seedKnownWidgets()` + au fur et à mesure.
    val knownWidgetIds: MutableSet<WidgetId> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
