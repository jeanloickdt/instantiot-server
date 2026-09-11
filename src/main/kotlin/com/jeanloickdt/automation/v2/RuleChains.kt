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


package com.jeanloickdt.automation.v2

/**
 * Les chaînes entre règles — ce que [selfTriggeringSignals] ne voit pas.
 *
 * Une règle seule qui écrit ce qu'elle regarde est avertie à la création.
 * Deux règles qui se relaient — A écrit S1 que B regarde, B écrit S2 que A
 * regarde — ne l'étaient pas : chacune, prise seule, est innocente. Avec une
 * carte qui renvoie sa consigne, c'est la même boucle sans fin, à deux.
 *
 * Même réponse que pour la boucle simple : on avertit, on ne bloque pas.
 * Un asservissement à plusieurs règles est un usage légitime ; le plafond de
 * commandes par compte borne ce qu'il peut coûter s'il déraille.
 */
object RuleChains {
    /** Ce que la règle écrit, en clés `deviceId:adresse`. */
    fun RuleLogic.writtenSignalKeys(): Set<String> =
        actions.filterIsInstance<Action.SetSignal>().map { it.target.signalKey }.toSet()

    /** Ce que le DÉCLENCHEUR regarde — les conditions ne réveillent pas une règle. */
    fun RuleLogic.watchedSignalKeys(): Set<String> =
        trigger.signalRefs().map { it.signalKey }.toSet()

    /**
     * Les noms des règles avec lesquelles [candidate] forme une chaîne fermée,
     * dans l'ordre du chemin — vide s'il n'y en a pas. [candidate] n'est pas
     * dans [others] ; sa propre boucle est le travail de [selfTriggeringSignals].
     */
    fun chainsThrough(candidate: RuleLogic, others: List<Pair<String, RuleLogic>>): List<String> {
        val writes = candidate.writtenSignalKeys()
        val watches = candidate.watchedSignalKeys()
        if (writes.isEmpty() || watches.isEmpty()) return emptyList()

        // Profondeur d'abord depuis le candidat : une règle est atteinte si
        // ce qu'on vient d'écrire est ce qu'elle regarde ; la chaîne se ferme
        // quand ce qu'une règle écrit est ce que le candidat regarde.
        val visited = HashSet<Int>()
        fun walk(written: Set<String>, path: List<String>): List<String>? {
            if (written.any { it in watches }) return path
            for ((i, other) in others.withIndex()) {
                if (i in visited) continue
                val (name, logic) = other
                if (logic.watchedSignalKeys().none { it in written }) continue
                val next = logic.writtenSignalKeys()
                if (next.isEmpty()) continue
                visited += i
                walk(next, path + name)?.let { return it }
            }
            return null
        }
        return walk(writes, emptyList()).orEmpty()
    }
}
