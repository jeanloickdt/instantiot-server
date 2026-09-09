package com.jeanloickdt.automation

import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.deviceRepository
import com.jeanloickdt.automation.v2.AutomationEngine
import com.jeanloickdt.automation.v2.AutomationRuns
import com.jeanloickdt.automation.v2.InventoryResolver
import com.jeanloickdt.automation.v2.RuleCache
import com.jeanloickdt.automation.v2.SignalValueCache
import com.jeanloickdt.automation.v2.Trigger
import com.jeanloickdt.signalRepository
import com.jeanloickdt.event.EventSinks
import com.jeanloickdt.event.RelayEvent
import com.jeanloickdt.project.data.ProjectTable
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

    private lateinit var sinks: EventSinks
    private var now = 0L

    /**
     * Le fuseau ne vient PLUS d'ici : il appartient a la regle.
     *
     * Un `Schedule` et un `TimeOfDay` portant chacun le leur rendraient legale
     * une regle programmee a Toronto qui teste les heures de Teheran.
     */
    private fun schedule(at: String = "07:00", days: Set<Trigger.Day> = emptySet()) =
        Trigger.Schedule(
            minuteOfDay = at.substringBefore(':').toInt() * 60 + at.substringAfter(':').toInt(),
            days = days
        )

    private fun msOf(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = TORONTO): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.connectAndClean()
        sinks = EventSinks()
    }

    // ── L'arithmétique — pure ─────────────────────────────────────────────

    @Test
    fun `seven means seven in the rule's zone, tomorrow when today's has passed`() {
        val afterNoon = msOf(2026, 6, 10, 12, 0)
        assertEquals(msOf(2026, 6, 11, 7, 0), ScheduleMath.nextRunAfter(afterNoon, schedule(), TORONTO))

        val beforeDawn = msOf(2026, 6, 10, 5, 0)
        assertEquals(msOf(2026, 6, 10, 7, 0), ScheduleMath.nextRunAfter(beforeDawn, schedule(), TORONTO))
    }

    @Test
    fun `a day mask skips to the next allowed day`() {
        // 2026-06-10 is a Wednesday; Mondays only → 2026-06-15.
        val wednesday = msOf(2026, 6, 10, 12, 0)
        assertEquals(
            msOf(2026, 6, 15, 7, 0),
            ScheduleMath.nextRunAfter(wednesday, schedule(days = setOf(Trigger.Day.MON)), TORONTO)
        )
    }

    @Test
    fun `spring forward — a schedule inside the gap shifts, never vanishes`() {
        // 2026-03-08, America/Toronto: 02:00→03:00 does not exist. A 02:30
        // schedule must still fire that day (java.time shifts it into 03:30),
        // not silently skip to tomorrow.
        val beforeGap = msOf(2026, 3, 8, 1, 0)
        val next = ScheduleMath.nextRunAfter(beforeGap, schedule(at = "02:30"), TORONTO)
        val local = java.time.Instant.ofEpochMilli(next).atZone(TORONTO)
        assertEquals(8, local.dayOfMonth, "the run must stay on DST day, shifted — not skipped")
        assertEquals(3, local.hour)
    }

    @Test
    fun `fall back — the repeated hour fires once, on the first occurrence`() {
        // 2026-11-01, America/Toronto: 01:30 happens twice. One run, the first.
        val beforeMidnight = msOf(2026, 11, 1, 0, 0)
        val next = ScheduleMath.nextRunAfter(beforeMidnight, schedule(at = "01:30"), TORONTO)
        val following = ScheduleMath.nextRunAfter(next, schedule(at = "01:30"), TORONTO)
        val followingLocal = java.time.Instant.ofEpochMilli(following).atZone(TORONTO)
        assertEquals(2, followingLocal.dayOfMonth,
            "after the first 01:30, the NEXT run is tomorrow — the repeated hour must not double-fire")
    }

    @Test
    fun `the hour is the user's, not the server's`() {
        // Le meme horaire, deux fuseaux — portes par la REGLE, plus par le
        // declencheur. C'est ce que « 7 h veut dire 7 h chez celui qui a ecrit
        // la regle » signifie concretement.
        val at7 = schedule(at = "07:00")
        val after = msOf(2026, 6, 10, 0, 0, ZoneId.of("UTC"))
        val parisRun = ScheduleMath.nextRunAfter(after, at7, ZoneId.of("Europe/Paris"))
        val torontoRun = ScheduleMath.nextRunAfter(after, at7, TORONTO)
        assertEquals(6 * 3600_000L, torontoRun - parisRun,
            "same wall-clock time, six hours apart in June — the zone is the rule's")
    }

    // ── Le poll — contre SQLite réel ──────────────────────────────────────

    // Le JDBC brut visait le fichier SQLite. La meme instruction passe par la
    // transaction Exposed, donc par la connexion du moment — celle de Postgres.
    private fun exec(sql: String) =
        org.jetbrains.exposed.sql.transactions.transaction { exec(sql) }

    private fun seedScheduleRule(id: String, dueAt: Long, enabled: Boolean = true) {
        // Le fuseau est en COLONNE, plus dans la definition : c'est la
        // revision ① — tout ce qui n'est pas l'arbre logique sort du JSON.
        exec("""INSERT INTO automation_rules
                (id, owner_id, name, enabled, trigger_kind, trigger_signal_key, definition,
                 time_zone_id, schema_version, created_at, updated_at)
                VALUES ('$id','u1','$id',$enabled,'schedule',NULL,
                '{"trigger":{"kind":"schedule","minuteOfDay":420},"actions":[{"kind":"email","subject":"s","body":"b"}]}',
                'America/Toronto','v2',0,0)""")
        exec("INSERT INTO scheduled_jobs (rule_id, next_run_at, timezone) VALUES ('$id',$dueAt,'America/Toronto')")
    }

    private fun nextRunOf(id: String): Long =
        org.jetbrains.exposed.sql.transactions.transaction {
            exec("SELECT next_run_at FROM scheduled_jobs WHERE rule_id='$id'") { rs ->
                rs.next(); rs.getLong(1)
            }!!
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

        val cache = RuleCache(InventoryResolver(signalRepository, deviceRepository)).apply { reload() }
        val engine = AutomationEngine(
            sinks = sinks,
            cache = cache,
            values = SignalValueCache(signalRepository),
            actions = ExposedPendingActionRepository(),
            runs = AutomationRuns(),
            devices = deviceRepository,
            clock = { now }
        )
        SchedulerWorker(sinks, clock = { now }).pollOnce()
        drained().forEach { engine.handle(it) }

        // A restart-race replay of the SAME occurrence: same scheduledFor.
        engine.handle(RelayEvent.TimeReached("u1", "r1", due, now + 1_000))

        val rows = org.jetbrains.exposed.sql.transactions.transaction {
            exec("SELECT count(*) FROM pending_actions") { rs -> rs.next(); rs.getInt(1) }!!
        }
        assertEquals(1, rows, "one occurrence = one action, however many times it is delivered to the engine")
    }
}
