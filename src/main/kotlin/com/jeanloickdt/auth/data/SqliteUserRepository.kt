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

package com.jeanloickdt.auth.data

import com.jeanloickdt.auth.domain.UserRepository
import com.jeanloickdt.auth.domain.UserRow
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class SqliteUserRepository : UserRepository {

    override fun create(
        username: String,
        pwdHash: String,
        role: String,
        passwordChanged: Boolean
    ): String {
        val id = UUID.randomUUID().toString()
        transaction {
            UserTable.insert {
                it[UserTable.id]              = id
                it[UserTable.username]        = username
                it[UserTable.pwdHash]         = pwdHash
                it[UserTable.role]            = role
                // false for the bootstrap admin/admin (must be changed),
                // true for self-registration (user picked their own password).
                it[UserTable.passwordChanged] = passwordChanged
                it[UserTable.createdAt]       = System.currentTimeMillis()
            }
        }
        return id
    }

    override fun findByUsername(username: String): UserRow? {
        return transaction {
            UserTable
                .selectAll()
                .where { UserTable.username eq username }
                .singleOrNull()
                ?.toUserRow()
        }
    }

    override fun findById(id: String): UserRow? {
        return transaction {
            UserTable
                .selectAll()
                .where { UserTable.id eq id }
                .singleOrNull()
                ?.toUserRow()
        }
    }

    override fun findAll(): List<UserRow> {
        return transaction {
            UserTable.selectAll()
                .orderBy(UserTable.createdAt to SortOrder.ASC)
                .map { it.toUserRow() }
        }
    }

    override fun updatePassword(id: String, newHash: String, passwordChanged: Boolean) {
        transaction {
            UserTable.update({ UserTable.id eq id }) {
                it[pwdHash] = newHash
                it[UserTable.passwordChanged] = passwordChanged
            }
        }
    }

    override fun updateCredentials(id: String, newUsername: String?, newPwdHash: String?) {
        if (newUsername == null && newPwdHash == null) return
        transaction {
            UserTable.update({ UserTable.id eq id }) {
                if (newUsername != null) it[username] = newUsername
                // setting one's own password clears the "must change" flag
                if (newPwdHash != null) {
                    it[pwdHash] = newPwdHash
                    it[UserTable.passwordChanged] = true
                }
            }
        }
    }

    override fun count(): Long {
        return transaction {
            UserTable.selectAll().count()
        }
    }

    private fun ResultRow.toUserRow() = UserRow(
        id              = this[UserTable.id],
        username        = this[UserTable.username],
        pwdHash         = this[UserTable.pwdHash],
        role            = this[UserTable.role],
        passwordChanged = this[UserTable.passwordChanged],
        createdAt       = this[UserTable.createdAt]
    )
}