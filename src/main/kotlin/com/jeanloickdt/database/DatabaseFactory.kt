package com.jeanloickdt.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager

object DatabaseFactory {
    fun init(vararg tables: Table) {
        val url = "jdbc:sqlite:./instantiot.db"

        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA synchronous=NORMAL")
                stmt.execute("PRAGMA cache_size=-32000")
                stmt.execute("PRAGMA temp_store=MEMORY")
            }
        }

        Database.connect(url = url, driver = "org.sqlite.JDBC")

        transaction {
            SchemaUtils.create(*tables)

            // index pour widget_history — queries rapides par plage de temps
            exec("CREATE INDEX IF NOT EXISTS idx_history_widget  ON widget_history (widget_id, recorded_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_project ON widget_history (project_id, recorded_at)")

            // index pour widget_history_numeric — lectures chart time-range
            exec("CREATE INDEX IF NOT EXISTS idx_history_numeric_widget ON widget_history_numeric (widget_id, recorded_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_numeric_widget_series ON widget_history_numeric (widget_id, series_id, recorded_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_numeric_project ON widget_history_numeric (project_id, recorded_at)")
        }
    }
}