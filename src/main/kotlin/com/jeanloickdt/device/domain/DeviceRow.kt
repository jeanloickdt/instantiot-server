// device/domain/DeviceRow.kt
package com.jeanloickdt.device.domain

data class DeviceRow(
    val id: String,
    val projectId: String,
    val ownerId: String,
    val name: String,
    val tokenHash: String,
    val lastSeen: Long?,
    val isOnline: Boolean
)