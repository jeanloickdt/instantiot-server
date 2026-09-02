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


// project/data/ExposedProjectRepository.kt
package com.jeanloickdt.project.data

import com.jeanloickdt.project.domain.LayoutWrite
import com.jeanloickdt.project.domain.ProjectRepository
import com.jeanloickdt.project.domain.ProjectRow
import com.jeanloickdt.project.domain.ProjectSummary
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Les projets, en DSL Exposed pur.
 *
 * Les trois règles sont énoncées dans [ProjectRepository]. Ici, ce qu'elles
 * donnent : chaque requête filtre sur `owner_id`, chaque écriture est une
 * instruction unique, et chaque écriture rend sa ligne.
 */
class ExposedProjectRepository : ProjectRepository {

    /** Un projet, et son propriétaire. Jamais l'un sans l'autre. */
    private fun mine(ownerId: String, id: String): Op<Boolean> =
        (ProjectTable.id eq id) and (ProjectTable.ownerId eq ownerId)

    // ============================================================
    // Créer — layout vide, l'app l'initialise
    // ============================================================
    override fun create(ownerId: String, name: String): ProjectRow {
        val id  = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        return transaction {
            ProjectTable.insert {
                it[ProjectTable.id]         = id
                it[ProjectTable.ownerId]    = ownerId
                it[ProjectTable.name]       = name
                it[ProjectTable.layoutJson] = "{}"
                it[ProjectTable.createdAt]  = now
                it[ProjectTable.updatedAt]  = now
            }
            // La ligne, pas l'identifiant : l'appelant n'a plus de seconde
            // requête à faire ni de `!!` à écrire pour la relire.
            ProjectTable.selectAll().where { mine(ownerId, id) }.single().toProjectRow()
        }
    }

    // ============================================================
    // Lire — toujours cadré par le compte
    // ============================================================
    override fun findById(ownerId: String, id: String): ProjectRow? = transaction {
        ProjectTable.selectAll().where { mine(ownerId, id) }.singleOrNull()?.toProjectRow()
    }

    override fun findAllByOwner(ownerId: String): List<ProjectRow> = transaction {
        ProjectTable.selectAll().where { ProjectTable.ownerId eq ownerId }.map { it.toProjectRow() }
    }

    /**
     * La liste, sans la colonne de layout.
     *
     * `select(...)` nomme les colonnes voulues, là où `selectAll()` prend
     * tout. Ce n'est pas de la cosmétique : PostgreSQL stocke un `TEXT`
     * volumineux hors ligne (TOAST), et la colonne non demandée n'est donc
     * **pas lue sur le disque**. Un filtre en Kotlin aurait tout chargé pour
     * tout jeter.
     */
    override fun findAllByOwnerSummary(ownerId: String): List<ProjectSummary> = transaction {
        ProjectTable
            .select(
                ProjectTable.id, ProjectTable.name, ProjectTable.version,
                ProjectTable.createdAt, ProjectTable.updatedAt
            )
            .where { ProjectTable.ownerId eq ownerId }
            .map {
                ProjectSummary(
                    id        = it[ProjectTable.id],
                    name      = it[ProjectTable.name],
                    version   = it[ProjectTable.version],
                    createdAt = it[ProjectTable.createdAt],
                    updatedAt = it[ProjectTable.updatedAt],
                )
            }
    }

    // ============================================================
    // Renommer — `version` ne bouge pas, voir l'interface
    // ============================================================
    override fun updateName(ownerId: String, id: String, name: String): ProjectRow? = transaction {
        val touched = ProjectTable.update({ mine(ownerId, id) }) {
            it[ProjectTable.name]      = name
            it[ProjectTable.updatedAt] = System.currentTimeMillis()
        }
        if (touched == 0) null
        else ProjectTable.selectAll().where { mine(ownerId, id) }.single().toProjectRow()
    }

    // ============================================================
    // Écrire le layout — UNE instruction conditionnelle
    // ============================================================
    override fun updateLayout(
        ownerId: String,
        id: String,
        layoutJson: String,
        expectedVersion: Int?
    ): LayoutWrite = transaction {
        // La version d'avant lisait, comparait en Kotlin, puis écrivait — les
        // deux dans une seule transaction, et le commentaire affirmait que ça
        // fermait la course.
        //
        // FAUX : PostgreSQL en READ COMMITTED ne sérialise pas ses écrivains,
        // et un `SELECT` ne verrouille rien.
        //
        //     T1  SELECT version → 5
        //     T2  SELECT version → 5
        //     T1  5 == 5, d'accord → UPDATE version = 6, valide
        //     T2  5 == 5, d'accord → UPDATE version = 6, valide
        //
        // Le `WHERE` ne portait que sur l'identifiant : il correspondait quelle
        // que soit la version. T2 écrasait le travail de T1 et rendait `Ok`.
        // Mesuré : trois gagnants sur huit écrivains simultanés — voir
        // `PostgresContractTest`.
        //
        // Ici la version est DANS le `WHERE`. Sous READ COMMITTED, le second
        // UPDATE attend le verrou de ligne, réévalue sa condition sur la ligne
        // fraîchement validée, ne correspond plus, et touche zéro ligne. Même
        // comportement sur les deux moteurs, sans verrou explicite.
        val touched = ProjectTable.update({
            var where = mine(ownerId, id)
            if (expectedVersion != null) where = where and (ProjectTable.version eq expectedVersion)
            where
        }) {
            it[ProjectTable.layoutJson] = layoutJson
            // Calculé par le moteur : `lu + 1` ramènerait le compteur dans le
            // programme, et la course avec lui.
            with(SqlExpressionBuilder) { it[ProjectTable.version] = ProjectTable.version + 1 }
            it[ProjectTable.updatedAt] = System.currentTimeMillis()
        }

        val row = ProjectTable.selectAll().where { mine(ownerId, id) }.limit(1).firstOrNull()
            ?: return@transaction LayoutWrite.NotFound

        // Zéro ligne touchée alors que le projet existe et nous appartient :
        // la version ne correspondait pas. C'est la seule autre raison.
        if (touched > 0) LayoutWrite.Ok(row[ProjectTable.version])
        else LayoutWrite.Conflict(row[ProjectTable.version], row[ProjectTable.layoutJson])
    }

    // ============================================================
    // Supprimer
    // ============================================================
    override fun delete(ownerId: String, id: String): Boolean = transaction {
        ProjectTable.deleteWhere { (ProjectTable.id eq id) and (ProjectTable.ownerId eq ownerId) } > 0
    }

    override fun deleteAllByOwner(ownerId: String): Int = transaction {
        ProjectTable.deleteWhere { ProjectTable.ownerId eq ownerId }
    }

    // ============================================================
    // La métrique d'administration — hors du cadrage par compte
    // ============================================================
    override fun countAll(): Long = transaction { ProjectTable.selectAll().count() }

    private fun ResultRow.toProjectRow() = ProjectRow(
        id         = this[ProjectTable.id],
        ownerId    = this[ProjectTable.ownerId],
        name       = this[ProjectTable.name],
        layoutJson = this[ProjectTable.layoutJson],
        version    = this[ProjectTable.version],
        createdAt  = this[ProjectTable.createdAt],
        updatedAt  = this[ProjectTable.updatedAt]
    )
}
