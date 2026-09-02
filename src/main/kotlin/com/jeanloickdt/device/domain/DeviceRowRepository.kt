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

package com.jeanloickdt.device.domain

// device/domain/DeviceRowRepository.kt

/**
 * Les cartes, sous les mêmes trois règles que les projets.
 *
 * **1. `ownerId` en premier.** Une carte se consulte depuis quatre modules —
 * ses routes, les règles, le moteur d'automatisation, les signaux. La
 * vérification d'appartenance était recopiée à la main **huit fois**, dans
 * cinq fichiers. Une neuvième qui l'oublie, et un compte pilote la carte d'un
 * autre. Le compilateur doit rendre la faute impossible.
 *
 * **2. Une écriture, une instruction.** Déjà vrai ici — aucune méthode ne lit
 * puis écrit. La règle est écrite pour que la prochaine naisse correcte.
 *
 * **3. Une écriture rend sa ligne.** Cinq méthodes ne rendaient rien : un
 * appelant ne pouvait pas savoir si l'écriture avait porté. Renommer une carte
 * inexistante réussissait en silence, et la route répondait 200.
 *
 * ## Ce qui n'est PAS cadré par un compte, et pourquoi
 *
 * [findByTokenHash] établit l'identité au lieu de la supposer : c'est par lui
 * qu'une carte prouve qui elle est, donc il ne peut pas exiger de savoir à qui
 * elle appartient. C'est la seule lecture par identifiant sans propriétaire, et
 * elle n'est atteignable que depuis la poignée de main du relais.
 *
 * Les trois lectures d'administration portent `All` dans leur nom pour que leur
 * absence de cadrage se voie à l'appel, et non seulement à la lecture du corps.
 *
 * L'écriture de présence vit dans [DevicePresenceWriter] — un contrat séparé,
 * avec sa propre justification.
 */
interface DeviceRepository {

    /** @return la ligne créée — l'appelant n'a pas à la relire. */
    fun create(
        ownerId: String,
        name: String,
        projectId: String,
        tokenHash: String,
        deviceType: DeviceType,
        connectivity: DeviceConnectivity
    ): DeviceRow

    fun findById(ownerId: String, id: String): DeviceRow?

    /**
     * La carte qui présente ce jeton — **sans propriétaire, et c'est le point**.
     *
     * C'est l'entrée de la poignée de main : elle ÉTABLIT l'identité du compte
     * plutôt que de la supposer. Exiger un `ownerId` ici reviendrait à demander
     * la réponse avant de poser la question.
     *
     * Aucun autre appelant que le relais n'a de raison de s'en servir.
     */
    fun findByTokenHash(tokenHash: String): DeviceRow?

    fun findAllByOwner(ownerId: String): List<DeviceRow>

    fun findAllByProject(ownerId: String, projectId: String): List<DeviceRow>

    /** Renomme, et rend la ligne à jour. La session TCP active reste ouverte. */
    fun updateName(ownerId: String, id: String, newName: String): DeviceRow?

    /**
     * Modifie le MATERIEL declare : la puce, et la facon dont elle se
     * connecte.
     *
     * Ces deux champs n'etaient poses qu'a l'enregistrement, et n'avaient
     * aucun chemin pour changer ensuite — une carte declaree « ESP32 / WiFi »
     * par erreur le restait. Ce n'est pas cosmetique : c'est sur eux que le
     * generateur de croquis s'appuiera pour emettre le bon en-tete et la
     * bonne pile reseau.
     *
     * `null` veut dire « ne touche pas », pas « efface » — un appel qui ne
     * change que la connectivite ne doit pas effacer le type.
     */
    fun updateHardware(
        ownerId: String,
        id: String,
        deviceType: String?,
        connectivity: String?
    ): DeviceRow?

    /** Remplace le jeton, et rend la ligne. La carte devra se reconnecter. */
    fun renewToken(ownerId: String, id: String, newTokenHash: String): DeviceRow?

    fun delete(ownerId: String, id: String): Boolean

    fun deleteAllByProject(ownerId: String, projectId: String): Int

    /**
     * Toutes les cartes d'un compte — la suppression de compte.
     *
     * La purge appelait [deleteAllByProject] dans une boucle sur les projets.
     * Deux instructions par projet, dans une transaction qui tient des verrous.
     * Ici, une seule, quel que soit ce que le compte possède : `owner_id` est
     * déjà sur la ligne, il n'y a aucune jointure à faire pour s'en servir.
     */
    fun deleteAllByOwner(ownerId: String): Int

    /** Les cartes d'un compte — le contrôle du quota `devices.max`. */
    fun countByOwner(ownerId: String): Long

    // ── Administration : hors du cadrage par compte ───────────────────────

    fun findAllForAdmin(): List<DeviceRow>
    fun countAll(): Long
    fun countOnlineAll(): Long
}

/**
 * L'écriture de présence — un contrat à part, et volontairement.
 *
 * Ces trois écritures ne portent pas de propriétaire, et ce n'est pas un oubli
 * de la règle : elles sont **internes au relais**, appelées pour une carte
 * qu'il vient d'authentifier par son jeton, et elles ne rendent rien qu'un
 * appelant pourrait lire. Il n'existe aucun chemin par lequel un compte
 * atteindrait la présence d'un autre.
 *
 * Elles vivent ici plutôt que dans [DeviceRepository] pour que cette absence
 * soit une **décision visible** au lieu d'une exception noyée au milieu de
 * méthodes cadrées. `PresenceStore` ne dépend que de ce contrat.
 */
interface DevicePresenceWriter {

    fun updateOnlineStatus(id: String, isOnline: Boolean)

    fun updateLastSeen(id: String, timestamp: Long)

    /**
     * Remet toutes les cartes hors ligne.
     *
     * Appelé au démarrage pour nettoyer les états périmés après un arrêt
     * brutal — un Ctrl+C qui saute le `finally` de `handleDevice`. Sans lui, la
     * base garde `isOnline = true` alors qu'aucune session TCP n'existe, et
     * l'app affiche des cartes fantômes.
     */
    fun markAllOffline()
}
