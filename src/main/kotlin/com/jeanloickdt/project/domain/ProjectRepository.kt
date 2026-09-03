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

// project/domain/ProjectRepository.kt
package com.jeanloickdt.project.domain

/**
 * Les projets, sous trois règles qui ne souffrent aucune exception.
 *
 * **1. `ownerId` en premier, partout.** Pas de surcharge « pratique » sans lui.
 * La vérification d'appartenance était recopiée à la main dans cinq routes ;
 * une sixième qui l'oublie, et un compte lit le projet d'un autre. Le
 * compilateur doit rendre la faute impossible, pas la relecture la rendre
 * détectable — c'est la même règle que `SignalRepository.findById(ownerId, id)`,
 * et pour la même raison.
 *
 * **2. Toute écriture est UNE instruction conditionnelle.** Jamais lire puis
 * écrire. `updateLayout` le faisait, et la garde de version qu'il portait ne
 * protégeait rien sous PostgreSQL : deux téléphones lisaient la même version,
 * la trouvaient bonne, et écrivaient tous les deux. Voir la mesure dans
 * `PostgresContractTest` — trois gagnants sur huit.
 *
 * **3. Toute écriture rend la ligne**, jamais un `Boolean` ni un `String`. Les
 * routes n'ont alors ni seconde requête à faire, ni `!!` à écrire.
 */
interface ProjectRepository {

    /** @return la ligne créée — l'appelant n'a pas à la relire. */
    fun create(ownerId: String, name: String): ProjectRow

    fun findById(ownerId: String, id: String): ProjectRow?

    fun findAllByOwner(ownerId: String): List<ProjectRow>

    /**
     * Les projets d'un compte, **sans leur layout**.
     *
     * La liste sert à afficher des noms. Son pendant complet transportait le
     * champ le plus lourd de chaque projet à chaque ouverture de l'app — et
     * sous PostgreSQL un `TEXT` volumineux est stocké hors ligne (TOAST), donc
     * le servir voulait dire aller le LIRE sur le disque.
     *
     * L'implémentation ne doit pas sélectionner la colonne. Filtrer en Kotlin
     * aurait tout lu pour tout jeter, et n'aurait rien économisé de ce qui
     * coûte.
     */
    fun findAllByOwnerSummary(ownerId: String): List<ProjectSummary>

    /**
     * Renomme, et rend la ligne à jour — `null` si le projet n'existe pas, ou
     * n'appartient pas à ce compte. Les deux se répondent pareil : un 404.
     *
     * **`version` ne bouge PAS, et c'est voulu.** Ce compteur garde le LAYOUT.
     * L'incrémenter sur un renommage provoquerait un 409 sur l'autre téléphone,
     * qui n'a pourtant rien à réconcilier — son plan de tableau de bord est
     * toujours le bon. Ne « corrigez » pas cet oubli : ce n'en est pas un.
     */
    fun updateName(ownerId: String, id: String, name: String): ProjectRow?

    /**
     * Écrit le layout sous concurrence optimiste.
     *
     * [expectedVersion] à `null` garde le comportement d'avant — écrire et
     * espérer. Il n'existe que pour qu'une app pas encore mise à jour continue
     * de fonctionner ; il n'offre aucune protection, et c'est tout l'intérêt
     * de le nommer.
     */
    fun updateLayout(
        ownerId: String,
        id: String,
        layoutJson: String,
        expectedVersion: Int? = null
    ): LayoutWrite

    fun delete(ownerId: String, id: String): Boolean

    /**
     * Tous les projets d'un compte — la suppression de compte.
     *
     * Même raison que son homologue côté cartes : la purge les supprimait un
     * par un. Une instruction FIXE vaut mieux qu'une par projet dans une
     * transaction qui tient des verrous.
     */
    fun deleteAllByOwner(ownerId: String): Int

    /**
     * Tous les projets de tous les comptes — une métrique d'administration.
     *
     * Le nom porte le `All` parce que c'est la seule méthode de cette interface
     * qui ne soit pas cadrée par un compte, et qu'un `count()` au milieu de
     * méthodes scopées se lit comme « les miens ».
     */
    fun countAll(): Long
}

/**
 * What a layout write did.
 *
 * [Conflict] carries the current state so the caller can answer the app with
 * something it can act on — "someone else saved" is useless without "and here
 * is what they saved".
 */
sealed interface LayoutWrite {
    data class Ok(val version: Int) : LayoutWrite
    data class Conflict(val currentVersion: Int, val currentLayoutJson: String) : LayoutWrite
    data object NotFound : LayoutWrite
}
