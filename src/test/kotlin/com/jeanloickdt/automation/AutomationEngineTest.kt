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
import com.jeanloickdt.projectRepository
import com.jeanloickdt.widget.data.WidgetHistoryDayTable
import com.jeanloickdt.widget.data.WidgetHistoryHourTable
import com.jeanloickdt.widget.data.WidgetHistoryMinTable
import com.jeanloickdt.widget.data.WidgetHistoryNumericTable
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import java.io.File
import java.sql.DriverManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine's automaton, clock in hand. Every test asks one of the two
 * questions that decide whether alerts are sellable: does the RIGHT alert go
 * out, and does the WRONG one stay in — the flapping sensor, the bouncing
 * Wi-Fi, the replayed episode, the cross-tenant command.
 */
class AutomationEngineTest {

    private lateinit var dbFile: File
    private lateinit var sinks: EventSinks
    private lateinit var cache: RuleCache
    private lateinit var engine: AutomationEngine
    private var now = 1_000_000_000L

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("instantiot-engine-", ".db").apply { deleteOnExit() }
        DatabaseFactory.init(
            UserTable, ProjectTable, DeviceTable, WidgetTable,
            WidgetHistoryTable, WidgetHistoryNumericTable,
            WidgetHistoryMinTable, WidgetHistoryHourTable, WidgetHistoryDayTable,
            *AutomationTables.ALL,
            dbFile = dbFile
        )
        sinks = EventSinks()
        cache = RuleCache()
        engine = AutomationEngine(
            sinks, cache, SqlitePendingActionRepository(), SqliteAutomationStateStore(),
            deviceRepository, clock = { now }
        )
    }

    // ── seeding ───────────────────────────────────────────────────────────

    private fun exec(sql: String) =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            c.createStatement().use { it.execute(sql) }
        }

    private fun insertRule(
        id: String, owner: String = "u1", widgetId: String? = "w1",
        kind: String, definition: String
    ) {
        val widgetCol = if (widgetId == null) "NULL" else "'$widgetId'"
        exec("""INSERT INTO automation_rules (id, owner_id, name, enabled, trigger_kind, trigger_widget_id, definition, created_at, updated_at)
                VALUES ('$id','$owner','$id',1,'$kind',$widgetCol,'$definition',0,0)""")
        cache.reload()
    }

    private fun valueRule(
        id: String = "r1", threshold: Double = 90.0, rearm: Double? = 85.0,
        cooldownS: Int = 0, actions: String = """[{"type":"PUSH","title":"t","body":"{{value}}"}]"""
    ) {
        val rearmPart = rearm?.let { ""","rearmBelow":$it""" } ?: ""
        insertRule(id, kind = "value",
            definition = """{"when":{"kind":"value","above":$threshold$rearmPart},"cooldownS":$cooldownS,"actions":$actions}""")
    }

    private fun value(v: Double, at: Long = now, depth: Int = 0) =
        RelayEvent.WidgetValue("u1", "w1", null, v, at, depth)

    private fun pendingRows(type: String? = null): List<Pair<String, String>> =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
            val where = type?.let { "WHERE type = '$it'" } ?: ""
            c.createStatement().use { s ->
                s.executeQuery("SELECT idempotency_key, payload FROM pending_actions $where").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                }
            }
        }

    // ── Le seuil, l'hystérésis ────────────────────────────────────────────

    @Test
    fun `crossing the threshold fires once, with the value rendered`() {
        valueRule()
        engine.handle(value(85.0))
        engine.handle(value(92.5))

        val rows = pendingRows("PUSH")
        assertEquals(1, rows.size)
        assertTrue("92.5" in rows.single().second, "{{value}} must render: ${rows.single().second}")
    }

    @Test
    fun `a flapping sensor fires exactly once — the whole point of hysteresis`() {
        valueRule(threshold = 90.0, rearm = 85.0)
        // 89.8 / 90.1 / 89.9 / 90.2: crosses "above 90" three times, never
        // dips under the 85 rearm line. Without hysteresis: three pushes in
        // two seconds. With it: one.
        listOf(89.8, 90.1, 89.9, 90.2, 89.7, 91.0).forEach { engine.handle(value(it, at = now++)) }

        assertEquals(1, pendingRows().size)
    }

    @Test
    fun `dipping past the rearm line re-arms — a NEW episode fires again`() {
        valueRule(threshold = 90.0, rearm = 85.0, cooldownS = 0)
        engine.handle(value(92.0, at = now))       // fire 1
        engine.handle(value(87.0, at = now + 1))   // below 90 but above rearm: still latched
        engine.handle(value(93.0, at = now + 2))   // NOT a new episode
        engine.handle(value(84.0, at = now + 3))   // past the rearm line
        engine.handle(value(91.0, at = now + 4))   // a genuinely new episode

        assertEquals(2, pendingRows().size)
    }

    @Test
    fun `a sustained condition is one episode, not one alert per sample`() {
        valueRule(cooldownS = 0)
        repeat(100) { engine.handle(value(95.0, at = now + it)) }
        assertEquals(1, pendingRows().size)
    }

    @Test
    fun `below-kind rules mirror the logic`() {
        insertRule("r1", kind = "value",
            definition = """{"when":{"kind":"value","below":0.0},"cooldownS":0,"actions":[{"type":"PUSH","title":"gel","body":"b"}]}""")
        engine.handle(value(2.0))
        engine.handle(value(-1.0))
        // Default rearm for threshold 0 is the absolute FLOOR (2% of 0 is 0):
        // 0.01 — climbing to 0.005 must NOT re-arm, 0.02 must.
        engine.handle(value(0.005, at = now + 1))
        engine.handle(value(-1.0, at = now + 2))
        assertEquals(1, pendingRows().size, "0.005 is under the floor — same episode")

        engine.handle(value(0.02, at = now + 3))
        engine.handle(value(-1.0, at = now + 4))
        assertEquals(2, pendingRows().size, "0.02 re-armed — new episode")
    }

    // ── Le cooldown ───────────────────────────────────────────────────────

    @Test
    fun `the cooldown caps the rate across episodes`() {
        valueRule(threshold = 90.0, rearm = 85.0, cooldownS = 60)
        engine.handle(value(92.0, at = now))            // fire 1
        engine.handle(value(80.0, at = now + 10_000))   // re-armed
        engine.handle(value(92.0, at = now + 20_000))   // new episode, 20 s < 60 s → swallowed
        assertEquals(1, pendingRows().size)

        engine.handle(value(80.0, at = now + 30_000))
        engine.handle(value(92.0, at = now + 70_000))   // 70 s ≥ 60 s → fires
        assertEquals(2, pendingRows().size)
    }

    // ── Stale, et l'identité de l'épisode ─────────────────────────────────

    @Test
    fun `stale fires once, a re-detection of the SAME silence dedups on the index`() {
        insertRule("r1", kind = "stale",
            definition = """{"when":{"kind":"stale"},"cooldownS":0,"actions":[{"type":"PUSH","title":"muet","body":"b"}]}""")
        val lastSeen = now - 20 * 60_000L
        engine.handle(RelayEvent.WidgetStale("u1", "w1", lastSeen, now))

        // Sweeper restart: same episode re-reported, same lastSeenAt — the
        // engine state was ALSO reset to simulate the worst case.
        cache.reload()
        engine.handle(RelayEvent.WidgetStale("u1", "w1", lastSeen, now + 60_000))

        assertEquals(1, pendingRows().size, "the fact time keys the dedup — one push per silence")
    }

    @Test
    fun `the sensor speaking closes the stale episode — the next silence fires anew`() {
        insertRule("r1", kind = "stale",
            definition = """{"when":{"kind":"stale"},"cooldownS":0,"actions":[{"type":"PUSH","title":"muet","body":"b"}]}""")
        engine.handle(RelayEvent.WidgetStale("u1", "w1", now - 20 * 60_000, now))
        engine.handle(value(42.0, at = now + 1))   // it spoke
        engine.handle(RelayEvent.WidgetStale("u1", "w1", now + 1, now + 30 * 60_000))

        assertEquals(2, pendingRows().size)
    }

    // ── Offline : la confirmation par le tick, jamais par une attente ─────

    private fun offlineRule(afterS: Int = 30) {
        insertRule("r1", widgetId = null, kind = "offline",
            definition = """{"when":{"kind":"offline","deviceId":"d1","afterS":$afterS},"cooldownS":0,"actions":[{"type":"PUSH","title":"hors ligne","body":"b"}]}""")
    }

    @Test
    fun `offline fires only after the confirmation window, keyed on the vanish time`() {
        offlineRule(afterS = 30)
        engine.handle(RelayEvent.DeviceOffline("u1", "d1", "disconnected", now))
        engine.tick(now + 10_000)
        assertEquals(0, pendingRows().size, "10 s < 30 s — not confirmed yet")

        engine.tick(now + 31_000)
        val rows = pendingRows()
        assertEquals(1, rows.size)
        assertTrue(":$now:" in rows.single().first, "the fact time is when it VANISHED, not the tick")
    }

    @Test
    fun `a Wi-Fi bounce never fires — DeviceOnline cancels for free`() {
        offlineRule(afterS = 30)
        engine.handle(RelayEvent.DeviceOffline("u1", "d1", "disconnected", now))
        engine.handle(RelayEvent.DeviceOnline("u1", "d1", now + 4_000))   // back after 4 s
        engine.tick(now + 60_000)

        assertEquals(0, pendingRows().size, "a bounce on weak Wi-Fi must not push")
    }

    @Test
    fun `after a bounce, a real outage still fires — from the second vanish time`() {
        offlineRule(afterS = 30)
        engine.handle(RelayEvent.DeviceOffline("u1", "d1", "disconnected", now))
        engine.handle(RelayEvent.DeviceOnline("u1", "d1", now + 4_000))
        engine.handle(RelayEvent.DeviceOffline("u1", "d1", "disconnected", now + 10_000))
        engine.tick(now + 41_000)

        val rows = pendingRows()
        assertEquals(1, rows.size)
        assertTrue(":${now + 10_000}:" in rows.single().first)
    }

    // ── La propriété de la cible COMMAND ──────────────────────────────────

    @Test
    fun `a COMMAND aimed at another tenant's board is skipped, the PUSH still fires`() {
        val victimProject = projectRepository.create("p-victim", "victim")
        deviceRepository.create(
            name = "board", projectId = victimProject, ownerId = "victim",
            tokenHash = "h", deviceType = com.jeanloickdt.device.domain.DeviceType.ESP32,
            connectivity = com.jeanloickdt.device.domain.DeviceConnectivity.WIFI
        ).let { victimDevice ->
            valueRule(actions = """[
                {"type":"COMMAND","deviceId":"$victimDevice","payloadB64":"AA=="},
                {"type":"PUSH","title":"t","body":"b"}
            ]""")
        }

        engine.handle(value(95.0))

        assertEquals(0, pendingRows("COMMAND").size,
            "never trust an identifier without its owner — cross-tenant command refused")
        assertEquals(1, pendingRows("PUSH").size, "the other actions still fire")
    }

    // ── depth, redémarrage ────────────────────────────────────────────────

    @Test
    fun `an event past the depth guard is refused and counted`() {
        valueRule()
        engine.handle(value(95.0, depth = 4))
        assertEquals(0, pendingRows().size)
        assertEquals(1, engine.depthRefusedCount)
    }

    @Test
    fun `a restart neither re-arms the fleet nor replays yesterday's alert`() {
        valueRule(cooldownS = 0)
        engine.handle(value(95.0))
        assertEquals(1, pendingRows().size)

        // Restart: fresh cache (state reloaded from the table), fresh engine.
        val cache2 = RuleCache().apply { reload() }
        val engine2 = AutomationEngine(
            sinks, cache2, SqlitePendingActionRepository(), SqliteAutomationStateStore(),
            deviceRepository, clock = { now }
        )
        // The condition still holds — the rule must be loaded TRIGGERED.
        engine2.handle(value(96.0, at = now + 5_000))
        assertEquals(1, pendingRows().size, "the dawn replay is dead by construction")

        // But a genuine new episode after the restart fires normally.
        engine2.handle(value(80.0, at = now + 6_000))
        engine2.handle(value(95.0, at = now + 7_000))
        assertEquals(2, pendingRows().size)
    }

    @Test
    fun `zero rules — the engine looks at events and touches nothing`() {
        engine.handle(value(95.0))
        engine.handle(RelayEvent.DeviceOffline("u1", "d1", "disconnected", now))
        engine.tick(now + 60_000)
        assertEquals(0, pendingRows().size)
        assertNull(pendingRows().firstOrNull())
    }
}
