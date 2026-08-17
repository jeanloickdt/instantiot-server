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

import com.jeanloickdt.automation.data.AutomationRuleTable
import com.jeanloickdt.automation.data.ScheduledJobTable
import com.jeanloickdt.event.EventSinks
import com.jeanloickdt.event.RelayEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SchedulerWorker")

/**
 * "7 h" arithmetic — pure, so every daylight-saving edge is provable with a
 * fixed clock and no sleep.
 */
object ScheduleMath {

    /**
     * The next UTC instant this schedule fires, strictly after [afterMs].
     *
     * Computed IN THE RULE'S ZONE: "07:00" means seven o'clock where the user
     * lives, whatever the server's locale, across DST changes. java.time
     * resolves the two DST edges the sane way — a time inside the spring-
     * forward gap shifts forward by the gap, a time repeated at fall-back
     * fires on the FIRST occurrence.
     */
    fun nextRunAfter(afterMs: Long, schedule: Trigger.Schedule): Long {
        val zone = schedule.zone
        val after = Instant.ofEpochMilli(afterMs).atZone(zone)
        val at = LocalTime.of(schedule.minuteOfDay / 60, schedule.minuteOfDay % 60)

        var day: LocalDate = after.toLocalDate()
        repeat(8) {   // 8 covers every day-mask, including "only Mondays"
            if (dayAllowed(day.dayOfWeek, schedule.days)) {
                val candidate = day.atTime(at).atZone(zone)   // DST-resolved here
                if (candidate.toInstant().toEpochMilli() > afterMs) {
                    return candidate.toInstant().toEpochMilli()
                }
            }
            day = day.plusDays(1)
        }
        error("unreachable: an allowed day exists within any 8-day window")
    }

    private fun dayAllowed(day: DayOfWeek, mask: Set<DayOfWeek>): Boolean =
        mask.isEmpty() || day in mask
}

/**
 * The fourth trigger — TIME. Polls the materialised next-fires (an indexed
 * range scan, étape 3's `idx_scheduled_due`; never a parse of every schedule
 * every ten seconds), publishes [RelayEvent.TimeReached] into the sinks, and
 * advances the row. The engine treats the event like any other; everything
 * downstream — actions, durability, delivery — already exists.
 *
 * ## The server was OFF at the appointed hour
 *
 * Skip and log, NEVER replay: watering replayed at 14 h because the server
 * rebooted does more damage than watering missed at 7 h. A due time older
 * than [MISSED_GRACE_MS] is recorded as missed and the row advances to the
 * next occurrence. Within the grace it fires normally — a 2-minute deploy
 * must not eat the morning watering.
 *
 * ## Idempotency
 *
 * The event carries `scheduledFor` — the WALL-CLOCK target, not the poll
 * time. The engine keys the enqueue on it, so a poll racing a restart cannot
 * double-fire one occurrence: the second INSERT dies on étape 3's index.
 */
class SchedulerWorker(
    private val sinks: EventSinks,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** One poll. Returns how many schedules fired (tests drive this directly). */
    fun pollOnce(): Int {
        val now = clock()
        var fired = 0

        data class Due(val ruleId: String, val ownerId: String, val enabled: Boolean, val definition: String, val dueAt: Long)

        val due = transaction {
            ScheduledJobTable
                .join(AutomationRuleTable, JoinType.INNER, ScheduledJobTable.ruleId, AutomationRuleTable.id)
                .selectAll()
                .where { ScheduledJobTable.nextRunAt lessEq now }
                .map {
                    Due(
                        ruleId     = it[ScheduledJobTable.ruleId],
                        ownerId    = it[AutomationRuleTable.ownerId],
                        enabled    = it[AutomationRuleTable.enabled],
                        definition = it[AutomationRuleTable.definition],
                        dueAt      = it[ScheduledJobTable.nextRunAt]
                    )
                }
        }

        due.forEach { job ->
            val schedule = RuleDefinition.parseOrNull(job.ruleId, job.definition)
                ?.trigger as? Trigger.Schedule
            if (schedule == null) {
                // The rule changed kind or broke under the job's feet — the
                // orphan row must not be re-polled every 10 s forever.
                transaction { ScheduledJobTable.deleteWhere { ScheduledJobTable.ruleId eq job.ruleId } }
                return@forEach
            }

            val late = now - job.dueAt
            when {
                !job.enabled -> Unit   // advance silently below — no event

                late > MISSED_GRACE_MS ->
                    // The server was off (or stuck) at the appointed hour.
                    logger.warn(
                        "Schedule ${job.ruleId} MISSED its ${job.dueAt} run by ${late / 1000}s — " +
                            "skipped, never replayed: a late watering does more damage than a missed one"
                    )

                else -> {
                    sinks.publish(
                        RelayEvent.TimeReached(
                            ownerId = job.ownerId,
                            ruleId = job.ruleId,
                            scheduledFor = job.dueAt,   // the fact time — keys the dedup
                            occurredAt = now
                        )
                    )
                    fired++
                }
            }

            // Advance from the DUE time, not from now: a poll 25 s late must
            // not shift tomorrow's 07:00 to 07:00:25 forever.
            val next = ScheduleMath.nextRunAfter(maxOf(job.dueAt, now - MISSED_GRACE_MS), schedule)
            transaction {
                ScheduledJobTable.update({ ScheduledJobTable.ruleId eq job.ruleId }) {
                    it[nextRunAt] = next
                }
            }
        }
        return fired
    }

    companion object {
        /** A short outage still fires; past this, the moment has passed. */
        const val MISSED_GRACE_MS = 5 * 60_000L
    }
}
