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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parser's one promise: broken data degrades ITS rule — null plus a loud
 * log — never the engine. Every refusal case below would otherwise be either
 * a crash or, worse, a rule that silently does something else than asked.
 */
class RuleDefinitionTest {

    private fun parse(json: String) = RuleDefinition.parseOrNull("r-test", json)

    @Test
    fun `the three kinds parse, with their defaults`() {
        val value = parse("""{"when":{"kind":"value","above":90.0},"actions":[{"type":"PUSH","title":"t","body":"b"}]}""")!!
        assertEquals(RuleDefinition.DEFAULT_COOLDOWN_MS, value.cooldownMs, "60 s cooldown by default")
        val t = value.trigger as Trigger.ValueThreshold
        assertEquals(90.0 - 1.8, t.rearm, 1e-9, "default rearm = threshold − 2 %")

        val offline = parse("""{"when":{"kind":"offline","deviceId":"d1"},"actions":[{"type":"EMAIL","body":"b"}]}""")!!
        assertEquals(RuleDefinition.DEFAULT_OFFLINE_AFTER_MS, (offline.trigger as Trigger.DeviceOffline).afterMs)

        assertTrue(parse("""{"when":{"kind":"stale"},"actions":[{"type":"PUSH","title":"t","body":"b"}]}""")!!
            .trigger is Trigger.WidgetStale)
    }

    @Test
    fun `the hysteresis floor covers the zero threshold`() {
        // 2 % of zero is zero — a margin of nothing is no hysteresis at all.
        assertEquals(-RuleDefinition.HYSTERESIS_FLOOR, RuleDefinition.defaultRearm(above = true, threshold = 0.0), 1e-9)
        assertEquals(RuleDefinition.HYSTERESIS_FLOOR, RuleDefinition.defaultRearm(above = false, threshold = 0.0), 1e-9)
    }

    @Test
    fun `broken data degrades its rule, never the engine`() {
        assertNull(parse("not json at all"))
        assertNull(parse("{}"), "missing when")
        assertNull(parse("""{"when":{"kind":"teleport"},"actions":[{"type":"PUSH"}]}"""), "unknown kind")
        assertNull(parse("""{"when":{"kind":"value"},"actions":[{"type":"PUSH"}]}"""), "value without threshold")
        assertNull(parse("""{"when":{"kind":"value","above":1,"below":2},"actions":[{"type":"PUSH"}]}"""), "both sides")
        assertNull(parse("""{"when":{"kind":"offline"},"actions":[{"type":"PUSH"}]}"""), "offline without device")
        assertNull(parse("""{"when":{"kind":"value","above":90},"actions":[]}"""), "a rule that does nothing")
        assertNull(parse("""{"when":{"kind":"value","above":90},"actions":[{"type":"CARRIER_PIGEON"}]}"""), "unknown action")
    }

    @Test
    fun `a rearm on the wrong side would disable the hysteresis — refused`() {
        assertNull(parse("""{"when":{"kind":"value","above":90,"rearmBelow":95},"actions":[{"type":"PUSH","title":"t"}]}"""))
        assertNull(parse("""{"when":{"kind":"value","below":10,"rearmAbove":5},"actions":[{"type":"PUSH","title":"t"}]}"""))
    }

    @Test
    fun `negative timings are refused, not clamped in silence`() {
        assertNull(parse("""{"when":{"kind":"value","above":90},"cooldownS":-1,"actions":[{"type":"PUSH","title":"t"}]}"""))
        assertNull(parse("""{"when":{"kind":"offline","deviceId":"d","afterS":-5},"actions":[{"type":"PUSH","title":"t"}]}"""))
    }
}
