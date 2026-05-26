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

package com.jeanloickdt.backup

import com.jeanloickdt.common.ServerConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.DriverManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the server's SQLite snapshots — protects the maker against
 * data loss in case of:
 *   - SQLite corruption (rare but happens on a Pi SD card)
 *   - A dying disk
 *   - Human error (an unfortunate `rm -rf`)
 *   - Restore after a bad update
 *
 * **Snapshot**: `VACUUM INTO 'backup.db'` — atomic, handles WAL, does
 * not block in-progress writes. Output in `~/.instantiot/backups/`
 * under the name `instantiot-YYYY-MM-DD-HHmm.db`.
 *
 * **Restore**: copies the chosen backup in place of `instantiot.db`,
 * keeping the old DB under `.before-restore-XXX` as a safety
 * net. Server restart required (Exposed maintains a connection pool
 * that does not like the file being swapped under it).
 *
 * **Lifecycle**: [start] launches a periodic coroutine that snapshots
 * + cleans up according to `ServerConfig.backupIntervalHours`. [stop] cancels.
 *
 * **Thread-safety**: only one snapshot can run at a time (sync
 * on `this`). Avoids concurrent snapshots if the admin clicks
 * "Backup now" while the cron is running.
 */
object BackupManager {

    private val log = LoggerFactory.getLogger("BackupManager")

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)
    private val displayFormat   = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Timestamp ms of the last successful snapshot (for admin panel display). */
    @Volatile var lastBackupAtMs: Long = 0L
        private set

    /**
     * Immediate snapshot. Idempotent on the filename side — if called 2×
     * in the same minute, the 2nd overwrites the 1st (ms not enough in the name).
     *
     * @return File of the created backup, or null on failure
     */
    @Synchronized
    fun snapshotNow(): File? {
        val srcDb = ServerConfig.dbFile
        if (!srcDb.exists()) {
            log.warn("Cannot snapshot — source DB does not exist: $srcDb")
            return null
        }

        val backupDir = ServerConfig.backupDir
        backupDir.mkdirs()
        val timestamp = timestampFormat.format(Date())
        val target = File(backupDir, "instantiot-$timestamp.db")

        return try {
            // atomic VACUUM INTO — handles WAL transparently, does not
            // block in-progress writes. The output file is a valid
            // standalone SQLite DB, restorable as-is.
            val url = "jdbc:sqlite:${srcDb.absolutePath}"
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().use { stmt ->
                    // Escape the ' just in case (path with an apostrophe)
                    val safePath = target.absolutePath.replace("'", "''")
                    stmt.execute("VACUUM INTO '$safePath'")
                }
            }
            lastBackupAtMs = System.currentTimeMillis()
            log.info(
                "Backup created: {} ({} KB)",
                target.name,
                target.length() / 1024
            )
            target
        } catch (e: Exception) {
            log.error("Backup failed: ${e.message}", e)
            null
        }
    }

    /**
     * Lists the existing backups, sorted from most recent to oldest.
     */
    fun list(): List<BackupInfo> {
        val backupDir = ServerConfig.backupDir
        if (!backupDir.exists()) return emptyList()
        return backupDir.listFiles { f -> f.isFile && f.name.endsWith(".db") }
            ?.sortedByDescending { it.lastModified() }
            ?.map {
                BackupInfo(
                    filename  = it.name,
                    sizeBytes = it.length(),
                    createdAt = it.lastModified(),
                    createdAtFormatted = displayFormat.format(Date(it.lastModified()))
                )
            }
            ?: emptyList()
    }

    /**
     * Restores a backup in place. Keeps the old DB as a safety net
     * (`instantiot.db.before-restore-XXX`).
     *
     * **The admin MUST restart the server afterwards so that the
     * Exposed connection pool reloads the new file.**
     *
     * @return Pair (newDbFile, safetyNetFile) if OK, null on failure
     */
    @Synchronized
    fun restore(filename: String): Pair<File, File>? {
        val backupDir = ServerConfig.backupDir
        val src = File(backupDir, filename)
        if (!src.exists() || !src.isFile) {
            log.warn("Restore failed — backup not found: $filename")
            return null
        }
        // Guard against path traversal — we only accept simple
        // names under backupDir (no "../etc/passwd" trick)
        if (src.parentFile.canonicalPath != backupDir.canonicalPath) {
            log.warn("Restore failed — backup path escapes backupDir: $filename")
            return null
        }

        val targetDb = ServerConfig.dbFile
        return try {
            // 1. Snapshot the current DB as a safety net (if it exists)
            val safetyNet = if (targetDb.exists()) {
                val ts = timestampFormat.format(Date())
                val safety = File(targetDb.parentFile, "${targetDb.name}.before-restore-$ts")
                targetDb.copyTo(safety, overwrite = true)
                log.info("Safety snapshot saved: ${safety.name}")
                safety
            } else File("/dev/null")

            // 2. Copy the backup in place
            src.copyTo(targetDb, overwrite = true)

            // 3. Wipe the WAL/SHM files if present (the restore is a
            //    complete DB, old-regime WAL files would confuse SQLite)
            File(targetDb.parentFile, "${targetDb.name}-wal").delete()
            File(targetDb.parentFile, "${targetDb.name}-shm").delete()

            log.warn(
                "Backup restored: {} → {}. RESTART REQUIRED to reload the connection pool.",
                src.name, targetDb.name
            )
            targetDb to safetyNet
        } catch (e: Exception) {
            log.error("Restore failed: ${e.message}", e)
            null
        }
    }

    /**
     * Applies retention: keeps the N most recent, deletes the rest.
     */
    @Synchronized
    fun cleanup() {
        val keepCount = ServerConfig.backupRetentionCount
        val backups = list()
        if (backups.size <= keepCount) return
        val toDelete = backups.drop(keepCount)
        toDelete.forEach { backup ->
            val file = File(ServerConfig.backupDir, backup.filename)
            if (file.delete()) {
                log.info("Pruned old backup: ${backup.filename}")
            } else {
                log.warn("Failed to prune backup: ${backup.filename}")
            }
        }
    }
}

/**
 * Metadata of a backup for the admin panel.
 */
data class BackupInfo(
    val filename: String,
    val sizeBytes: Long,
    val createdAt: Long,           // epoch ms
    val createdAtFormatted: String // YYYY-MM-DD HH:mm:ss user-friendly
)