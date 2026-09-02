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
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.File
import java.sql.DriverManager

object DatabaseFactory {

    private val log = LoggerFactory.getLogger("DatabaseFactory")

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

            // ── Le modele signal ──
            // L'adresse et l'instant : c'est la lecture de toute courbe.
            exec("CREATE INDEX IF NOT EXISTS idx_signal_raw_signal  ON signal_raw  (signal_id, ts)")
            exec("CREATE INDEX IF NOT EXISTS idx_signal_min_signal  ON signal_min  (signal_id, bucket_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_signal_hour_signal ON signal_hour (signal_id, bucket_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_signal_day_signal  ON signal_day  (signal_id, bucket_at)")
            // Le balayage de retention, par compte
            exec("CREATE INDEX IF NOT EXISTS idx_signal_raw_owner  ON signal_raw  (owner_id, ts)")
            exec("CREATE INDEX IF NOT EXISTS idx_signal_min_owner  ON signal_min  (owner_id, bucket_at)")

            // ── Automations & notifications ──
            // The UNIQUE on idempotency_key is THE GUARANTEE, not an
            // optimisation: the engine may evaluate the same event twice
            // (restart mid-batch) and the second INSERT must die on this
            // constraint rather than send the owner two pushes.
            exec("CREATE UNIQUE INDEX IF NOT EXISTS uniq_pending_idempotency ON pending_actions (idempotency_key)")
            // The DeliveryWorker's every-second sweep: "what is due?"
            exec("CREATE INDEX IF NOT EXISTS idx_pending_due ON pending_actions (status, next_attempt_at)")
            // Le chargement du cache des regles
            exec("CREATE INDEX IF NOT EXISTS idx_rules_owner_signal ON automation_rules (owner_id, trigger_signal_key)")
            // The scheduler's poll — an indexed range scan, not a table scan
            exec("CREATE INDEX IF NOT EXISTS idx_scheduled_due ON scheduled_jobs (next_run_at)")
            // Delivery fans out per owner: all their tokens in one read
            exec("CREATE INDEX IF NOT EXISTS idx_push_tokens_owner ON push_tokens (owner_id)")
        }

        // Switch the file to incremental auto-vacuum so the recurring reclaim is
        // a cheap PRAGMA incremental_vacuum, not a full-file VACUUM. Runs AFTER
        // the transaction above (VACUUM cannot execute inside a transaction) and
        // before the relay/routes accept traffic, so nothing else holds the DB.
        ensureIncrementalAutoVacuum(dbFile)
    }

    /**
     * Ensures the database uses `auto_vacuum=INCREMENTAL`.
     *
     * SQLite only lets auto_vacuum be set before any table exists, OR switched
     * afterwards by a one-time `VACUUM` (see sqlite.org/pragma.html). So:
     *   - already incremental (mode 2) → no-op, every boot after the first.
     *   - otherwise → set the mode + one `VACUUM` to rewrite the file in it.
     *
     * That one-time `VACUUM` is the only full rewrite we pay: it runs here at
     * init, before any traffic, so there is no concurrent writer to lock out —
     * trivial on a fresh install, a single up-front cost on an existing DB.
     * Thereafter the weekly reclaim is the lock-light [incrementalVacuum].
     *
     * Must run OUTSIDE an Exposed transaction — VACUUM cannot run inside one.
     */
    private fun ensureIncrementalAutoVacuum(dbFile: File) {
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                val mode = stmt.executeQuery("PRAGMA auto_vacuum").use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
                if (mode == 2) return        // 2 = incremental → nothing to do
                log.info("Migrating database to incremental auto_vacuum (one-time; may take a moment on a large DB)…")
                stmt.execute("PRAGMA auto_vacuum=INCREMENTAL")
                stmt.execute("VACUUM")       // rewrites the file in the new mode
                log.info("Database now uses incremental auto_vacuum")
            }
        }
    }

    /**
     * Reclaim disk space freed by the retention DELETEs.
     *
     * The hourly retention job DELETEs old rows but SQLite keeps the freed pages
     * on the freelist — on a Raspberry Pi / SD card the file slowly eats the
     * disk. With [init] having put the file in `auto_vacuum=INCREMENTAL`, those
     * pages are tracked, and `PRAGMA incremental_vacuum` truncates them back to
     * the OS. Unlike a full `VACUUM` this does **not** rewrite the whole file
     * under a multi-second exclusive lock — it just trims the freelist — so it
     * is cheap enough to run weekly without disrupting live writers.
     *
     * Runs on a dedicated raw connection (no surrounding Exposed transaction).
     *
     * @param dbFile the SQLite file to reclaim. Defaults to production; tests
     *   pass a throwaway file.
     */
    fun incrementalVacuum(dbFile: File = com.jeanloickdt.common.ServerConfig.dbFile) {
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("PRAGMA incremental_vacuum") }
        }
    }
}