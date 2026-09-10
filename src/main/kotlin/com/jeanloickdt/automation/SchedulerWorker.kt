package com.jeanloickdt.automation

import com.jeanloickdt.automation.data.AutomationRuleTable
import com.jeanloickdt.automation.v2.RuleCodec
import com.jeanloickdt.automation.v2.Trigger
import com.jeanloickdt.automation.data.ScheduledJobTable
import com.jeanloickdt.event.EventSinks
import com.jeanloickdt.event.RelayEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
    /**
     * Le fuseau vient de la REGLE, plus du declencheur.
     *
     * En v1 chaque `Schedule` portait le sien ; en v2 il n'y en a qu'un par
     * regle, et une feuille qui en porte un est refusee (`tz-in-leaf`). Deux
     * fuseaux dans une meme regle rendraient legale une regle programmee a
     * Toronto qui teste les heures de Teheran.
     */
    fun nextRunAfter(afterMs: Long, schedule: Trigger.Schedule, zone: ZoneId): Long {
        val after = Instant.ofEpochMilli(afterMs).atZone(zone)
        val at = LocalTime.of(schedule.minuteOfDay / 60, schedule.minuteOfDay % 60)
        val mask = schedule.days.map { DAYS.getValue(it) }.toSet()

        var day: LocalDate = after.toLocalDate()
        repeat(8) {   // 8 covers every day-mask, including "only Mondays"
            if (dayAllowed(day.dayOfWeek, mask)) {
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

    /** Le vocabulaire du fil vers celui de java.time — une seule table. */
    private val DAYS = mapOf(
        Trigger.Day.MON to DayOfWeek.MONDAY, Trigger.Day.TUE to DayOfWeek.TUESDAY,
        Trigger.Day.WED to DayOfWeek.WEDNESDAY, Trigger.Day.THU to DayOfWeek.THURSDAY,
        Trigger.Day.FRI to DayOfWeek.FRIDAY, Trigger.Day.SAT to DayOfWeek.SATURDAY,
        Trigger.Day.SUN to DayOfWeek.SUNDAY
    )
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

        data class Due(
            val ruleId: String, val ownerId: String, val enabled: Boolean,
            val definition: String, val timeZoneId: String,
            val invalidReason: String?, val dueAt: Long
        )

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
                        timeZoneId = it[AutomationRuleTable.timeZoneId],
                        invalidReason = it[AutomationRuleTable.invalidReason],
                        dueAt      = it[ScheduledJobTable.nextRunAt]
                    )
                }
        }

        due.forEach { job ->
            val schedule = (RuleCodec.decode(job.definition) as? RuleCodec.Outcome.Ok)
                ?.logic?.trigger as? Trigger.Schedule
            if (schedule == null) {
                // The rule changed kind or broke under the job's feet — the
                // orphan row must not be re-polled every 10 s forever.
                transaction { ScheduledJobTable.deleteWhere { ScheduledJobTable.ruleId eq job.ruleId } }
                return@forEach
            }
            val zone = runCatching { ZoneId.of(job.timeZoneId) }.getOrDefault(ZoneId.of("UTC"))

            val late = now - job.dueAt
            when {
                // Une regle eteinte ou invalide avance en silence : sa ligne
                // doit rester en phase avec l'horloge pour que la reactivation
                // reprenne a la bonne occurrence, mais rien ne se publie.
                !job.enabled || job.invalidReason != null -> Unit

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
            val next = ScheduleMath.nextRunAfter(maxOf(job.dueAt, now - MISSED_GRACE_MS), schedule, zone)
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
