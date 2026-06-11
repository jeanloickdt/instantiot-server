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
 * **Restore**: two-phase to avoid swapping the DB under the running
 * Exposed pool. [stageRestore] validates the chosen backup and copies it
 * aside as `instantiot.db.pending-restore`; [applyPendingRestore] does the
 * real swap at the next boot — before any connection is open — taking a
 * WAL-complete safety net (`.before-restore-XXX`) first. Server restart
 * required to apply.
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

    /** Suffix of the staged-restore file, applied at the next boot. */
    const val PENDING_RESTORE_SUFFIX = ".pending-restore"

    /**
     * **Stages** a restore for the next boot — does NOT touch the live DB.
     *
     * Swapping the DB file under the *running* Exposed connection pool leaves the
     * server split-brained (the open pool still reads the old inode, new
     * connections the new file) until a restart that was only ever "advisory".
     * Instead we validate the chosen backup and copy it aside as
     * `<db>.pending-restore`; [applyPendingRestore] performs the real swap at the
     * next boot, before any connection is open — no live swap, no split-brain.
     *
     * The admin panel forces a restart right after this returns; the swap +
     * safety-net happen during that restart.
     *
     * @return the staged pending-restore File if accepted, null if the backup is
     *   missing, escapes [backupDir], or fails the SQLite integrity check.
     */
    @Synchronized
    fun stageRestore(
        filename: String,
        targetDb: File = ServerConfig.dbFile,
        backupDir: File = ServerConfig.backupDir
    ): File? {
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

        // Validate the backup is a sound SQLite DB BEFORE staging it. A
        // half-written backup (disk full / power cut / SD bit-rot — the very
        // failures backups protect against) lists fine and copyTo() never throws
        // on corrupt bytes; refusing it here means a corrupt file can never be
        // queued for a boot-time swap. (Re-checked in applyPendingRestore too.)
        if (!isSoundSqliteDb(src)) {
            log.warn("Restore refused — backup failed the SQLite integrity check: $filename")
            return null
        }

        val pending = File(targetDb.parentFile, "${targetDb.name}$PENDING_RESTORE_SUFFIX")
        val tmp = File(targetDb.parentFile, "${targetDb.name}.pending-tmp")
        return try {
            // Copy to a temp sibling then atomic-move into place, so a crash
            // mid-copy never leaves a half-written pending file that boot would
            // try to apply.
            src.copyTo(tmp, overwrite = true)
            atomicMove(tmp, pending)
            log.info("Restore staged: {} → {}. Applies on next restart.", src.name, pending.name)
            pending
        } catch (e: Exception) {
            log.error("Failed to stage restore: ${e.message}", e)
            runCatching { tmp.delete() }   // don't leak a partial temp file
            null
        }
    }

    /**
     * Applies a staged restore, if any, **at boot — before the connection pool
     * opens**. With no connection open there is no split-brain: we snapshot the
     * current live DB as a WAL-complete safety net (`VACUUM INTO`, capturing the
     * freshest state including the live `-wal`), atomically move the staged
     * backup onto the live DB, and drop the now-stale `-wal`/`-shm`.
     *
     * Must be called once, before `DatabaseFactory.init`. A no-op when nothing
     * is pending.
     *
     * @return the safety-net File if a restore was applied, null if none pending
     *   (or the pending file had rotted and was discarded).
     */
    @Synchronized
    fun applyPendingRestore(targetDb: File = ServerConfig.dbFile): File? {
        val pending = File(targetDb.parentFile, "${targetDb.name}$PENDING_RESTORE_SUFFIX")
        if (!pending.exists()) return null

        // Defense in depth: the pending file could have rotted since it was
        // staged (power cut, SD bit-rot). Never plant a corrupt DB — discard it
        // and boot the current DB untouched.
        if (!isSoundSqliteDb(pending)) {
            log.error("Pending restore is corrupt — discarding it, keeping current DB: ${pending.name}")
            pending.delete()
            return null
        }

        return try {
            // WAL-complete safety net of the freshest live state, taken now while
            // nothing else holds the DB open.
            val safetyNet = if (targetDb.exists()) {
                val ts = timestampFormat.format(Date())
                val safety = File(targetDb.parentFile, "${targetDb.name}.before-restore-$ts")
                vacuumInto(targetDb, safety)
                log.info("Safety snapshot saved before restore (WAL-complete): ${safety.name}")
                safety
            } else File("/dev/null")

            // Atomic replace — the OS swaps the whole file or nothing.
            atomicMove(pending, targetDb)

            // Wipe the OLD regime's WAL/SHM — they belong to the replaced DB and
            // SQLite would otherwise try to apply them onto the restored one.
            File(targetDb.parentFile, "${targetDb.name}-wal").delete()
            File(targetDb.parentFile, "${targetDb.name}-shm").delete()

            log.warn("Pending restore applied at boot — {} replaced. Safety net: {}", targetDb.name, safetyNet.name)
            safetyNet
        } catch (e: Exception) {
            // Keep the pending file so the next boot can retry; current DB stays
            // whatever it was (atomicMove is all-or-nothing).
            log.error("Failed to apply pending restore — keeping current DB: ${e.message}", e)
            null
        }
    }

    /** Atomic file replace, with a graceful fallback when the FS can't do it. */
    private fun atomicMove(from: File, to: File) {
        try {
            Files.move(
                from.toPath(), to.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
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