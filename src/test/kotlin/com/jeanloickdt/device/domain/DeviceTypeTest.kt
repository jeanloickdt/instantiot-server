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

package com.jeanloickdt.device.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the device-type / connectivity domain — the input validation
 * applied by `POST /api/devices`. Parsing is case-sensitive (values come
 * straight off the wire and are stored as enum names in SQLite), and the
 * type↔connectivity matrix rejects physically impossible pairs.
 */
class DeviceTypeTest {

    @Test
    fun `fromString parses a valid device type`() {
        assertEquals(DeviceType.ESP32, DeviceType.fromString("ESP32"))
        assertEquals(DeviceType.ARDUINO_NANO_33_IOT, DeviceType.fromString("ARDUINO_NANO_33_IOT"))
    }

    @Test
    fun `fromString is case-sensitive and rejects unknown, empty or null`() {
        assertNull(DeviceType.fromString("esp32"))    // wrong case
        assertNull(DeviceType.fromString("ESP-32"))
        assertNull(DeviceType.fromString("UNKNOWN"))
        assertNull(DeviceType.fromString(""))
        assertNull(DeviceType.fromString(null))
    }

    @Test
    fun `connectivity fromString parses valid and rejects invalid`() {
        assertEquals(DeviceConnectivity.WIFI, DeviceConnectivity.fromString("WIFI"))
        assertEquals(DeviceConnectivity.ETHERNET, DeviceConnectivity.fromString("ETHERNET"))
        assertNull(DeviceConnectivity.fromString("wifi"))
        assertNull(DeviceConnectivity.fromString("4G"))
        assertNull(DeviceConnectivity.fromString(null))
    }

    @Test
    fun `valid device-connectivity combinations are accepted`() {
        assertTrue(isValidDeviceCombination(DeviceType.ESP32, DeviceConnectivity.WIFI))
        assertTrue(isValidDeviceCombination(DeviceType.ESP32, DeviceConnectivity.ETHERNET))
        assertTrue(isValidDeviceCombination(DeviceType.ESP8266, DeviceConnectivity.WIFI))
        assertTrue(isValidDeviceCombination(DeviceType.ARDUINO_UNO_R4_WIFI, DeviceConnectivity.WIFI))
        assertTrue(isValidDeviceCombination(DeviceType.ARDUINO_UNO_R4_MINIMA, DeviceConnectivity.ETHERNET))
        assertTrue(isValidDeviceCombination(DeviceType.ARDUINO_MEGA_2560, DeviceConnectivity.ETHERNET))
        assertTrue(isValidDeviceCombination(DeviceType.ARDUINO_NANO_33_IOT, DeviceConnectivity.WIFI))
    }

    @Test
    fun `impossible device-connectivity combinations are rejected`() {
        assertFalse(isValidDeviceCombination(DeviceType.ESP8266, DeviceConnectivity.ETHERNET))
        assertFalse(isValidDeviceCombination(DeviceType.ARDUINO_UNO_R4_WIFI, DeviceConnectivity.ETHERNET))
        assertFalse(isValidDeviceCombination(DeviceType.ARDUINO_UNO_R4_MINIMA, DeviceConnectivity.WIFI))
        assertFalse(isValidDeviceCombination(DeviceType.ARDUINO_MEGA_2560, DeviceConnectivity.WIFI))
        assertFalse(isValidDeviceCombination(DeviceType.ARDUINO_NANO_33_IOT, DeviceConnectivity.ETHERNET))
    }

    @Test
    fun `every device type is present in the connectivity map with at least one mode`() {
        // Guards the documented footgun: "any new entry must be added to the map".
        // A new DeviceType without a map entry would make POST /api/devices reject
        // EVERY connectivity for that board.
        for (type in DeviceType.entries) {
            val modes = DEVICE_CONNECTIVITY_MAP[type]
            assertNotNull(modes, "DeviceType $type is missing from DEVICE_CONNECTIVITY_MAP")
            assertTrue(modes.isNotEmpty(), "DeviceType $type has no connectivity mode")
        }
    }
}
