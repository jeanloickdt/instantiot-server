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

// device/data/ExposedDeviceRepository.kt
package com.jeanloickdt.device.data

import com.jeanloickdt.device.domain.DeviceConnectivity
import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.device.domain.DeviceRow
import com.jeanloickdt.device.domain.DeviceType
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class ExposedDeviceRepository : DeviceRepository, com.jeanloickdt.device.domain.DevicePresenceWriter {

    /** Une carte, et son proprietaire. Jamais l'un sans l'autre. */
    private fun mine(ownerId: String, id: String) =
        (DeviceTable.id eq id) and (DeviceTable.ownerId eq ownerId)


    override fun create(
        ownerId: String,
        name: String,
        projectId: String,
        tokenHash: String,
        deviceType: DeviceType,
        connectivity: DeviceConnectivity
    ): DeviceRow {
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
        // La ligne, pas l'identifiant : l'appelant n'a plus de seconde requete
        // a faire ni de `!!` a ecrire.
        return transaction {
            DeviceTable.selectAll().where { mine(ownerId, id) }.single().toDeviceRow()
        }
    }

    override fun findById(ownerId: String, id: String): DeviceRow? {
        return transaction {
            DeviceTable
                .selectAll()
                .where { mine(ownerId, id) }
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

    override fun findAllForAdmin(): List<DeviceRow> {
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

    override fun findAllByProject(ownerId: String, projectId: String): List<DeviceRow> {
        return transaction {
            DeviceTable
                .selectAll()
                .where { (DeviceTable.projectId eq projectId) and (DeviceTable.ownerId eq ownerId) }
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

    override fun updateName(ownerId: String, id: String, newName: String): DeviceRow? = transaction {
        val touched = DeviceTable.update({ mine(ownerId, id) }) {
            it[DeviceTable.name] = newName
        }
        if (touched == 0) null
        else DeviceTable.selectAll().where { mine(ownerId, id) }.single().toDeviceRow()
    }

    override fun updateHardware(
        ownerId: String,
        id: String,
        deviceType: String?,
        connectivity: String?
    ): DeviceRow? = transaction {
        // Rien a faire n'est pas une erreur : le PATCH peut ne porter que le
        // nom, et cette methode est alors appelee sans rien.
        if (deviceType == null && connectivity == null) {
            return@transaction DeviceTable.selectAll()
                .where { mine(ownerId, id) }.singleOrNull()?.toDeviceRow()
        }
        val touched = DeviceTable.update({ mine(ownerId, id) }) {
            if (deviceType != null) it[DeviceTable.deviceType] = deviceType
            if (connectivity != null) it[DeviceTable.connectivity] = connectivity
        }
        if (touched == 0) null
        else DeviceTable.selectAll().where { mine(ownerId, id) }.single().toDeviceRow()
    }

    override fun delete(ownerId: String, id: String): Boolean = transaction {
        DeviceTable.deleteWhere { (DeviceTable.id eq id) and (DeviceTable.ownerId eq ownerId) } > 0
    }

    override fun deleteAllByProject(ownerId: String, projectId: String): Int = transaction {
        DeviceTable.deleteWhere {
            (DeviceTable.projectId eq projectId) and (DeviceTable.ownerId eq ownerId)
        }
    }

    override fun deleteAllByOwner(ownerId: String): Int = transaction {
        DeviceTable.deleteWhere { DeviceTable.ownerId eq ownerId }
    }

    override fun renewToken(ownerId: String, id: String, newTokenHash: String): DeviceRow? = transaction {
        val touched = DeviceTable.update({ mine(ownerId, id) }) {
            it[DeviceTable.tokenHash] = newTokenHash
        }
        if (touched == 0) null
        else DeviceTable.selectAll().where { mine(ownerId, id) }.single().toDeviceRow()
    }

    override fun countByOwner(ownerId: String): Long {
        return transaction {
            DeviceTable.selectAll().where { DeviceTable.ownerId eq ownerId }.count()
        }
    }

    override fun countAll(): Long {
        return transaction {
            DeviceTable.selectAll().count()
        }
    }

    override fun countOnlineAll(): Long {
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