// common/Models.kt
package com.jeanloickdt.common

import kotlinx.serialization.Serializable

@Serializable
data class StatusResponse(
    val status: String,
    val setup_required: Boolean,
    val licence_required: Boolean
)