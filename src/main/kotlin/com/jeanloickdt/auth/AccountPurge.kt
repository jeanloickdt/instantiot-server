/*
 * InstantIoT Server — self-hosted IoT relay for makers.
 * Copyright (C) 2026 Djoufack Tsobeng Jean Loick (InstantIoT)
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

package com.jeanloickdt.auth

import com.jeanloickdt.auth.domain.UserRepository
import com.jeanloickdt.automation.data.AutomationRuleTable
import com.jeanloickdt.automation.data.AutomationStateTable
import com.jeanloickdt.automation.data.MessageUsageTable
import com.jeanloickdt.automation.data.PendingActionTable
import com.jeanloickdt.automation.data.PushTokenTable
import com.jeanloickdt.automation.data.ScheduledJobTable
import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.project.domain.ProjectRepository
import com.jeanloickdt.relay.ConnectionRegistry
import com.jeanloickdt.relay.ControlEventBroadcaster
import com.jeanloickdt.relay.DeviceOfflineReason
import com.jeanloickdt.signal.data.ExposedSignalHistoryRepository
import com.jeanloickdt.signal.domain.SignalRepository
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AccountPurge")

/** What was erased — returned to the caller, logged for the audit trail. */
@Serializable
data class PurgeReport(val projects: Int, val devices: Int)

/**
 * Erases EVERYTHING an account owns. The one place that knows the full list —
 * so that when a new per-owner table appears, there is exactly one file to
 * update, and its test to extend.
 *
 * ## Shape
 *
 * The same two-phase shape as the project cascade it generalises
 * (`ProjectRoutes` DELETE):
 *
 *  1. **Kicks first, outside the transaction** — socket and WS closes are I/O
 *     effects and must not be rolled back. The device sockets are closed (the
 *     connection's own finally then cleans the registry — safe since the
 *     ownership check), and every app session of the owner is closed with an
 *     explicit reason so the client shows "account deleted", not a reconnect
 *     spinner.
 *  2. **One transaction for every row** — per-project cascades (history ×5 →
 *     widgets → devices → project), then the owner-level tables (usage ledger,
 *     rules, their state and schedules, pending actions, push tokens), then
 *     the users row LAST: if anything fails, the account still exists and the
 *     deletion can be retried. The reverse order could leave orphan data with
 *     no owner able to retry — the exact opposite of what deletion promises.
 *
 * ## What deletion promises
 *
 * After [purge] commits, no table holds a row keyed by this owner — la
 * declaration des signaux comme leurs quatre paliers.
 *
 * Une carte encore flashee qui continue d'emettre ne peut donc plus rien
 * ecrire : son adresse n'est plus declaree, et le relais refuse une adresse
 * inconnue avant toute ecriture. Le cache RAM des dernieres valeurs n'est pas
 * purge explicitement — il ne survit pas au redemarrage et rien ne le relit
 * pour un compte disparu.
 */
class AccountPurge(
    private val userRepository: UserRepository,
    private val projectRepository: ProjectRepository,
    private val deviceRepository: DeviceRepository,
    /** Le dépôt CACHÉ — l'éviction du cache fait partie de la promesse. */
    private val signalRepository: SignalRepository,
    private val signalHistoryRepository: ExposedSignalHistoryRepository,
    private val connections: ConnectionRegistry,
    private val events: ControlEventBroadcaster
) {

    /**
     * @param deleteUserRow false = leave the users row in place — the CLOUD
     *   deletion flow anonymises and tombstones it instead, because a
     *   stateless iia JWT lives up to 7 days and the JIT provisioning would
     *   happily re-create a deleted account from a still-valid token. The
     *   local flow always deletes: its validation checks the row, so removal
     *   IS the revocation.
     */
    suspend fun purge(ownerId: String, deleteUserRow: Boolean = true): PurgeReport {
        val projects = projectRepository.findAllByOwner(ownerId)

        // ── Phase 1 : kicks — I/O, never rolled back ──────────────────────
        var devicesKicked = 0
        projects.forEach { project ->
            deviceRepository.findAllByProject(ownerId, project.id).forEach { d ->
                devicesKicked++
                events.deviceOffline(project.id, d.id, DeviceOfflineReason.DELETED)
                connections.getDeviceSession(d.id)?.let { active ->
                    runCatching { active.socket.close() }
                    // the connection's finally cleans the registry — and the
                    // ownership check makes that safe against races
                }
            }
        }
        connections.appSessions[ownerId]?.toList()?.forEach { appSession ->
            runCatching {
                appSession.session.close(CloseReason(CloseReason.Codes.NORMAL, "Account deleted"))
            }
        }

        // ── Phase 2 : every row, one transaction ──────────────────────────
        transaction {
            // L'ORDRE est une contrainte, pas une préférence : chaque table
            // d'historique porte une clé étrangère vers `signals.id`, et
            // chaque signal une vers son device. Supprimer en sens inverse
            // ferait échouer la contrainte et avorter toute la transaction.
            //
            // L'historique se purge par COMPTE, pas par projet : ses lignes
            // ne portent pas de `project_id` — le modèle cible l'a retiré,
            // `owner_id` dénormalisé suffit à purger sans jointure.
            signalHistoryRepository.deleteAllByOwner(ownerId)
            signalRepository.deleteByOwner(ownerId)
            // Par COMPTE, pas projet par projet. La version d'avant faisait
            // deux instructions par projet ; la suppression d'un compte tient
            // desormais en un nombre FIXE d'instructions, quel que soit ce que
            // le compte possede. Dans une transaction qui tient des verrous,
            // c'est la propriete qui compte.
            deviceRepository.deleteAllByOwner(ownerId)
            projectRepository.deleteAllByOwner(ownerId)

            // Owner-level tables. automation_state and scheduled_jobs are keyed
            // by rule, not owner — resolve the rule ids first, inside the same
            // transaction.
            val ruleIds = AutomationRuleTable.selectAll()
                .where { AutomationRuleTable.ownerId eq ownerId }
                .map { it[AutomationRuleTable.id] }
            if (ruleIds.isNotEmpty()) {
                AutomationStateTable.deleteWhere { ruleId inList ruleIds }
                ScheduledJobTable.deleteWhere { ruleId inList ruleIds }
            }
            AutomationRuleTable.deleteWhere { AutomationRuleTable.ownerId eq ownerId }
            PendingActionTable.deleteWhere { PendingActionTable.ownerId eq ownerId }
            PushTokenTable.deleteWhere { PushTokenTable.ownerId eq ownerId }
            MessageUsageTable.deleteWhere { MessageUsageTable.ownerId eq ownerId }

            // The users row LAST — as long as it exists, the deletion is
            // retryable by its owner.
            if (deleteUserRow) userRepository.delete(ownerId)
        }

        logger.info("Account purged — ownerId=$ownerId projects=${projects.size} devices=$devicesKicked")
        return PurgeReport(projects = projects.size, devices = devicesKicked)
    }
}
