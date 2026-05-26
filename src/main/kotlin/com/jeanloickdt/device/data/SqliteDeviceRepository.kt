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

// device/data/SqliteDeviceRepository.kt
package com.jeanloickdt.device.data

import com.jeanloickdt.device.domain.DeviceConnectivity
import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.device.domain.DeviceRow
import com.jeanloickdt.device.domain.DeviceType
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class SqliteDeviceRepository : DeviceRepository {

    override fun create(
        name: String,
        projectId: String,
        ownerId: String,
        tokenHash: String,
        deviceType: DeviceType,
        connectivity: DeviceConnectivity
    ): String {
        val id = UUID.randomUUID().toString()
        transaction {
            DeviceTable.insert {
                it[DeviceTable.id]           = id
                it[DeviceTable.projectId]    = projectId
                it[DeviceTable.ownerId]      = ownerId
                it[DeviceTable.name]         = name
                it[DeviceTable.tokenHash]    = tokenHash
                it[DeviceTable.isOnline]     = false
                it[DeviceTable.lastSeen]     = null
                it[DeviceTable.deviceType]   = deviceType.name
                it[DeviceTable.connectivity] = connectivity.name
            }
        }
        return id
    }

    override fun findById(id: String): DeviceRow? {
        return transaction {
            DeviceTable
                .selectAll()
                .where { DeviceTable.id eq id }
                .singleOrNull()
                ?.toDeviceRow()
        }
    }

    override fun findByTokenHash(tokenHash: String): DeviceRow? {
        return transaction {
            DeviceTable
                .selectAll()
                .where { DeviceTable.tokenHash eq tokenHash }
                .singleOrNull()
                ?.toDeviceRow()
        }
    }

    override fun findAll(): List<DeviceRow> {
        return transaction {
            DeviceTable.selectAll().map { it.toDeviceRow() }
        }
    }

    override fun findAllByOwner(ownerId: String): List<DeviceRow> {
        return transaction {
            DeviceTable
                .selectAll()
                .where { DeviceTable.ownerId eq ownerId }
                .map { it.toDeviceRow() }
        }
    }

    override fun findAllByProject(projectId: String): List<DeviceRow> {
        return transaction {
            DeviceTable
                .selectAll()
                .where { DeviceTable.projectId eq projectId }
                .map { it.toDeviceRow() }
        }
    }

    override fun updateOnlineStatus(id: String, isOnline: Boolean) {
        transaction {
            DeviceTable.update({ DeviceTable.id eq id }) {
                it[DeviceTable.isOnline] = isOnline
            }
        }
    }

    override fun markAllOffline() {
        transaction {
            DeviceTable.update({ DeviceTable.isOnline eq true }) {
                it[DeviceTable.isOnline] = false
            }
        }
    }

    override fun updateLastSeen(id: String, timestamp: Long) {
        transaction {
            DeviceTable.update({ DeviceTable.id eq id }) {
                it[DeviceTable.lastSeen] = timestamp
            }
        }
    }

    override fun updateName(id: String, newName: String) {
        transaction {
            DeviceTable.update({ DeviceTable.id eq id }) {
                it[DeviceTable.name] = newName
            }
        }
    }

    override fun delete(id: String): Boolean {
        return transaction {
            DeviceTable.deleteWhere { DeviceTable.id eq id } > 0
        }
    }

    override fun deleteAllByProject(projectId: String) {
        transaction {
            DeviceTable.deleteWhere { DeviceTable.projectId eq projectId }
        }
    }

    override fun renewToken(id: String, newTokenHash: String) {
        transaction {
            DeviceTable.update({ DeviceTable.id eq id }) {
                it[DeviceTable.tokenHash] = newTokenHash
            }
        }
    }

    override fun count(): Long {
        return transaction {
            DeviceTable.selectAll().count()
        }
    }

    override fun countOnline(): Long {
        return transaction {
            DeviceTable.selectAll().where { DeviceTable.isOnline eq true }.count()
        }
    }

    private fun ResultRow.toDeviceRow() = DeviceRow(
        id           = this[DeviceTable.id],
        projectId    = this[DeviceTable.projectId],
        ownerId      = this[DeviceTable.ownerId],
        name         = this[DeviceTable.name],
        tokenHash    = this[DeviceTable.tokenHash],
        lastSeen     = this[DeviceTable.lastSeen],
        isOnline     = this[DeviceTable.isOnline],
        deviceType   = DeviceType.fromString(this[DeviceTable.deviceType]),
        connectivity = DeviceConnectivity.fromString(this[DeviceTable.connectivity])
    )
}