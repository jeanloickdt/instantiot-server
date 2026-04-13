// project/domain/ProjectRow.kt
package com.jeanloickdt.project.domain

data class ProjectRow(
    val id: String,
    val ownerId: String,
    val name: String,
    val layoutJson: String, // ProjectLayout complet — blob opaque
    val createdAt: Long,
    val updatedAt: Long
)