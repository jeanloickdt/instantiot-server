/*
 * InstantIoT Server — self-hosted IoT relay for makers.
 * Copyright (C) 2026 InstantIoT
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

/**
 * Type of IoT device supported by InstantIoT.
 *
 * Each entry corresponds to a physical board with its own hardware
 * constraints (built-in WiFi, Ethernet shield, etc.).
 * The [DEVICE_CONNECTIVITY_MAP] map defines the connectivity modes
 * available per board.
 *
 * ⚠️ Stored in DB as String (name of the enum) — see the
 * `devices.device_type` column. Any new entry must be added to the
 * map AND synchronized on the Android app side via
 * `feature/connectivity/domain/.../DeviceType.kt`.
 */
enum class DeviceType {
    ESP32,
    ESP8266,
    ARDUINO_UNO_R4_WIFI,
    ARDUINO_UNO_R4_MINIMA,
    ARDUINO_MEGA_2560,
    ARDUINO_NANO_33_IOT;

    companion object {
        /**
         * Case-sensitive parse from a String to [DeviceType].
         * Returns null if the value matches no entry.
         */
        fun fromString(value: String?): DeviceType? = value?.let {
            entries.firstOrNull { entry -> entry.name == it }
        }
    }
}

/**
 * Physical connectivity mode of a device toward the InstantIoT server.
 *
 * ⚠️ Stored in DB as String (name of the enum) — see the
 * `devices.connectivity` column.
 */
enum class DeviceConnectivity {
    WIFI,
    ETHERNET;

    companion object {
        fun fromString(value: String?): DeviceConnectivity? = value?.let {
            entries.firstOrNull { entry -> entry.name == it }
        }
    }
}

/**
 * Constrained mapping: for each [DeviceType], the list of connectivity
 * modes actually supported by the board.
 *
 * - `ESP32`               → WIFI + ETHERNET (ESP32-Ethernet, LAN8720, etc.)
 * - `ESP8266`             → WIFI only
 * - `ARDUINO_UNO_R4_WIFI` → WIFI only (built-in WiFi chip)
 * - `ARDUINO_UNO_R4_MINIMA` → ETHERNET via Ethernet shield
 * - `ARDUINO_MEGA_2560`   → ETHERNET via Ethernet shield (no native WiFi)
 * - `ARDUINO_NANO_33_IOT` → WIFI only
 *
 * ⚠️ Keep synchronized with the Android app side
 * `feature/connectivity/domain/.../DeviceType.kt`. TODO: share via
 * a common Gradle module if the duplication becomes a problem.
 */
val DEVICE_CONNECTIVITY_MAP: Map<DeviceType, Set<DeviceConnectivity>> = mapOf(
    DeviceType.ESP32                 to setOf(DeviceConnectivity.WIFI, DeviceConnectivity.ETHERNET),
    DeviceType.ESP8266               to setOf(DeviceConnectivity.WIFI),
    DeviceType.ARDUINO_UNO_R4_WIFI   to setOf(DeviceConnectivity.WIFI),
    DeviceType.ARDUINO_UNO_R4_MINIMA to setOf(DeviceConnectivity.ETHERNET),
    DeviceType.ARDUINO_MEGA_2560     to setOf(DeviceConnectivity.ETHERNET),
    DeviceType.ARDUINO_NANO_33_IOT   to setOf(DeviceConnectivity.WIFI)
)

/**
 * Validates that a `(deviceType, connectivity)` combination is acceptable
 * according to [DEVICE_CONNECTIVITY_MAP]. Used by `POST /api/devices` to
 * reject invalid pairs with 400 (e.g. ESP8266 + ETHERNET).
 */
fun isValidDeviceCombination(
    type: DeviceType,
    connectivity: DeviceConnectivity
): Boolean = DEVICE_CONNECTIVITY_MAP[type]?.contains(connectivity) == true