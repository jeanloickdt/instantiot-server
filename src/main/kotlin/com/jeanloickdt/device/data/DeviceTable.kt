// device/data/DeviceTable.kt
package com.jeanloickdt.device.data

import org.jetbrains.exposed.sql.Table

object DeviceTable : Table("devices") {
    val id        = text("id")
    val projectId = text("project_id")
    val ownerId   = text("owner_id")
    val name      = text("name")
    val tokenHash = text("token_hash")  // SHA-256 du token
    val lastSeen  = long("last_seen").nullable()
    val isOnline  = bool("is_online").default(false)
    override val primaryKey = PrimaryKey(id)
}