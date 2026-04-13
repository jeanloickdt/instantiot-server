package com.jeanloickdt.auth.data

import org.jetbrains.exposed.sql.Table

object UserTable : Table("users") {
    val id        = text("id")
    val username  = text("username").uniqueIndex()
    val pwdHash   = text("pwd_hash")
    val role      = text("role").default("user")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}