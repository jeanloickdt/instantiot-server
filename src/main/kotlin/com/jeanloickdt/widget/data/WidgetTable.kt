// widget/data/WidgetTable.kt
package com.jeanloickdt.widget.data

import org.jetbrains.exposed.sql.Table

object WidgetTable : Table("widgets") {
    val id          = text("id")
    val projectId   = text("project_id")
    val ownerId     = text("owner_id")
    val type        = text("type")                     // "display" | "command" — pour widget_history
    val lastPayload = text("last_payload").nullable()  // PAYLOAD brut base64 — écrit par relay uniquement
    val lastSeenAt  = long("last_seen_at").nullable()  // timestamp dernière trame ESP — écrit par relay
    override val primaryKey = PrimaryKey(id)
}