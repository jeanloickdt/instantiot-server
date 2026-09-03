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

// common/Dispatchers.kt
package com.jeanloickdt.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * The network and the storage never share a thread pool.
 *
 * Both used to run on `Dispatchers.IO`: the ktor-network selector, the six
 * background loops, the outbox consumers, the device authentication lookup and
 * every database write. That pool holds 64 threads, and a writer waiting on
 * the database occupies one of them for as long as the wait lasts.
 *
 * The failure it produces is the one nobody diagnoses correctly, because the
 * symptom names the wrong culprit: **the database is slow, so boards stop
 * connecting.** Writers fill the pool, the `SelectorManager` is starved, and
 * accepts and socket reads stall — on a machine whose network is perfectly
 * idle.
 *
 * ## Pourquoi PostgreSQL rend la separation plus necessaire, pas moins
 *
 * Une ecriture PostgreSQL est un aller-retour reseau *plus* une attente sur le
 * pool JDBC. Partager un repartiteur couplerait deux attentes reseau, avec des
 * latences plus longues et bien plus variables que celles d'un disque.
 *
 * ## The sizing rule
 *
 *     write dispatcher size == JDBC pool size
 *
 * Never larger. A thread that cannot obtain a connection is a thread blocked by
 * construction: it consumes a stack and a scheduler slot to wait for something
 * arithmetically unavailable. Our write path is already batched — one
 * transaction every five seconds — so throughput comes from batch size, not
 * from parallelism, and a narrow pool is the right shape.
 *
 * The selector is sized on cores because it is purely non-blocking: it demuxes
 * ready sockets and hands the work off. More threads there buy nothing.
 */
object ServerDispatchers {

    private fun named(prefix: String): ThreadFactory {
        val counter = AtomicInteger(1)
        return ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${counter.getAndIncrement()}").apply {
                // Daemon: these must never hold the JVM open at shutdown. The
                // final flush is registered separately and runs before exit.
                isDaemon = true
            }
        }
    }

    /** How many cores the JVM believes it has, floored so a 1-vCPU box still works. */
    private val cores = maxOf(2, Runtime.getRuntime().availableProcessors())

    /**
     * The ktor-network selector. Non-blocking by contract — nothing here may
     * ever touch the database or a file.
     */
    val network: CoroutineDispatcher =
        Executors.newFixedThreadPool(cores, named("iot-net")).asCoroutineDispatcher()

    /**
     * Every database write: the flush loop, the retention sweep, presence.
     *
     * Sized to [DB_POOL_SIZE] so the dispatcher and the JDBC pool are the same
     * width. Raising one without the other is how a pool starves quietly.
     */
    val storage: CoroutineDispatcher =
        Executors.newFixedThreadPool(DB_POOL_SIZE, named("iot-db")).asCoroutineDispatcher()

    /**
     * The JDBC pool width, and the write dispatcher's with it.
     *
     * Deliberately narrow. PostgreSQL does not reward wide pools — each
     * connection is a backend process, and past a handful of concurrent writers
     * the contention costs more than the parallelism returns.
     */
    const val DB_POOL_SIZE = 6

    /** For the boot log — the one place someone will look to check the shape. */
    fun describe(): String =
        "dispatchers: network=$cores threads · storage=$DB_POOL_SIZE threads (= JDBC pool)"
}
