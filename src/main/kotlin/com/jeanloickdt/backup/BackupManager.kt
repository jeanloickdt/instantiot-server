package com.jeanloickdt.backup

import com.jeanloickdt.common.ServerConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.DriverManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gère les snapshots SQLite du serveur — protège le maker contre la
 * perte de données en cas de :
 *   - Corruption SQLite (rare mais arrive sur SD card de Pi)
 *   - Disque qui meurt
 *   - Erreur humaine (`rm -rf` malheureux)
 *   - Restore après mauvais update
 *
 * **Snapshot** : `VACUUM INTO 'backup.db'` — atomique, gère WAL, ne
 * bloque pas les writes en cours. Output dans `~/.instantiot/backups/`
 * sous le nom `instantiot-YYYY-MM-DD-HHmm.db`.
 *
 * **Restore** : copie le backup choisi en place de `instantiot.db`,
 * en gardant l'ancienne DB sous `.before-restore-XXX` comme safety
 * net. Restart serveur requis (Exposed maintient un connection pool
 * qui n'aime pas qu'on swap le fichier sous lui).
 *
 * **Lifecycle** : [start] lance une coroutine périodique qui snapshot
 * + cleanup selon `ServerConfig.backupIntervalHours`. [stop] cancel.
 *
 * **Thread-safety** : un seul snapshot peut tourner à la fois (sync
 * sur `this`). Évite les snapshots concurrents si l'admin clique
 * "Backup now" pendant que la cron tourne.
 */
object BackupManager {

    private val log = LoggerFactory.getLogger("BackupManager")

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)
    private val displayFormat   = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Timestamp ms du dernier snapshot réussi (pour affichage admin panel). */
    @Volatile var lastBackupAtMs: Long = 0L
        private set

    /**
     * Snapshot immédiat. Idempotent côté nom de fichier — si appelé 2×
     * dans la même minute, le 2e écrase le 1er (ms suffit pas dans le nom).
     *
     * @return File du backup créé, ou null si échec
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
            // VACUUM INTO atomique — gère le WAL transparent, ne bloque
            // pas les writes en cours. Le fichier output est une DB
            // SQLite valide standalone, restorable telle quelle.
            val url = "jdbc:sqlite:${srcDb.absolutePath}"
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().use { stmt ->
                    // Échapper les ' au cas où (path avec apostrophe)
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
     * Liste les backups existants, triés du plus récent au plus ancien.
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
     * Restore un backup en place. Garde l'ancienne DB en safety net
     * (`instantiot.db.before-restore-XXX`).
     *
     * **L'admin DOIT restart le serveur après pour que le pool de
     * connexions Exposed reload le nouveau fichier.**
     *
     * @return Pair (newDbFile, safetyNetFile) si OK, null si échec
     */
    @Synchronized
    fun restore(filename: String): Pair<File, File>? {
        val backupDir = ServerConfig.backupDir
        val src = File(backupDir, filename)
        if (!src.exists() || !src.isFile) {
            log.warn("Restore failed — backup not found: $filename")
            return null
        }
        // Guard contre path traversal — on n'accepte que des noms
        // simples sous backupDir (pas de "../etc/passwd" trick)
        if (src.parentFile.canonicalPath != backupDir.canonicalPath) {
            log.warn("Restore failed — backup path escapes backupDir: $filename")
            return null
        }

        val targetDb = ServerConfig.dbFile
        return try {
            // 1. Snapshot la DB courante en safety net (si elle existe)
            val safetyNet = if (targetDb.exists()) {
                val ts = timestampFormat.format(Date())
                val safety = File(targetDb.parentFile, "${targetDb.name}.before-restore-$ts")
                targetDb.copyTo(safety, overwrite = true)
                log.info("Safety snapshot saved: ${safety.name}")
                safety
            } else File("/dev/null")

            // 2. Copy le backup en place
            src.copyTo(targetDb, overwrite = true)

            // 3. Wipe les WAL/SHM files si présents (le restore est une
            //    DB complète, les WAL files ancien régime vont confuser SQLite)
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
     * Applique la rétention : garde les N plus récents, supprime le reste.
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
 * Métadonnées d'un backup pour l'admin panel.
 */
data class BackupInfo(
    val filename: String,
    val sizeBytes: Long,
    val createdAt: Long,           // epoch ms
    val createdAtFormatted: String // YYYY-MM-DD HH:mm:ss user-friendly
)
