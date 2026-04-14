package com.jeanloickdt.auth.domain

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val role: String,
    val passwordChanged: Boolean
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

@Serializable
data class AdminStatsResponse(
    val users: Long,
    val projects: Long,
    val devicesTotal: Long,
    val devicesOnline: Int,
    val appSessionsActive: Int,
    val deviceSessionsActive: Int
)

@Serializable
data class ServerInfoResponse(
    val version: String,
    val httpPort: Int,
    val tcpPort: Int,
    val uptimeMs: Long,
    val dbSizeBytes: Long,
    val javaVersion: String,
    val osName: String,
    val localIp: String
)

@Serializable
data class UpdateConfigRequest(
    val httpPort: Int? = null,
    val tcpPort: Int? = null
)

@Serializable
data class UpdateConfigResponse(
    val message: String,
    val httpPort: Int,
    val tcpPort: Int
)