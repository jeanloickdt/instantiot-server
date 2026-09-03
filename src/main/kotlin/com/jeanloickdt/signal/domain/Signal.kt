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

package com.jeanloickdt.signal.domain

/**
 * The identity of a signal on the wire and in every index: an address, on a
 * board, in an account. Nothing else is ever needed to route a value.
 */
data class SignalKey(val ownerId: String, val deviceId: String, val address: Int)

data class SignalRow(
    /**
     * L'identifiant universel — voir [com.jeanloickdt.signal.data.SignalTable.id].
     * Défaut à `0` pour les tests qui fabriquent une ligne sans se soucier
     * de son identité ; toute ligne réelle passe par [SignalRepository.findById]
     * ou une lecture par triplet, jamais par cette valeur.
     */
    val id: Long = 0L,
    val ownerId: String,
    val deviceId: String,
    val address: Int,
    val label: String,
    val type: String,
    val unit: String,
    val decimals: Int,
    val minValue: Double?,
    val maxValue: Double?,
    val historised: Boolean,
    /** Rejouée à la reconnexion — voir [com.jeanloickdt.signal.data.SignalTable.replayOnConnect]. */
    val replayOnConnect: Boolean = true,
    /** Proposé dans l'éditeur de règles — voir [com.jeanloickdt.signal.data.SignalTable.automationVisible]. */
    val automationVisible: Boolean = true,
    val lastPayload: String?,
    val lastSeenAt: Long?
) {
    val key: SignalKey get() = SignalKey(ownerId, deviceId, address)
}

interface SignalRepository {

    /**
     * The hot path's only question: does this board own this address?
     * A frame for an undeclared address has no recipient — the relay drops it,
     * loudly enough to be diagnosable.
     */
    fun find(ownerId: String, deviceId: String, address: Int): SignalRow?

    /**
     * Résout un signal par son identifiant universel — jamais par lui seul.
     *
     * `id` est un entier global, séquentiel, devinable ; sans `ownerId` dans
     * la question, la réponse appartiendrait à n'importe qui. La clé
     * composite d'avant rendait cette vérification gratuite : elle ne l'est
     * plus, donc elle se fait ici, explicitement, à chaque appel — et cette
     * signature est la SEULE façon d'atteindre une ligne par `id` dans tout
     * le dépôt. Aucune surcharge `findById(id: Long)` ne doit jamais exister
     * : tant qu'elle n'existe pas, personne ne peut l'appeler par
     * distraction.
     */
    fun findById(ownerId: String, id: Long): SignalRow?

    fun listByDevice(ownerId: String, deviceId: String): List<SignalRow>

    fun listByOwner(ownerId: String): List<SignalRow>

    /**
     * How many of this account's signals keep a trace.
     *
     * The quota counts these and not the total: declaring an address costs one
     * row that never grows, keeping its history costs 396 kB a day for as long
     * as the retention says. Only the second is worth a limit.
     */
    fun countHistorised(ownerId: String): Int =
        listByOwner(ownerId).count { it.historised }

    /**
     * Declares a signal. Returns false when the address is already taken on
     * that board — the caller decides whether that is a conflict or a no-op.
     */
    fun create(
        ownerId: String,
        deviceId: String,
        address: Int,
        label: String,
        type: String,
        unit: String = "",
        decimals: Int = 1,
        minValue: Double? = null,
        maxValue: Double? = null,
        historised: Boolean = true,
        replayOnConnect: Boolean = true,
        automationVisible: Boolean = true,
        nowMs: Long
    ): Boolean

    /** The lowest free address on that board, or null when the space is full. */
    fun nextFreeAddress(ownerId: String, deviceId: String): Int?

    /**
     * Modifies a declaration.
     *
     * [type] is editable — but changing it **drops the stored value**, and
     * that is not a detail. `lastPayload` holds bytes encoded with the old
     * type tag; replaying them under the new one would send the board a float
     * read as an int. Dropping costs one value the next write re-establishes;
     * keeping would send 1_102_263_091 to a pump.
     */
    fun update(
        ownerId: String,
        deviceId: String,
        address: Int,
        label: String? = null,
        unit: String? = null,
        decimals: Int? = null,
        minValue: Double? = null,
        maxValue: Double? = null,
        historised: Boolean? = null,
        replayOnConnect: Boolean? = null,
        automationVisible: Boolean? = null,
        type: String? = null,
        nowMs: Long
    ): Boolean

    fun delete(ownerId: String, deviceId: String, address: Int): Boolean

    /** Erases every signal of a board — used when the board is deleted. */
    fun deleteByDevice(ownerId: String, deviceId: String): Int

    /**
     * Erases every signal of SEVERAL boards — used when a project is deleted.
     *
     * The plural exists because the singular was being called in a loop. A
     * hundred boards meant a hundred round trips, inside a transaction holding
     * locks — and the account with a hundred boards is the paying one. Its
     * neighbour on the history side, `deleteAllByDevices`, had already been
     * fixed for exactly this; the fix had stopped one line short.
     *
     * An EMPTY list deletes nothing and returns 0. It must not be turned into
     * `IN ()`, which is a syntax error on most engines — a project with no
     * board would fail to delete.
     */
    fun deleteByDevices(ownerId: String, deviceIds: List<String>): Int

    /** Erases every signal of an account — used by the purge. */
    fun deleteByOwner(ownerId: String): Int

    /**
     * Records a value that must survive a crash — **written through, always**.
     *
     * This is the setpoint path. A setpoint is an intention the user expressed
     * once, and the board finds it again at its next connection: losing the
     * last few seconds of it would replay a stale one and move something
     * physical to the wrong place. Rare enough that a transaction each costs
     * nothing.
     */
    fun touch(ownerId: String, deviceId: String, address: Int, payloadB64: String, atMs: Long): Boolean

    /**
     * Records what a board just sent — **may be buffered**.
     *
     * This is the telemetry path, and it runs on the hottest line of the relay.
     * Losing the last few seconds on an unclean shutdown is the same trade the
     * history buffers already make, and for the same reason: the live value the
     * apps read lives in [com.jeanloickdt.relay.LastValueCache], in RAM. What
     * is written here is only the copy that survives a restart.
     *
     * The default delegates to [touch], so an implementation that has no buffer
     * is correct without doing anything. Only the caching decorator overrides
     * it — see `CachedSignalRepository`.
     */
    fun touchBuffered(ownerId: String, deviceId: String, address: Int, payloadB64: String, atMs: Long): Boolean =
        touch(ownerId, deviceId, address, payloadB64, atMs)

    /**
     * Writes a batch of values in **one transaction**, and returns how many rows
     * were updated.
     *
     * The point is the transaction, not the statement: the fsync, the journal
     * commit and the write lock are paid once per transaction whatever the
     * number of rows inside. Twenty thousand updates in one transaction cost a
     * fraction of twenty thousand transactions of one row.
     *
     * The default loops over [touch], which is correct — just not fast. The
     * Exposed implementation wraps the loop in a single transaction, and a
     * future one can make it a single multi-row upsert.
     */
    fun touchAll(batch: List<com.jeanloickdt.signal.data.SignalTouch>): Int =
        batch.count { touch(it.ownerId, it.deviceId, it.address, it.payloadB64, it.atMs) }
}
