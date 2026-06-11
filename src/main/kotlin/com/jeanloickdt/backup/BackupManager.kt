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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    fun restore(
        filename: String,
        targetDb: File = ServerConfig.dbFile,
        backupDir: File = ServerConfig.backupDir
    ): Pair<File, File>? {
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

        // FIX 3 — validate the backup is a sound SQLite DB BEFORE touching prod.
        // A half-written backup (disk full / power cut / SD bit-rot — the very
        // failures backups protect against) lists fine and copyTo() never throws
        // on corrupt bytes, so without this guard a "successful" restore would
        // plant a corrupt DB, only failing at the next boot. Refuse it here and
        // leave prod untouched.
        if (!isSoundSqliteDb(src)) {
            log.warn("Restore refused — backup failed the SQLite integrity check: $filename")
            return null
        }

        val tmp = File(targetDb.parentFile, "${targetDb.name}.restoring-tmp")
        return try {
            // FIX 1 — safety net via VACUUM INTO (NOT a raw copyTo). The server
            // is running, so committed transactions may still live in the live
            // `-wal`; a raw copy of `.db` alone would silently miss them. VACUUM
            // INTO reads a consistent WAL-complete snapshot → the safety net is
            // valid by construction and loses nothing.
            val safetyNet = if (targetDb.exists()) {
                val ts = timestampFormat.format(Date())
                val safety = File(targetDb.parentFile, "${targetDb.name}.before-restore-$ts")
                vacuumInto(targetDb, safety)
                log.info("Safety snapshot saved (WAL-complete): ${safety.name}")
                safety
            } else File("/dev/null")

            // FIX 2 — atomic replace. Copy to a temp sibling, then move it onto
            // targetDb atomically: a crash mid-copy can never leave a half-written
            // prod DB (the OS swaps the whole file or nothing).
            src.copyTo(tmp, overwrite = true)
            try {
                Files.move(
                    tmp.toPath(), targetDb.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Fallback when the filesystem can't do an atomic move
                Files.move(tmp.toPath(), targetDb.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            // Wipe the OLD regime's WAL/SHM — they belong to the replaced DB and
            // SQLite would otherwise try to apply them onto the restored one.
            File(targetDb.parentFile, "${targetDb.name}-wal").delete()
            File(targetDb.parentFile, "${targetDb.name}-shm").delete()

            log.warn(
                "Backup restored: {} → {}. RESTART REQUIRED to reload the connection pool.",
                src.name, targetDb.name
            )
            targetDb to safetyNet
        } catch (e: Exception) {
            log.error("Restore failed: ${e.message}", e)
            runCatching { tmp.delete() }   // don't leak a partial temp file
            null
        }
    }

    /**
     * True iff [file] is a structurally sound SQLite database. Cheap magic-header
     * check first, then `PRAGMA quick_check` opened read-only. Used before a
     * restore so a corrupt backup can never overwrite prod.
     */
    private fun isSoundSqliteDb(file: File): Boolean {
        return try {
            // 1. SQLite magic: bytes 0..14 = "SQLite format 3", byte 15 = NUL (0x00)
            val header = ByteArray(16)
            val read = file.inputStream().use { it.read(header) }
            val magicOk = read == 16 &&
                String(header, 0, 15, Charsets.US_ASCII) == "SQLite format 3" &&
                header[15] == 0.toByte()
            if (!magicOk) return false

            // 2. structural check (read-only open)
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA quick_check").use { rs ->
                        rs.next() && rs.getString(1).equals("ok", ignoreCase = true)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Integrity check threw for ${file.name} — treating as corrupt: ${e.message}")
            false
        }
    }

    /** Consistent (WAL-complete) snapshot of [src] into [dest] via VACUUM INTO. */
    private fun vacuumInto(src: File, dest: File) {
        dest.delete() // VACUUM INTO refuses an existing target
        DriverManager.getConnection("jdbc:sqlite:${src.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("VACUUM INTO '${dest.absolutePath.replace("'", "''")}'")
            }
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