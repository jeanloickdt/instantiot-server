// auth/UserRepository.kt
package com.jeanloickdt.auth.domain

interface UserRepository {
    /**
     * Create a new user in the users table.
     *
     * @param passwordChanged `true` when the user chose the password themselves
     *   (e.g. via /api/register). `false` when the password was assigned
     *   (admin bootstrap or admin-reset via a forgot-password flow) — the
     *   user will be forced to change it at the next login.
     */
    fun create(
        username: String,
        pwdHash: String,
        role: String = "user",
        passwordChanged: Boolean = false
    ): String

    fun findByUsername(username: String): UserRow?
    fun findById(id: String): UserRow?
    fun updatePassword(id: String, newHash: String)
    fun count(): Long
}

