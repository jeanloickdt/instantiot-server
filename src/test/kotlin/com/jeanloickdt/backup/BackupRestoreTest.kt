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

import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the restore-hardening invariants on the most destructive operation in
 * the system. All cases use isolated temp files (BackupManager.restore takes
 * explicit targetDb/backupDir), so the real prod DB is never touched.
 */
class BackupRestoreTest {

    private lateinit var dir: File
    private lateinit var backupDir: File
    private lateinit var prod: File

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("instantiot-restore-test-").toFile()
        backupDir = File(dir, "backups").apply { mkdirs() }
        prod = File(dir, "instantiot.db")
    }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    // ── case 1: a CORRUPT backup is refused and prod is left intact ──
    @Test
    fun `restoring a corrupt backup is refused and leaves prod untouched`() {
        makeDb(prod, "PROD-DATA")
        // a truncated copy of a real DB: valid magic header, corrupt body
        val good = File(backupDir, "good.db").also { makeDb(it, "X") }
        val corrupt = File(backupDir, "corrupt.db")
        val bytes = good.readBytes()
        corrupt.writeBytes(bytes.copyOf(bytes.size / 2))  // half a DB → integrity_check fails

        val result = BackupManager.restore("corrupt.db", targetDb = prod, backupDir = backupDir)

        assertNull(result, "a corrupt backup must be refused")
        assertEquals("PROD-DATA", readMarker(prod), "prod must be untouched after a refused restore")
        // no half-written temp file left behind
        assertTrue(File(prod.parentFile, "${prod.name}.restoring-tmp").let { !it.exists() })
    }

    // ── case 2: a pure-garbage file (no SQLite magic) is refused ──
    @Test
    fun `restoring a non-sqlite file is refused`() {
        makeDb(prod, "PROD-DATA")
        File(backupDir, "garbage.db").writeBytes("this is not a database at all".toByteArray())

        val result = BackupManager.restore("garbage.db", targetDb = prod, backupDir = backupDir)

        assertNull(result)
        assertEquals("PROD-DATA", readMarker(prod))
    }

    // ── case 3: a valid restore swaps prod and keeps the old data in the safety net ──
    @Test
    fun `a valid restore replaces prod and preserves the previous data in the safety net`() {
        makeDb(prod, "OLD-PROD")
        File(backupDir, "snap.db").also { makeDb(it, "FROM-BACKUP") }

        val result = BackupManager.restore("snap.db", targetDb = prod, backupDir = backupDir)

        assertNotNull(result)
        val (restored, safetyNet) = result
        assertEquals("FROM-BACKUP", readMarker(restored), "prod now holds the backup's data")
        assertEquals("OLD-PROD", readMarker(safetyNet), "the safety net holds the pre-restore data")
    }

    // ── case 4 (the real bug): the safety net is WAL-complete ──
    @Test
    fun `the safety net captures transactions still living in the live WAL`() {
        // Build prod as a WAL database with a row committed but NOT checkpointed:
        // keep the connection OPEN so the row stays in the -wal (not folded into .db).
        val live = DriverManager.getConnection("jdbc:sqlite:${prod.absolutePath}")
        live.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("CREATE TABLE marker(v TEXT)")
            s.execute("INSERT INTO marker VALUES('IN-WAL')")  // committed → lives in -wal
        }
        // sanity: the row really is in the WAL, not yet in the .db sidecar-less file
        assertTrue(File("${prod.absolutePath}-wal").exists(), "the -wal sidecar must exist")

        File(backupDir, "snap.db").also { makeDb(it, "FROM-BACKUP") }
        val result = BackupManager.restore("snap.db", targetDb = prod, backupDir = backupDir)
        live.close()

        assertNotNull(result)
        val safetyNet = result.second
        // A raw copyTo(.db) would MISS 'IN-WAL'. VACUUM INTO captures it →
        // the safety net is complete.
        assertEquals("IN-WAL", readMarker(safetyNet),
            "the WAL-complete safety net must contain the transaction that was still in the WAL")
    }

    // ── helpers ──
    private fun makeDb(file: File, marker: String) {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE marker(v TEXT)")
                s.execute("INSERT INTO marker VALUES('$marker')")
            }
        }
    }

    private fun readMarker(file: File): String? =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT v FROM marker LIMIT 1").use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }
}
