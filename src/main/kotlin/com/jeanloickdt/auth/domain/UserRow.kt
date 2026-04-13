package com.jeanloickdt.auth.domain

data class UserRow(
    val id: String,
    val username: String,
    val pwdHash: String,
    val role: String,
    val createdAt: Long
)