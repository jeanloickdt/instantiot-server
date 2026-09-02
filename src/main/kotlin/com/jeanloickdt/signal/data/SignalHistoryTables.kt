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

package com.jeanloickdt.signal.data

import org.jetbrains.exposed.sql.Table

/**
 * Les tables d'historique du modèle signal — étape 1 du passage complet.
 *
 * ## Ce qui change par rapport à `widget_history_*`
 *
 * La clé est `(signal_id, bucket_at)` — **deux colonnes**, contre quatre
 * aujourd'hui (`widget_id, owner_id, series_id, bucket_at`). Elle **contient
 * déjà la colonne de temps**, ce que la clé de substitution des tables
 * widget ne faisait pas — d'où toute la danse « créer la PK, la retirer
 * pour l'hypertable, ne jamais la recréer » qui vivait dans
 * `PostgresDatabaseFactory`. Ici, rien à retirer : Postgres peut
 * hypertable-iser la table directement, PK comprise.
 *
 * `owner_id` reste sur la ligne — dénormalisé, pour purger sans jointure —
 * mais **hors de la clé**, comme le modèle cible l'exige.
 *
 * ## Portée de cette étape
 *
 * Quatre tables, pas cinq. `signal_text` n'existe pas encore : rien
 * n'historise aujourd'hui les signaux de type texte — ce n'est pas un
 * chemin existant à rediriger, ce serait une fonctionnalité neuve, hors du
 * périmètre « brancher le chemin d'écriture [existant] dessus ».
 *
 * `signal_hour` et `signal_day` sont créées ici — le schéma doit exister —
 * mais restent **vides** jusqu'à l'étape qui les dérive par un travail
 * périodique (§3 du brief). Rien n'y écrit encore.
 */
open class SignalAggregateTable(tableName: String) : Table(tableName) {
    val signalId    = long("signal_id").references(SignalTable.id)
    val ownerId     = text("owner_id")
    val bucketAt    = long("bucket_at")
    val avgValue    = double("avg_value")
    val minValue    = double("min_value")
    /** L'instant exact de l'échantillon minimum — voir le fichier ci-dessus. */
    val minAt       = long("min_at")
    val maxValue    = double("max_value")
    /** L'instant exact de l'échantillon maximum. */
    val maxAt       = long("max_at")
    val sampleCount = integer("sample_count")

    override val primaryKey = PrimaryKey(signalId, bucketAt)

    init {
        // `listByOwner`-style lectures filtrent sur ownerId ; bucketAt suit
        // pour les requêtes par plage, la forme la plus commune.
        index(isUnique = false, ownerId, bucketAt)
    }
}

object SignalMinTable  : SignalAggregateTable("signal_min")
object SignalHourTable : SignalAggregateTable("signal_hour")
object SignalDayTable  : SignalAggregateTable("signal_day")

/**
 * Le tier brut — une ligne par échantillon, pas d'agrégation.
 *
 * `PRIMARY KEY (signal_id, ts)` est une CORRECTION, pas une reconduction :
 * `widget_history_numeric` n'a aujourd'hui aucune clé unique — sa PK de
 * substitution est retirée pour l'hypertable-isation et jamais recréée,
 * donc rien n'empêche un batch RAW rejoué de doubler ses lignes. Ici la
 * clé contient le temps dès le départ : hypertable directe, et un rejeu
 * du même batch ne peut plus créer de doublon.
 */
object SignalRawTable : Table("signal_raw") {
    val signalId = long("signal_id").references(SignalTable.id)
    val ownerId  = text("owner_id")
    val ts       = long("ts")
    val value    = double("value")

    override val primaryKey = PrimaryKey(signalId, ts)

    init {
        index(isUnique = false, ownerId, ts)
    }
}

/**
 * Les cinq tables du modèle signal, enregistrées ensemble.
 *
 * La déclaration et ses quatre paliers voyagent en groupe : une suppression de
 * compte ou de projet descend jusqu'au dernier, et un schéma qui n'en contient
 * que quelques-unes échoue à l'exécution, pas à la compilation. Le tableau
 * rend l'oubli impossible — sur le modèle de `AutomationTables.ALL`.
 */
object SignalTables {
    val ALL = arrayOf<Table>(
        SignalTable, SignalRawTable, SignalMinTable, SignalHourTable, SignalDayTable
    )
}
