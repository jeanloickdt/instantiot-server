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
 * Effacer l'historique — le contrat des cascades, et rien d'autre.
 *
 * ## Pourquoi une interface étroite plutôt qu'un miroir
 *
 * Le dépôt d'historique porte dix-sept méthodes publiques : écriture par lots,
 * lectures par plage, dérivation, balayages de rétention. Ses **appelants de
 * cascade** — la suppression d'un projet, la purge d'un compte — n'en utilisent
 * que deux, et n'ont aucune raison de voir le reste.
 *
 * Une route déclarait la classe concrète dans sa signature alors que toutes ses
 * autres dépendances étaient des interfaces. Le miroir complet aurait corrigé
 * la forme sans corriger le fond : ce qu'une route de projet doit savoir de
 * l'historique, c'est comment l'effacer.
 *
 * ## L'ordre, qui n'est pas négociable
 *
 * Chaque table d'historique porte une clé étrangère vers `signals.id`.
 * **Effacer l'historique AVANT les signaux**, toujours. Dans l'autre sens la
 * contrainte refuse, et la transaction entière de la purge tombe avec elle.
 */
interface SignalHistoryPurge {

    /**
     * Tout l'historique d'un compte — la suppression de compte.
     *
     * `owner_id` est dénormalisé sur chaque ligne précisément pour que ce
     * balayage n'ait aucune jointure à faire.
     */
    fun deleteAllByOwner(ownerId: String): Int

    /**
     * L'historique des signaux portés par ces cartes — la suppression d'un
     * projet, ou d'une carte.
     *
     * **Par cartes, pas par identifiants de signaux**, et c'est ce qui la
     * distingue de la version d'avant. L'appelant résolvait lui-même les
     * signaux : une requête par carte, puis une liste de tous leurs
     * identifiants passée en `IN (…)`. À cent cartes, cent allers-retours et
     * une clause de plusieurs milliers d'éléments — que certains moteurs
     * refusent de planifier correctement.
     *
     * Ici le moteur fait la jointure : une instruction par table, quelle que
     * soit la taille du projet.
     */
    fun deleteAllByDevices(ownerId: String, deviceIds: List<String>): Int
}
