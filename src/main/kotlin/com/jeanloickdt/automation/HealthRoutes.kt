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

package com.jeanloickdt.automation

import com.jeanloickdt.auth.domain.UserRepository
import com.jeanloickdt.auth.requireAdmin
import com.jeanloickdt.event.EventSinks
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AutomationHealth")

/**
 * One read, the whole subsystem's health. The number that decides everything
 * is [oldestPendingAgeMs] — the AGE of the oldest undelivered action, not the
 * queue length: ten 3-second-old rows mean the worker is keeping up; ONE
 * ten-minute-old row means something is broken, however short the queue.
 */
@Serializable
data class AutomationHealth(
    /** `null` = queue empty. Past [AutomationHealthWatch.MAX_PENDING_AGE_MS], nobody was warned. */
    val oldestPendingAgeMs: Long?,
    val pendingCount: Long,
    /** Every DEAD row is an alert somebody paid for and did not get. */
    val deadCount: Long,
    /** Lossy-by-design channel — context, not alarm. */
    val droppedValueEvents: Long,
    /** NOT lossy by design — any non-zero here means the engine was stuck. */
    val droppedDiscreteEvents: Long,
    /** Refused events past the depth guard — a rule chain was looping. */
    val depthRefusedEvents: Long
)

/**
 * Why this exists before a single alert is sold: today a stuck delivery queue
 * is SILENT. While the product is dashboards, an outage means "my chart lags"
 * — visible, the user sees it themselves. The day alerts are sold, the same
 * outage means "the freezer thawed and nobody was told" — invisible by
 * definition, because the failure IS the absence of a signal. The only party
 * that can see it is the server itself. This endpoint and its watch loop are
 * how it does.
 */
fun Route.automationHealthRoutes(
    userRepository: UserRepository,
    actions: PendingActionRepository,
    sinks: EventSinks,
    engine: AutomationEngine,
    clock: () -> Long = System::currentTimeMillis
) {
    authenticate("jwt") {
        get("/api/admin/automation/health") {
            call.requireAdmin(userRepository) ?: return@get
            call.respond(HttpStatusCode.OK, snapshot(actions, sinks, engine, clock()))
        }
    }
}

fun snapshot(
    actions: PendingActionRepository,
    sinks: EventSinks,
    engine: AutomationEngine,
    nowMs: Long
): AutomationHealth = AutomationHealth(
    oldestPendingAgeMs    = actions.oldestPendingAgeMs(nowMs),
    pendingCount          = actions.pendingCount(),
    deadCount             = actions.deadCount(),
    droppedValueEvents    = sinks.droppedValueCount,
    droppedDiscreteEvents = sinks.droppedDiscreteCount,
    depthRefusedEvents    = engine.depthRefusedCount
)

/**
 * The periodic check the watch loop runs. Pure over its inputs so the
 * threshold logic is testable without a clock or a logger — the loop feeds
 * it and logs what it returns.
 */
class AutomationHealthWatch {
    private var lastDeadCount = 0L

    /** Warnings to log this round — empty when all is well. */
    fun check(health: AutomationHealth): List<String> = buildList {
        val age = health.oldestPendingAgeMs
        if (age != null && age > MAX_PENDING_AGE_MS) {
            add(
                "Delivery is FALLING BEHIND — oldest PENDING action is ${age / 1000}s old " +
                    "(${health.pendingCount} waiting). If alerts are being sold, nobody is being warned."
            )
        }
        if (health.deadCount > lastDeadCount) {
            add(
                "${health.deadCount - lastDeadCount} action(s) went DEAD (total ${health.deadCount}) — " +
                    "each one is an alert somebody paid for and did not get."
            )
        }
        lastDeadCount = health.deadCount
        if (health.droppedDiscreteEvents > 0) {
            add(
                "${health.droppedDiscreteEvents} DISCRETE event(s) were dropped — " +
                    "that channel is not lossy by design: the engine was stuck."
            )
        }
    }

    fun logAll(warnings: List<String>) = warnings.forEach { logger.warn(it) }

    companion object {
        /** The work order's threshold: past 60 s, the queue is not late, it is stuck. */
        const val MAX_PENDING_AGE_MS = 60_000L
    }
}
