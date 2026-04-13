// widget/data/WidgetHistoryTable.kt
package com.jeanloickdt.widget.data

import org.jetbrains.exposed.sql.Table

object WidgetHistoryTable : Table("widget_history") {
    // auto-increment — table interne, pas exposée en API
    val id         = integer("id").autoIncrement()
    val widgetId   = text("widget_id")   // FK → widgets.id
    val projectId  = text("project_id")  // query par projet sans JOIN
    val ownerId    = text("owner_id")    // isolation sans JOIN
    val payload    = text("payload")     // PAYLOAD brut base64 — opaque server
    val recordedAt = long("recorded_at") // timestamp enregistrement
    override val primaryKey = PrimaryKey(id)
}