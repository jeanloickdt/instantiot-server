// auth/UserRepository.kt
package com.jeanloickdt.auth.domain

interface UserRepository {
    fun create(username: String, pwdHash: String, role: String = "user"): String
    fun findByUsername(username: String): UserRow?
    fun findById(id: String): UserRow?
    fun updatePassword(id: String, newHash: String)
    fun count(): Long
}

