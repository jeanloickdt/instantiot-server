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

package com.jeanloickdt.signal.data

import com.jeanloickdt.signal.domain.SignalRepository
import com.jeanloickdt.signal.domain.SignalRow
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class SqliteSignalRepository : SignalRepository {

    override fun find(ownerId: String, deviceId: String, address: Int): SignalRow? = transaction {
        SignalTable.selectAll()
            .where {
                (SignalTable.ownerId eq ownerId) and
                    (SignalTable.deviceId eq deviceId) and
                    (SignalTable.address eq address)
            }
            .limit(1)
            .firstOrNull()
            ?.toRow()
    }

    override fun listByDevice(ownerId: String, deviceId: String): List<SignalRow> = transaction {
        SignalTable.selectAll()
            .where { (SignalTable.ownerId eq ownerId) and (SignalTable.deviceId eq deviceId) }
            .orderBy(SignalTable.address)
            .map { it.toRow() }
    }

    override fun listByOwner(ownerId: String): List<SignalRow> = transaction {
        SignalTable.selectAll()
            .where { SignalTable.ownerId eq ownerId }
            .orderBy(SignalTable.deviceId)
            .orderBy(SignalTable.address)
            .map { it.toRow() }
    }

    override fun create(
        ownerId: String,
        deviceId: String,
        address: Int,
        label: String,
        type: String,
        unit: String,
        decimals: Int,
        minValue: Double?,
        maxValue: Double?,
        historised: Boolean,
        direction: String,
        nowMs: Long
    ): Boolean {
        if (address < SignalTable.ADDRESS_MIN || address > SignalTable.ADDRESS_MAX) return false
        return transaction {
            // The composite PK would throw; a taken address is an ordinary
            // outcome the caller reports, not an exception to unwind.
            val taken = SignalTable.selectAll()
                .where {
                    (SignalTable.ownerId eq ownerId) and
                        (SignalTable.deviceId eq deviceId) and
                        (SignalTable.address eq address)
                }
                .limit(1).any()
            if (taken) return@transaction false

            SignalTable.insert {
                it[SignalTable.ownerId]    = ownerId
                it[SignalTable.deviceId]   = deviceId
                it[SignalTable.address]    = address
                it[SignalTable.label]      = label
                it[SignalTable.type]       = type
                it[SignalTable.unit]       = unit
                it[SignalTable.decimals]   = decimals
                it[SignalTable.minValue]   = minValue
                it[SignalTable.maxValue]   = maxValue
                it[SignalTable.historised] = historised
                it[SignalTable.direction]  = direction
                it[SignalTable.createdAt]  = nowMs
                it[SignalTable.updatedAt]  = nowMs
            }
            true
        }
    }

    /**
     * The lowest free slot, so a board's addresses stay dense and its sketch
     * reads `I0, I1, I2` instead of a scatter.
     */
    override fun nextFreeAddress(ownerId: String, deviceId: String): Int? = transaction {
        val taken = SignalTable.selectAll()
            .where { (SignalTable.ownerId eq ownerId) and (SignalTable.deviceId eq deviceId) }
            .map { it[SignalTable.address] }
            .toHashSet()
        (SignalTable.ADDRESS_MIN..SignalTable.ADDRESS_MAX).firstOrNull { it !in taken }
    }

    override fun update(
        ownerId: String,
        deviceId: String,
        address: Int,
        label: String?,
        unit: String?,
        decimals: Int?,
        minValue: Double?,
        maxValue: Double?,
        historised: Boolean?,
        direction: String?,
        type: String?,
        nowMs: Long
    ): Boolean = transaction {
        // Read first: whether the type actually CHANGES decides the fate of
        // the stored value, and a PATCH that repeats the current type must
        // not throw it away.
        val current = SignalTable.selectAll()
            .where {
                (SignalTable.ownerId eq ownerId) and
                    (SignalTable.deviceId eq deviceId) and
                    (SignalTable.address eq address)
            }
            .limit(1).firstOrNull() ?: return@transaction false

        val typeChanged = type != null && type != current[SignalTable.type]

        SignalTable.update({
            (SignalTable.ownerId eq ownerId) and
                (SignalTable.deviceId eq deviceId) and
                (SignalTable.address eq address)
        }) { row ->
            if (label != null)      row[SignalTable.label] = label
            if (unit != null)       row[SignalTable.unit] = unit
            if (decimals != null)   row[SignalTable.decimals] = decimals
            if (minValue != null)   row[SignalTable.minValue] = minValue
            if (maxValue != null)   row[SignalTable.maxValue] = maxValue
            if (historised != null) row[SignalTable.historised] = historised
            if (direction != null)  row[SignalTable.direction] = direction
            if (typeChanged) {
                row[SignalTable.type] = type!!
                // The bytes were encoded with the OLD tag. Replaying them
                // under the new one is what would send a float to a board
                // expecting an int — see the contract in SignalRepository.
                row[SignalTable.lastPayload] = null
                row[SignalTable.lastSeenAt] = null
            }
            row[SignalTable.updatedAt] = nowMs
        } > 0
    }

    override fun delete(ownerId: String, deviceId: String, address: Int): Boolean = transaction {
        SignalTable.deleteWhere {
            (SignalTable.ownerId eq ownerId) and
                (SignalTable.deviceId eq deviceId) and
                (SignalTable.address eq address)
        } > 0
    }

    override fun deleteByDevice(ownerId: String, deviceId: String): Int = transaction {
        SignalTable.deleteWhere {
            (SignalTable.ownerId eq ownerId) and (SignalTable.deviceId eq deviceId)
        }
    }

    override fun deleteByOwner(ownerId: String): Int = transaction {
        SignalTable.deleteWhere { SignalTable.ownerId eq ownerId }
    }

    override fun touch(
        ownerId: String,
        deviceId: String,
        address: Int,
        payloadB64: String,
        atMs: Long
    ): Boolean = transaction {
        SignalTable.update({
            (SignalTable.ownerId eq ownerId) and
                (SignalTable.deviceId eq deviceId) and
                (SignalTable.address eq address)
        }) {
            it[lastPayload] = payloadB64
            it[lastSeenAt]  = atMs
        } > 0
    }

    private fun ResultRow.toRow() = SignalRow(
        ownerId     = this[SignalTable.ownerId],
        deviceId    = this[SignalTable.deviceId],
        address     = this[SignalTable.address],
        label       = this[SignalTable.label],
        type        = this[SignalTable.type],
        unit        = this[SignalTable.unit],
        decimals    = this[SignalTable.decimals],
        minValue    = this[SignalTable.minValue],
        maxValue    = this[SignalTable.maxValue],
        historised  = this[SignalTable.historised],
        direction   = this[SignalTable.direction],
        lastPayload = this[SignalTable.lastPayload],
        lastSeenAt  = this[SignalTable.lastSeenAt]
    )
}
