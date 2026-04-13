// project/data/WidgetTable.kt
package com.jeanloickdt.project.data

import org.jetbrains.exposed.sql.Table

object ProjectTable : Table("projects") {
    val id         = text("id")
    val ownerId    = text("owner_id")
    val name       = text("name")
    val layoutJson = text("layout_json").default("{}") // ProjectLayout complet — blob opaque
    val createdAt  = long("created_at")
    val updatedAt  = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}