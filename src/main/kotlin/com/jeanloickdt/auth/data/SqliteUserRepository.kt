package com.jeanloickdt.auth.data

import com.jeanloickdt.auth.domain.UserRepository
import com.jeanloickdt.auth.domain.UserRow
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class SqliteUserRepository : UserRepository {

    override fun create(username: String, pwdHash: String, role: String): String {
        val id = UUID.randomUUID().toString()
        transaction {
            UserTable.insert {
                it[UserTable.id]        = id
                it[UserTable.username]  = username
                it[UserTable.pwdHash]   = pwdHash
                it[UserTable.role]      = role
                it[UserTable.createdAt] = System.currentTimeMillis()
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

    override fun updatePassword(id: String, newHash: String) {
        transaction {
            UserTable.update({ UserTable.id eq id }) {
                it[pwdHash] = newHash
            }
        }
    }

    override fun count(): Long {
        return transaction {
            UserTable.selectAll().count()
        }
    }

    private fun ResultRow.toUserRow() = UserRow(
        id        = this[UserTable.id],
        username  = this[UserTable.username],
        pwdHash   = this[UserTable.pwdHash],
        role      = this[UserTable.role],
        createdAt = this[UserTable.createdAt]
    )
}