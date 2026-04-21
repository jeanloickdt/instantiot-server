// device/domain/DeviceModels.kt
package com.jeanloickdt.device.domain

import kotlinx.serialization.Serializable

@Serializable
data class CreateDeviceRequest(
    val name: String,
    val projectId: String,
    /**
     * Nom de l'enum [DeviceType] (ex: `"ESP32"`, `"ARDUINO_UNO_R4_WIFI"`).
     * Validé par la route — rejet 400 si valeur inconnue.
     */
    val deviceType: String,
    /**
     * Nom de l'enum [DeviceConnectivity] (ex: `"WIFI"`, `"ETHERNET"`).
     * Validé par la route — rejet 400 si valeur inconnue OU si la paire
     * `(deviceType, connectivity)` n'est pas dans [DEVICE_CONNECTIVITY_MAP].
     */
    val connectivity: String
)

@Serializable
data class DeviceResponse(
    val id: String,
    val name: String,
    val projectId: String,
    val isOnline: Boolean,
    val lastSeen: Long?,
    val deviceType: String? = null,
    val connectivity: String? = null
)

@Serializable
data class CreateDeviceResponse(
    val id: String,
    val name: String,
    val projectId: String,
    val token: String,  // affiché une seule fois — jamais stocké en clair
    val deviceType: String,
    val connectivity: String
)

@Serializable
data class UpdateDeviceNameRequest(
    val name: String
)