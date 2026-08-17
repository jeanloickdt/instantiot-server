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
import com.jeanloickdt.widget.domain.WidgetHistoryAggregateRepository
import com.jeanloickdt.widget.domain.WidgetHistoryNumericRepository
import com.jeanloickdt.widget.domain.WidgetHistoryRepository
import com.jeanloickdt.widget.domain.WidgetRepository
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
 * After [purge] commits, no table holds a row keyed by this owner. The widget
 * deletes go through the cache-aware repository, so `knownWidgetIds` and the
 * last-value cache are evicted too — a frame from a still-flashed board hits
 * the strict-model guard and is dropped before any write.
 */
class AccountPurge(
    private val userRepository: UserRepository,
    private val projectRepository: ProjectRepository,
    private val deviceRepository: DeviceRepository,
    /** The CACHE-AWARE widget repository — eviction is part of the promise. */
    private val widgetRepository: WidgetRepository,
    private val widgetHistoryRepository: WidgetHistoryRepository,
    private val widgetHistoryNumericRepository: WidgetHistoryNumericRepository,
    private val widgetHistoryMinRepository: WidgetHistoryAggregateRepository,
    private val widgetHistoryHourRepository: WidgetHistoryAggregateRepository,
    private val widgetHistoryDayRepository: WidgetHistoryAggregateRepository,
    private val connections: ConnectionRegistry,
    private val events: ControlEventBroadcaster
) {

    suspend fun purge(ownerId: String): PurgeReport {
        val projects = projectRepository.findAllByOwner(ownerId)

        // ── Phase 1 : kicks — I/O, never rolled back ──────────────────────
        var devicesKicked = 0
        projects.forEach { project ->
            deviceRepository.findAllByProject(project.id).forEach { d ->
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
            projects.forEach { project ->
                widgetHistoryRepository.deleteAllByProject(project.id)
                widgetHistoryNumericRepository.deleteAllByProject(project.id)
                widgetHistoryMinRepository.deleteAllByProject(project.id)
                widgetHistoryHourRepository.deleteAllByProject(project.id)
                widgetHistoryDayRepository.deleteAllByProject(project.id)
                widgetRepository.deleteAllByProject(project.id)
                deviceRepository.deleteAllByProject(project.id)
                projectRepository.delete(project.id)
            }

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
            userRepository.delete(ownerId)
        }

        logger.info("Account purged — ownerId=$ownerId projects=${projects.size} devices=$devicesKicked")
        return PurgeReport(projects = projects.size, devices = devicesKicked)
    }
}
