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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The live last-value cache is keyed by (ownerId, widgetId). Without that, two
 * users sharing a protocolId (gauge1…) would stomp the same entry — last writer
 * wins, each seeing the other's live value. These pin the per-owner separation.
 */
class LastValueCacheTest {

    private val widget = "gauge1"
    private val A = "owner-A"
    private val B = "owner-B"

    @Test
    fun `two owners' values under the same widgetId do not stomp each other`() {
        val cache = InMemoryLastValueCache()
        cache.put(A, widget, "A-val", 100)
        cache.put(B, widget, "B-val", 200)

        assertEquals("A-val", cache.get(A, widget)?.payload, "A reads its own value")
        assertEquals("B-val", cache.get(B, widget)?.payload, "B reads its own value, not A's")
    }

    @Test
    fun `two owners under the same key never see each other's value`() {
        // Le test que la classe existe pour porter. La cle textuelle est deja
        // unique par carte, mais l'isolation ne doit dependre d'aucune
        // propriete des donnees : un seul champ, et le dernier ecrivain
        // gagnerait pour tout le monde.
        val cache = InMemoryLastValueCache()
        cache.put(A, widget, "A-val", 100)
        cache.put(B, widget, "B-val", 200)

        assertEquals("A-val", cache.get(A, widget)?.payload)
        assertEquals("B-val", cache.get(B, widget)?.payload)
    }
}
