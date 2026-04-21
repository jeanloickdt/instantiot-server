// device/data/DeviceTable.kt
package com.jeanloickdt.device.data

import org.jetbrains.exposed.sql.Table

object DeviceTable : Table("devices") {
    val id            = text("id")
    val projectId     = text("project_id")
    val ownerId       = text("owner_id")
    val name          = text("name")
    val tokenHash     = text("token_hash")  // SHA-256 du token
    val lastSeen      = long("last_seen").nullable()
    val isOnline      = bool("is_online").default(false)
    /**
     * Type matériel du device (ex: `ESP32`, `ARDUINO_UNO_R4_WIFI`).
     * Stocké comme String = nom de l'enum `DeviceType`.
     * Nullable pour rétro-compat avec les devices créés avant cette feature
     * — les nouveaux devices rejettent null via validation route.
     */
    val deviceType    = text("device_type").nullable()
    /**
     * Mode de connectivité physique (`WIFI` ou `ETHERNET`).
     * Stocké comme String = nom de l'enum `DeviceConnectivity`.
     * Nullable pour la même raison de rétro-compat.
     */
    val connectivity  = text("connectivity").nullable()
    override val primaryKey = PrimaryKey(id)
}