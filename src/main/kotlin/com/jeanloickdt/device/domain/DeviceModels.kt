// device/domain/DeviceModels.kt
package com.jeanloickdt.device.domain

import kotlinx.serialization.Serializable

@Serializable
data class CreateDeviceRequest(
    val name: String,
    val projectId: String
)

@Serializable
data class DeviceResponse(
    val id: String,
    val name: String,
    val projectId: String,
    val isOnline: Boolean,
    val lastSeen: Long?
)

@Serializable
data class CreateDeviceResponse(
    val id: String,
    val name: String,
    val projectId: String,
    val token: String  // affiché une seule fois — jamais stocké en clair
)