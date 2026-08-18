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

package com.jeanloickdt.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shape of the live-reflection message — the contract the app codes
 * against. Pinned here because a field renamed on this side would leave every
 * second phone silently stale, which is exactly the failure the event exists
 * to prevent.
 */
class LayoutChangedEventTest {

    @Test
    fun `the event says what changed and to which version — and carries no layout`() {
        val json = Json.encodeToString(
            ControlEvent.serializer(),
            ControlEvent(type = ControlEventType.LAYOUT_CHANGED, version = 4)
        )
        val o = Json.parseToJsonElement(json).jsonObject

        assertEquals("layout_changed", o["type"]!!.jsonPrimitive.content)
        assertEquals(4, o["version"]!!.jsonPrimitive.content.toInt())
        assertTrue("layoutJson" !in json,
            "the blob stays out: an app merely watching its gauges has no use for new geometry")
    }

    @Test
    fun `null fields are omitted, so the message stays small`() {
        val json = Json.encodeToString(
            ControlEvent.serializer(),
            ControlEvent(type = ControlEventType.LAYOUT_CHANGED, version = 1)
        )
        assertTrue("deviceId" !in json && "avg" !in json,
            "this is sent to every session on every debounced save — it must not carry the whole schema")
    }
}
