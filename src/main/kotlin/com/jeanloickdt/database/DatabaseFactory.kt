/*
 * InstantIoT Server — self-hosted IoT relay for makers.
 * Copyright (C) 2026 Djoufack Tsobeng Jean Loick (InstantIoT)
 * Author: Djoufack Tsobeng Jean Loick (@jeanloick_dt)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.jeanloickdt.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.File
import java.sql.DriverManager

object DatabaseFactory {
    /**
     * @param dbFile the SQLite file to open. Defaults to the production
     *   `~/.instantiot/instantiot.db`; tests pass a throwaway temp file to get
     *   an isolated database without touching the real one.
     */
    fun init(vararg tables: Table, dbFile: File = com.jeanloickdt.common.ServerConfig.dbFile) {
        // DB stored in ~/.instantiot/ (next to licence.key, secret.key,
        // setup.done) — single source of truth for all server state files.
        // Prevents the process CWD (systemd, jpackage, gradlew run from
        // anywhere) from influencing the DB location.
        val url = "jdbc:sqlite:${dbFile.absolutePath}"

        // SQLite hardening — applied to the REAL connections Exposed uses,
        // via a configured DataSource. The previous code set these PRAGMAs on
        // a throwaway DriverManager connection that was closed BEFORE Exposed
        // connected, so synchronous/cache_size/temp_store were silently lost
        // and busy_timeout was never set at all.
        //
        // busy_timeout is the critical one: in WAL mode one writer + N readers
        // run concurrently, but two concurrent WRITERS (the 5s history flush +
        // the relay's updateLastPayload + the hourly cleanup + the backup +
        // a REST write) would otherwise fail INSTANTLY with SQLITE_BUSY
        // ("database is locked"). A 5s busy timeout makes the loser wait for
        // the lock instead of throwing — eliminating the lock-storm under the
        // exact concurrent load this server is designed for.
        val config = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
            setBusyTimeout(5_000)                                 // ms
            setPragma(SQLiteConfig.Pragma.CACHE_SIZE, "-32000")   // ~32 MB page cache
            setTempStore(SQLiteConfig.TempStore.MEMORY)
        }
        val dataSource = SQLiteDataSource(config).apply { setUrl(url) }

        Database.connect(dataSource)

        transaction {
            // ─── Auto-migration of new columns/tables ───
            // `createMissingTablesAndColumns`:
            //   1. Creates missing tables (like the old `create`)
            //   2. ALTER TABLE ... ADD COLUMN for new columns
            //      added in the code but absent from the existing DB
            //
            // → When adding a feature (e.g. tags on widgets, notification
            //   config) that requires a new column in an Exposed `Table`,
            //   the upgrade of existing users migrates all by itself on
            //   first boot. No manual script to write.
            //
            // ⚠️ Safety rules to respect in the `Table` code:
            //   - Any new column must be `.nullable()` or have a
            //     `.default(...)` (otherwise ALTER fails on existing
            //     rows that wouldn't have the value).
            //   - NEVER rename a column (Exposed doesn't detect it
            //     → orphan column in the DB). Prefer:
            //     add new column → manual data migration
            //     → drop old column (manual, later).
            //   - Never change the type of an existing column
            //     (same, not detected).
            //
            // For destructive changes/renames, write a
            // `runCatching { exec("ALTER TABLE ...") }` block below (cf.
            // the legacy ALTERs on `devices` kept as no-ops for DBs
            // already migrated).
            //
            // Note: `createMissingTablesAndColumns` is deprecated by
            // Exposed which pushes toward Flyway for prod setups. For a
            // self-hosted server with simple additive migrations, this
            // function remains the right tool. We suppress the warning
            // explicitly rather than burdening the project with an
            // external migration tool.
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(*tables)

            // ─── Legacy migrations (devices) ──────────────────
            // Kept for DBs that were migrated manually back when
            // `SchemaUtils.create()` was used. No-op on new installs
            // (the column already exists via createMissingTablesAndColumns)
            // and on DBs already migrated (duplicate column → caught
            // silently).
            runCatching { exec("ALTER TABLE devices ADD COLUMN device_type TEXT") }
            runCatching { exec("ALTER TABLE devices ADD COLUMN connectivity TEXT") }

            // index for widget_history — fast time-range queries
            exec("CREATE INDEX IF NOT EXISTS idx_history_widget  ON widget_history (widget_id, recorded_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_project ON widget_history (project_id, recorded_at)")

            // index for widget_history_numeric — chart time-range reads
            exec("CREATE INDEX IF NOT EXISTS idx_history_numeric_widget ON widget_history_numeric (widget_id, recorded_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_numeric_widget_series ON widget_history_numeric (widget_id, series_id, recorded_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_numeric_project ON widget_history_numeric (project_id, recorded_at)")

            // UNIQUE INDEX for the aggregation tables — idempotence via
            // INSERT OR IGNORE in SqliteWidgetHistoryAggregateRepository.
            // COALESCE series → '' to match nulls (series absent on the
            // gauge/metric/etc side).
            exec("CREATE UNIQUE INDEX IF NOT EXISTS uniq_history_min  ON widget_history_min  (widget_id, COALESCE(series_id, ''), bucket_at)")
            exec("CREATE UNIQUE INDEX IF NOT EXISTS uniq_history_hour ON widget_history_hour (widget_id, COALESCE(series_id, ''), bucket_at)")
            exec("CREATE UNIQUE INDEX IF NOT EXISTS uniq_history_day  ON widget_history_day  (widget_id, COALESCE(series_id, ''), bucket_at)")

            // Time-range read index — same strategy as raw
            exec("CREATE INDEX IF NOT EXISTS idx_history_min_widget_series  ON widget_history_min  (widget_id, series_id, bucket_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_hour_widget_series ON widget_history_hour (widget_id, series_id, bucket_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_day_widget_series  ON widget_history_day  (widget_id, series_id, bucket_at)")

            // Cleanup per project (cascade DELETE)
            exec("CREATE INDEX IF NOT EXISTS idx_history_min_project  ON widget_history_min  (project_id)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_hour_project ON widget_history_hour (project_id)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_day_project  ON widget_history_day  (project_id)")
        }
    }

    /**
     * Reclaim disk space by rebuilding the database file.
     *
     * The hourly retention job DELETEs old rows but SQLite never returns the
     * freed pages to the OS — the file only grows toward its high-water mark.
     * On a Raspberry Pi / SD card this slowly eats the disk. `VACUUM` rewrites
     * the file compactly, returning the freed space.
     *
     * Runs on a dedicated raw connection because VACUUM **cannot** execute
     * inside an open transaction (which Exposed's `transaction {}` would
     * create). It takes a brief exclusive lock and rewrites the whole file, so
     * it's meant to run rarely (weekly) and off-peak — the 5s busy timeout set
     * in [init] makes concurrent writers wait rather than fail during it.
     */
    fun vacuum() {
        val url = "jdbc:sqlite:${com.jeanloickdt.common.ServerConfig.dbFile.absolutePath}"
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("VACUUM") }
        }
    }
}