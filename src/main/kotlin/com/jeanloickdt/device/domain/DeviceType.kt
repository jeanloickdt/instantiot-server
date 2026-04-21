package com.jeanloickdt.device.domain

/**
 * Type de device IoT supporté par InstantIoT.
 *
 * Chaque entrée correspond à un board physique avec ses propres
 * contraintes matérielles (WiFi intégré, Ethernet shield, etc.).
 * La map [DEVICE_CONNECTIVITY_MAP] définit les modes de connectivité
 * disponibles par board.
 *
 * ⚠️ Stocké en DB comme String (nom de l'enum) — voir
 * `devices.device_type` colonne. Toute nouvelle entrée doit être
 * ajoutée à la map ET synchronisée côté app Android via
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
         * Parse case-sensitive d'un String vers [DeviceType].
         * Retourne null si la valeur ne correspond à aucune entrée.
         */
        fun fromString(value: String?): DeviceType? = value?.let {
            entries.firstOrNull { entry -> entry.name == it }
        }
    }
}

/**
 * Mode de connectivité physique d'un device vers le serveur InstantIoT.
 *
 * ⚠️ Stocké en DB comme String (nom de l'enum) — voir
 * `devices.connectivity` colonne.
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
 * Mapping contraint : pour chaque [DeviceType], la liste des modes
 * de connectivité réellement supportés par le board.
 *
 * - `ESP32`               → WIFI + ETHERNET (ESP32-Ethernet, LAN8720, etc.)
 * - `ESP8266`             → WIFI seul
 * - `ARDUINO_UNO_R4_WIFI` → WIFI seul (puce WiFi intégrée)
 * - `ARDUINO_UNO_R4_MINIMA` → ETHERNET via Ethernet shield
 * - `ARDUINO_MEGA_2560`   → ETHERNET via Ethernet shield (pas de WiFi natif)
 * - `ARDUINO_NANO_33_IOT` → WIFI seul
 *
 * ⚠️ À garder synchronisé avec l'app Android côté
 * `feature/connectivity/domain/.../DeviceType.kt`. TODO: partager via
 * un module common Gradle si la duplication devient un problème.
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
 * Valide qu'une combinaison `(deviceType, connectivity)` est acceptable
 * selon [DEVICE_CONNECTIVITY_MAP]. Utilisé par `POST /api/devices` pour
 * rejeter avec 400 les paires invalides (ex : ESP8266 + ETHERNET).
 */
fun isValidDeviceCombination(
    type: DeviceType,
    connectivity: DeviceConnectivity
): Boolean = DEVICE_CONNECTIVITY_MAP[type]?.contains(connectivity) == true
