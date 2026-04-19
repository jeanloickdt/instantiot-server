package com.jeanloickdt.device.domain

// device/domain/DeviceRepository.kt

interface DeviceRepository {
    fun create(name: String, projectId: String, ownerId: String, tokenHash: String): String
    fun findById(id: String): DeviceRow?
    fun findByTokenHash(tokenHash: String): DeviceRow?
    fun findAll(): List<DeviceRow>
    fun findAllByOwner(ownerId: String): List<DeviceRow>
    fun findAllByProject(projectId: String): List<DeviceRow>
    fun updateOnlineStatus(id: String, isOnline: Boolean)
    /**
     * Bulk reset : marque tous les devices `isOnline = false`.
     *
     * Appelé au démarrage du serveur pour nettoyer les états stales
     * après un kill abrupt (Ctrl+C qui skip le `finally` de `handleDevice`).
     * Sans ça, la DB peut garder `isOnline=true` alors qu'aucune session
     * TCP n'est active → l'app affiche des devices "online" fantômes.
     */
    fun markAllOffline()
    fun updateLastSeen(id: String, timestamp: Long)
    /** Renomme un device. La session TCP active reste ouverte. */
    fun updateName(id: String, newName: String)
    fun delete(id: String): Boolean
    fun deleteAllByProject(projectId: String)
    fun renewToken(id: String, newTokenHash: String)
    fun count(): Long
    fun countOnline(): Long

}