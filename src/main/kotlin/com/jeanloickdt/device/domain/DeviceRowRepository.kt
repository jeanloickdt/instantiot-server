package com.jeanloickdt.device.domain

// device/domain/DeviceRepository.kt

interface DeviceRepository {
    fun create(name: String, projectId: String, ownerId: String, tokenHash: String): String
    fun findById(id: String): DeviceRow?
    fun findByTokenHash(tokenHash: String): DeviceRow?
    fun findAllByOwner(ownerId: String): List<DeviceRow>
    fun findAllByProject(projectId: String): List<DeviceRow>
    fun updateOnlineStatus(id: String, isOnline: Boolean)
    fun updateLastSeen(id: String, timestamp: Long)
    fun delete(id: String): Boolean
    fun renewToken(id: String, newTokenHash: String)

}