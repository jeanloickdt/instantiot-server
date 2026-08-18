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
    val direction: String,
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

    fun listByDevice(ownerId: String, deviceId: String): List<SignalRow>

    fun listByOwner(ownerId: String): List<SignalRow>

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
        direction: String = "measure",
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
        direction: String? = null,
        type: String? = null,
        nowMs: Long
    ): Boolean

    fun delete(ownerId: String, deviceId: String, address: Int): Boolean

    /** Erases every signal of a board — used when the board is deleted. */
    fun deleteByDevice(ownerId: String, deviceId: String): Int

    /** Erases every signal of an account — used by the purge. */
    fun deleteByOwner(ownerId: String): Int

    /**
     * Records what a board just sent. Written by the relay only.
     * [historised] is returned so the caller knows whether to feed the cascade
     * without a second read.
     */
    fun touch(ownerId: String, deviceId: String, address: Int, payloadB64: String, atMs: Long): Boolean
}
