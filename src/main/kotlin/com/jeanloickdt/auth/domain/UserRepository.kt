// auth/UserRepository.kt
package com.jeanloickdt.auth.domain

interface UserRepository {
    /**
     * Create a new user in the users table.
     *
     * Note: the legacy `passwordChanged` flag has been removed. The V1
     * first-launch flow handles credential bootstrap differently
     * (licence-key-based admin password + welcome screen with
     * Renew/Skip choice). See V1_PLAN.md.
     */
    fun create(
        username: String,
        pwdHash: String,
        role: String = "user"
    ): String

    fun findByUsername(username: String): UserRow?
    fun findById(id: String): UserRow?
    fun updatePassword(id: String, newHash: String)

    /**
     * Partial update : si un argument est `null`, le champ correspondant
     * reste inchangé. No-op si les deux sont null. Utilisé par le V1
     * first-launch welcome (Renew action) qui peut modifier l'un et/ou
     * l'autre.
     */
    fun updateCredentials(id: String, newUsername: String?, newPwdHash: String?)

    fun count(): Long
}
