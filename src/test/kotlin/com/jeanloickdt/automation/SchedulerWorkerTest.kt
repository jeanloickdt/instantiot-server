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

import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.deviceRepository
import com.jeanloickdt.event.EventSinks
import com.jeanloickdt.event.RelayEvent
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.widget.data.WidgetHistoryDayTable
import com.jeanloickdt.widget.data.WidgetHistoryHourTable
import com.jeanloickdt.widget.data.WidgetHistoryMinTable
import com.jeanloickdt.widget.data.WidgetHistoryNumericTable
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import java.io.File
import java.sql.DriverManager
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TORONTO = ZoneId.of("America/Toronto")

/**
 * "7 h" means 7 h where the user lives — across daylight saving, server
 * reboots and late polls. All of it provable with a fixed clock, because the
 * arithmetic is pure and the poll is drivable by hand.
 */
class SchedulerWorkerTest {

    private lateinit var dbFile: File
    private lateinit var sinks: EventSinks
    private var now = 0L

    private fun schedule(at: String = "07:00", days: Set<DayOfWeek> = emptySet(), tz: ZoneId = TORONTO) =
        Trigger.Schedule(
            minuteOfDay = at.substringBefore(':').toInt() * 60 + at.substringAfter(':').toInt(),
            days = days, zone = tz
        )

    private fun msOf(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = TORONTO): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("instantiot-sched-", ".db").apply { deleteOnExit() }
        DatabaseFactory.init(
            UserTable, ProjectTable, DeviceTable, WidgetTable,
            WidgetHistoryTable, WidgetHistoryNumericTable,
            WidgetHistoryMinTable, WidgetHistoryHourTable, WidgetHistoryDayTable,
            *AutomationTables.ALL,
            dbFile = dbFile
        )
        sinks = EventSinks()
    }

    // ── L'arithmétique — pure ─────────────────────────────────────────────

    @Test
    fun `seven means seven in the rule's zone, tomorrow when today's has passed`() {
        val afterNoon = msOf(2026, 6, 10, 12, 0)
        assertEquals(msOf(2026, 6, 11, 7, 0), ScheduleMath.nextRunAfter(afterNoon, schedule()))

        val beforeDawn = msOf(2026, 6, 10, 5, 0)
        assertEquals(msOf(2026, 6, 10, 7, 0), ScheduleMath.nextRunAfter(beforeDawn, schedule()))
    }

    @Test
    fun `a day mask skips to the next allowed day`() {
        // 2026-06-10 is a Wednesday; Mondays only → 2026-06-15.
        val wednesday = msOf(2026, 6, 10, 12, 0)
        assertEquals(
            msOf(2026, 6, 15, 7, 0),
            ScheduleMath.nextRunAfter(wednesday, schedule(days = setOf(DayOfWeek.MONDAY)))
        )
    }

    @Test
    fun `spring forward — a schedule inside the gap shifts, never vanishes`() {
        // 2026-03-08, America/Toronto: 02:00→03:00 does not exist. A 02:30
        // schedule must still fire that day (java.time shifts it into 03:30),
        // not silently skip to tomorrow.
        val beforeGap = msOf(2026, 3, 8, 1, 0)
        val next = ScheduleMath.nextRunAfter(beforeGap, schedule(at = "02:30"))
        val local = java.time.Instant.ofEpochMilli(next).atZone(TORONTO)
        assertEquals(8, local.dayOfMonth, "the run must stay on DST day, shifted — not skipped")
        assertEquals(3, local.hour)
    }

    @Test
    fun `fall back — the repeated hour fires once, on the first occurrence`() {
        // 2026-11-01, America/Toronto: 01:30 happens twice. One run, the first.
        val beforeMidnight = msOf(2026, 11, 1, 0, 0)
        val next = ScheduleMath.nextRunAfter(beforeMidnight, schedule(at = "01:30"))
        val following = ScheduleMath.nextRunAfter(next, schedule(at = "01:30"))
        val followingLocal = java.time.Instant.ofEpochMilli(following).atZone(TORONTO)
        assertEquals(2, followingLocal.dayOfMonth,
            "after the first 01:30, the NEXT run is tomorrow — the repeated hour must not double-fire")
    }

    @Test
    fun `the hour is the user's, not the server's`() {
        val paris = schedule(at = "07:00", tz = ZoneId.of("Europe/Paris"))
        val toronto = schedule(at = "07:00", tz = TORONTO)
        val after = msOf(2026, 6, 10, 0, 0, ZoneId.of("UTC"))
        val parisRun = ScheduleMath.nextRunAfter(after, paris)
        val torontoRun = ScheduleMath.nextRunAfter(after, toronto)
        assertEquals(6 * 3600_000L, torontoRun - parisRun,
            "same wall-clock time, six hours apart in June — the zone is the rule's")
    }

    // ── Le poll — contre SQLite réel ──────────────────────────────────────

    private fun exec(sql: String) =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.createStatement().use { it.execute(sql) }
        }

    private fun seedScheduleRule(id: String, dueAt: Long, enabled: Boolean = true) {
        exec("""INSERT INTO automation_rules (id, owner_id, name, enabled, trigger_kind, trigger_widget_id, definition, created_at, updated_at)
                VALUES ('$id','u1','$id',${if (enabled) 1 else 0},'schedule',NULL,
                '{"when":{"kind":"schedule","at":"07:00","tz":"America/Toronto"},"cooldownS":0,"actions":[{"type":"EMAIL","body":"b"}]}',0,0)""")
        exec("INSERT INTO scheduled_jobs (rule_id, next_run_at, timezone) VALUES ('$id',$dueAt,'America/Toronto')")
    }

    private fun nextRunOf(id: String): Long =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT next_run_at FROM scheduled_jobs WHERE rule_id='$id'")
                    .use { rs -> rs.next(); rs.getLong(1) }
            }
        }

    private fun drained(): List<RelayEvent.TimeReached> = buildList {
        while (true) add((sinks.discrete.tryReceive().getOrNull() ?: break) as? RelayEvent.TimeReached ?: continue)
    }

    @Test
    fun `a due schedule fires with the WALL-CLOCK target, and the row advances`() {
        val due = msOf(2026, 6, 10, 7, 0)
        now = due + 8_000   // the poll runs 8 s after 07:00
        seedScheduleRule("r1", due)

        val fired = SchedulerWorker(sinks, clock = { now }).pollOnce()

        assertEquals(1, fired)
        val event = drained().single()
        assertEquals(due, event.scheduledFor, "the fact time is 07:00:00, not the poll instant")
        assertEquals(msOf(2026, 6, 11, 7, 0), nextRunOf("r1"),
            "tomorrow is 07:00:00 sharp — a late poll must not drift the schedule")
    }

    @Test
    fun `not due yet — nothing fires, nothing moves`() {
        val due = msOf(2026, 6, 10, 7, 0)
        now = due - 60_000
        seedScheduleRule("r1", due)

        assertEquals(0, SchedulerWorker(sinks, clock = { now }).pollOnce())
        assertEquals(due, nextRunOf("r1"))
    }

    @Test
    fun `the server was off at the appointed hour — skipped and advanced, never replayed`() {
        val due = msOf(2026, 6, 10, 7, 0)
        now = due + 7 * 3600_000L   // rebooted at 14 h
        seedScheduleRule("r1", due)

        val fired = SchedulerWorker(sinks, clock = { now }).pollOnce()

        assertEquals(0, fired, "watering replayed at 14 h does more damage than watering missed")
        assertEquals(msOf(2026, 6, 11, 7, 0), nextRunOf("r1"), "…but the row advances to tomorrow")
    }

    @Test
    fun `a two-minute deploy does not eat the morning watering`() {
        val due = msOf(2026, 6, 10, 7, 0)
        now = due + 2 * 60_000L
        seedScheduleRule("r1", due)

        assertEquals(1, SchedulerWorker(sinks, clock = { now }).pollOnce(),
            "within the grace, late still fires")
    }

    @Test
    fun `a disabled rule advances silently — no event`() {
        val due = msOf(2026, 6, 10, 7, 0)
        now = due + 1_000
        seedScheduleRule("r1", due, enabled = false)

        assertEquals(0, SchedulerWorker(sinks, clock = { now }).pollOnce())
        assertTrue(drained().isEmpty())
        assertEquals(msOf(2026, 6, 11, 7, 0), nextRunOf("r1"))
    }

    // ── Bout en bout : TimeReached → moteur → pending_actions ─────────────

    @Test
    fun `the whole chain — the clock strikes, a durable action lands, replays dedup`() {
        val due = msOf(2026, 6, 10, 7, 0)
        now = due + 5_000
        seedScheduleRule("r1", due)

        val cache = RuleCache().apply { reload() }
        val engine = AutomationEngine(
            sinks, cache, SqlitePendingActionRepository(), SqliteAutomationStateStore(),
            deviceRepository, clock = { now }
        )
        SchedulerWorker(sinks, clock = { now }).pollOnce()
        drained().forEach { engine.handle(it) }

        // A restart-race replay of the SAME occurrence: same scheduledFor.
        engine.handle(RelayEvent.TimeReached("u1", "r1", due, now + 1_000))

        val rows = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM pending_actions").use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertEquals(1, rows, "one occurrence = one action, however many times it is delivered to the engine")
    }
}
