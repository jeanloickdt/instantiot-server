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

// relay/LastValueCache.kt
package com.jeanloickdt.relay

import java.util.concurrent.ConcurrentHashMap

/**
 * L'identite d'un signal dans les structures RAM du noeud.
 *
 * La cle textuelle est `"deviceId:adresse"` — voir `signalKey()`. Elle est
 * deja unique par carte, mais elle est TOUJOURS accompagnee du compte :
 * deux locataires peuvent posseder deux cartes homonymes le temps d'une
 * migration, et surtout l'isolation ne doit dependre d'aucune propriete des
 * donnees. Un seul champ, et la derniere valeur d'un compte ecraserait celle
 * d'un autre.
 */
data class SignalRef(val ownerId: String, val key: String)

/** Le dernier payload recu (base64) et l'instant ou il est arrive. */
data class LastValue(val payload: String, val at: Long)

/**
 * La valeur courante de chaque signal — ce que l'app lit a la reconnexion,
 * et la seule ecriture que la boucle de lecture fait par trame. Pure RAM,
 * jamais la base.
 *
 * La persistance passe ailleurs : `signals.last_payload` est mis a jour par
 * `touchBuffered`, cote depot. Ce cache-ci ne connait plus de "sale a vider"
 * — le couple `drainDirty`/`evict` servait a remonter `widgets.last_payload`,
 * une table qui n'existe plus.
 *
 * Couture assumee : un deploiement multi-noeud remplace
 * [InMemoryLastValueCache] par une implementation partagee (Redis, par
 * exemple) sans toucher un seul appelant.
 */
interface LastValueCache {
    fun put(ownerId: String, key: String, payload: String, at: Long)
    fun get(ownerId: String, key: String): LastValue?

    /**
     * Les signaux parmi [watched] dont le dernier echantillon est plus vieux
     * que [cutoffMs] — la lecture "le capteur s'est taru".
     *
     * [watched] est passe plutot que de balayer tout le cache : un signal que
     * personne ne surveille ne doit jamais etre releve, et c'est le cote
     * regles qui sait lesquels comptent. Une methode dediee plutot que la
     * carte exposee : la carte est l'affaire de cette classe.
     */
    fun staleSince(cutoffMs: Long, watched: Set<SignalRef>): List<Pair<SignalRef, Long>>
}

class InMemoryLastValueCache : LastValueCache {
    private val values = ConcurrentHashMap<SignalRef, LastValue>()

    override fun put(ownerId: String, key: String, payload: String, at: Long) {
        values[SignalRef(ownerId, key)] = LastValue(payload, at)
    }

    override fun get(ownerId: String, key: String): LastValue? = values[SignalRef(ownerId, key)]

    override fun staleSince(cutoffMs: Long, watched: Set<SignalRef>): List<Pair<SignalRef, Long>> =
        // On parcourt l'ensemble SURVEILLE, pas le cache : les regles d'un
        // noeud se comptent en centaines, les signaux en dizaines de milliers.
        watched.mapNotNull { key ->
            val last = values[key] ?: return@mapNotNull null
            if (last.at < cutoffMs) key to last.at else null
        }
}
