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


package com.jeanloickdt.relay

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AppInboundFuse")

/**
 * Le fusible d'une session app — ce qu'un téléphone peut nous envoyer.
 *
 * ## Le trou que ceci ferme
 *
 * `/ws/app` n'était sous aucun limiteur : le `RateLimit("api")` ne couvre
 * que le HTTP. Chaque `write_signal` faisait un UPDATE et un SELECT, sur le
 * thread Netty, et un curseur glissé à 20 Hz — le cas pour lequel ce chemin
 * a été construit — saturait le pool de six et bloquait les workers que
 * toutes les requêtes HTTP et toutes les sessions se partagent. Un compte
 * suffisait à faire attendre les autres.
 *
 * ## Ce que ça fait
 *
 * Le même seau à jetons que le fusible des cartes ([FrameRateLimiter]), par
 * session : 30 messages par seconde, rafale 60 — dix fois ce qu'un doigt
 * produit, très en dessous d'une boucle. Au-delà : refusé et compté, jamais
 * déconnecté. Un curseur qui dépasse perd quelques positions intermédiaires,
 * pas sa session ; et la position finale, envoyée quand le doigt se lève,
 * passe parce que le seau se remplit à 30 par seconde.
 *
 * Le journal le dit une fois par minute et par session, pas une fois par
 * message refusé.
 */
class AppInboundFuse(
    ratePerSecond: Int = DEFAULT_RATE_PER_SECOND,
    private val logEveryMs: Long = 60_000,
) {
    // Deux secondes de rafale, pas dix : ici le lecteur est Netty, jamais en
    // retard sur une socket, et chaque message admis peut etre une ecriture
    // en base. Le retard de lecture qui justifie la rafale des cartes n'a
    // pas d'equivalent sur ce chemin.
    private val bucket = FrameRateLimiter(ratePerSecond, burst = ratePerSecond.coerceAtLeast(1) * 2)
    var refused: Long = 0
        private set
    private var lastLogAt: Long = 0
    private var refusedAtLastLog: Long = 0

    /** Vrai si le message passe ; sinon il est compté, et l'appelant l'ignore. */
    fun admit(nowMs: Long): Boolean {
        if (bucket.tryAcquire(nowMs)) return true
        refused++
        return false
    }

    /** À appeler après un refus : journalise au plus une fois par [logEveryMs]. */
    fun logIfDue(nowMs: Long, who: String) {
        if (nowMs - lastLogAt < logEveryMs) return
        val since = refused - refusedAtLastLog
        logger.warn("App $who dépasse $DEFAULT_RATE_PER_SECOND messages/s — $since message(s) refusé(s) (total $refused), session gardée")
        lastLogAt = nowMs
        refusedAtLastLog = refused
    }

    companion object {
        const val DEFAULT_RATE_PER_SECOND = 30
    }
}
