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

// signal/data/CachedSignalRepository.kt
package com.jeanloickdt.signal.data

import com.jeanloickdt.signal.domain.SignalRepository
import com.jeanloickdt.signal.domain.SignalRow
import java.util.concurrent.ConcurrentHashMap

/**
 * Takes the database off the relay's hottest line.
 *
 * Every SIGNAL frame used to cost **two transactions**: a `SELECT` to find
 * the signal, then an `UPDATE` to store what arrived — two round trips on the
 * hottest path there is, competing with the flush loop and the retention
 * sweep. The widget path next door
 * has always done neither: a `Set` lookup in RAM and a buffer append.
 *
 * This decorator closes that gap, and it is written to stay right after the
 * move to PostgreSQL. There, a transaction per frame becomes a **network round
 * trip** rather than an in-process read — so both halves below matter more
 * after the migration, not less.
 *
 * ## Why the memory cost is not a concern
 *
 * Both maps hold **one entry per signal**, never one per frame: a board sending
 * fifty times a second overwrites the same entry fifty times, for free. The
 * size follows the inventory, not the traffic — roughly 4 MB at 400 paying
 * accounts, 42 MB at 4 200. That is the opposite of the history buffers, which
 * do grow with traffic between two flushes.
 *
 * ## Absence is cached too
 *
 * A board writing to an address nobody declared is the loop case — a firmware
 * typo repeating forever. Caching only what exists would leave exactly that
 * worst case paying a `SELECT` per frame, so a miss is remembered as a miss.
 *
 * ## What invalidates
 *
 * Every mutation goes through this interface, so every mutation is seen here.
 * The one that must **not** invalidate is a value arriving: it changes what the
 * signal holds, never what the signal is.
 */
class CachedSignalRepository(
    private val delegate: SignalRepository
) : SignalRepository {

    /** `null` means "looked up, and it does not exist" — a remembered absence. */
    private val rows = ConcurrentHashMap<String, Optional>()

    /** Signals whose last value has changed in RAM and not yet reached the disk. */
    private val dirty = ConcurrentHashMap<String, Pending>()

    private class Optional(val row: SignalRow?)

    private class Pending(
        val ownerId: String,
        val deviceId: String,
        val address: Int,
        @Volatile var payloadB64: String,
        @Volatile var atMs: Long
    )

    private fun key(ownerId: String, deviceId: String, address: Int) =
        "$ownerId|$deviceId|$address"

    // ── The read the relay does on every frame ────────────────────────────

    override fun find(ownerId: String, deviceId: String, address: Int): SignalRow? =
        rows.computeIfAbsent(key(ownerId, deviceId, address)) {
            Optional(delegate.find(ownerId, deviceId, address))
        }.row

    // ── The write the relay does on every frame ───────────────────────────

    /**
     * Buffers the value and returns immediately.
     *
     * Returns `true` when the signal exists, which is what the caller means by
     * the result — it never claims the row reached the disk.
     */
    override fun touchBuffered(
        ownerId: String, deviceId: String, address: Int, payloadB64: String, atMs: Long
    ): Boolean {
        val k = key(ownerId, deviceId, address)
        val row = find(ownerId, deviceId, address) ?: return false

        // The cached row is updated NOW, not at the flush.
        //
        // The invariant is worth stating because it is what makes this cache
        // safe to reason about: **the cache is always truthful, and the buffer
        // is only about durability.** Refreshing at flush time instead would
        // leave `find()` handing out a stale `lastPayload` for up to a flush
        // period — a lie sitting in memory waiting for somebody to believe it.
        rows[k] = Optional(row.copy(lastPayload = payloadB64, lastSeenAt = atMs))

        // Overwrite in place rather than allocating: the same signal is touched
        // again a few milliseconds later, and this is the hot path.
        val existing = dirty[k]
        if (existing != null) {
            existing.payloadB64 = payloadB64
            existing.atMs = atMs
        } else {
            dirty[k] = Pending(ownerId, deviceId, address, payloadB64, atMs)
        }
        return true
    }

    /**
     * Writes every buffered value in **one transaction**, and returns how many.
     *
     * Called by the flush loop that already drains the history buffers. The
     * gain is not in avoiding the `UPDATE` — it is in avoiding the transaction
     * around it: the fsync and the lock are paid per transaction, not per row.
     *
     * Draining before writing is deliberate. A value arriving mid-flush lands
     * in a fresh entry and goes out on the next round, rather than being
     * dropped because its key was cleared after the read.
     */
    fun flushPendingValues(): Int {
        if (dirty.isEmpty()) return 0
        val batch = dirty.keys.mapNotNull { dirty.remove(it) }
        if (batch.isEmpty()) return 0
        return delegate.touchAll(batch.map {
            SignalTouch(it.ownerId, it.deviceId, it.address, it.payloadB64, it.atMs)
        })
    }

    /** How many values are waiting — for the flush loop's own logging. */
    fun pendingCount(): Int = dirty.size

    // ── Everything that changes what a signal IS evicts ───────────────────

    override fun create(
        ownerId: String, deviceId: String, address: Int, label: String, type: String,
        unit: String, decimals: Int, minValue: Double?, maxValue: Double?,
        historised: Boolean, replayOnConnect: Boolean,
        automationVisible: Boolean, nowMs: Long
    ): Boolean = delegate.create(
        ownerId, deviceId, address, label, type, unit, decimals, minValue, maxValue,
        historised, replayOnConnect, automationVisible, nowMs
    ).also { evict(ownerId, deviceId, address) }

    override fun update(
        ownerId: String, deviceId: String, address: Int, label: String?, unit: String?,
        decimals: Int?, minValue: Double?, maxValue: Double?, historised: Boolean?,
        replayOnConnect: Boolean?, automationVisible: Boolean?,
        type: String?, nowMs: Long
    ): Boolean = delegate.update(
        ownerId, deviceId, address, label, unit, decimals, minValue, maxValue,
        historised, replayOnConnect, automationVisible, type, nowMs
    ).also { evict(ownerId, deviceId, address) }

    override fun delete(ownerId: String, deviceId: String, address: Int): Boolean =
        delegate.delete(ownerId, deviceId, address).also { evict(ownerId, deviceId, address) }

    override fun deleteByDevice(ownerId: String, deviceId: String): Int =
        delegate.deleteByDevice(ownerId, deviceId).also { evictWhere { it.startsWith("$ownerId|$deviceId|") } }

    override fun deleteByDevices(ownerId: String, deviceIds: List<String>): Int =
        delegate.deleteByDevices(ownerId, deviceIds).also {
            // Une seule traversee du cache pour toutes les cartes, plutot
            // qu'une par carte : `deviceIds` est un ensemble, et la cle porte
            // le format `owner|device|address`.
            val prefixes = deviceIds.map { id -> "$ownerId|$id|" }
            evictWhere { key -> prefixes.any { key.startsWith(it) } }
        }

    override fun deleteByOwner(ownerId: String): Int =
        delegate.deleteByOwner(ownerId).also { evictWhere { it.startsWith("$ownerId|") } }

    /**
     * A setpoint — written through, and it evicts nothing.
     *
     * It changes `lastPayload`, which the cached row also carries, so the entry
     * is refreshed rather than kept: a stale `lastPayload` would be replayed to
     * the board on its next connection.
     */
    override fun touch(
        ownerId: String, deviceId: String, address: Int, payloadB64: String, atMs: Long
    ): Boolean = delegate.touch(ownerId, deviceId, address, payloadB64, atMs)
        .also { evict(ownerId, deviceId, address) }

    // ── Reads that are not on the hot path go straight through ────────────

    // Pas dans le cache par triplet : `id` n'est pas la clé sur laquelle ce
    // cache est bâti, et rien n'appelle encore ce chemin par frame. Mettre
    // en cache une lecture qui n'est pas chaude serait de la complexité pour
    // rien — voir l'étape qui câble l'historique sur `signal_id` pour la
    // décision de le faire ou non.
    override fun findById(ownerId: String, id: Long): SignalRow? = delegate.findById(ownerId, id)

    override fun listByDevice(ownerId: String, deviceId: String): List<SignalRow> =
        delegate.listByDevice(ownerId, deviceId)

    override fun listByOwner(ownerId: String): List<SignalRow> = delegate.listByOwner(ownerId)

    override fun countHistorised(ownerId: String): Int = delegate.countHistorised(ownerId)

    override fun nextFreeAddress(ownerId: String, deviceId: String): Int? =
        delegate.nextFreeAddress(ownerId, deviceId)

    // ── Eviction ──────────────────────────────────────────────────────────

    private fun evict(ownerId: String, deviceId: String, address: Int) {
        rows.remove(key(ownerId, deviceId, address))
    }

    private fun evictWhere(predicate: (String) -> Boolean) {
        rows.keys.filter(predicate).forEach { rows.remove(it) }
        // A value waiting for a signal that no longer exists would fail its
        // UPDATE silently on the next round. Drop it with the row.
        dirty.keys.filter(predicate).forEach { dirty.remove(it) }
    }

    /** Test seam — a fresh cache without rebuilding the repository. */
    internal fun clearForTest() {
        rows.clear()
        dirty.clear()
    }
}

/** One buffered value, on its way to disk. */
data class SignalTouch(
    val ownerId: String,
    val deviceId: String,
    val address: Int,
    val payloadB64: String,
    val atMs: Long
)
