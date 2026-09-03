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

import com.jeanloickdt.retention.sweepRetention
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inSubQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Écrit et relit les paliers du modèle signal.
 *
 * ## La fusion, pas un `ON CONFLICT DO UPDATE` en SQL
 *
 * L'arithmétique vit une seule fois, dans [SignalMerge] — testée seule,
 * sabotée seule. L'écrire une seconde fois ici, en `CASE WHEN` SQL pour un
 * `ON CONFLICT DO UPDATE`, aurait porté la même règle deux fois — exactement
 * le défaut que ce chantier retire ailleurs (le décodeur de trames écrit
 * deux fois, server et app). Un seau en conflit est donc lu, fusionné en
 * Kotlin, puis réécrit — dans la même transaction que le reste du batch,
 * donc au même coût de verrou/fsync qu'un `INSERT` simple.
 */
class ExposedSignalHistoryRepository(
    /**
     * L'horloge, injectable — sans elle, « le seau en cours » n'est pas
     * testable, et c'est précisément la borne qu'il faut garder.
     */
    private val now: () -> Long = { System.currentTimeMillis() }
) : com.jeanloickdt.signal.domain.SignalHistoryPurge {

    companion object {
        /**
         * Combien de seaux cibles CLOS un passage de dérivation reprend.
         *
         * La fenêtre remplace un filigrane persisté, et c'est un choix : elle
         * est sans état, donc rien à garder cohérent avec la donnée, et elle
         * est auto-réparante — la dérivation REMPLACE la ligne cible, donc
         * refaire un seau déjà fait ne coûte que du travail répété, jamais une
         * erreur qui s'accumule.
         *
         * Son prix : une interruption plus longue que la fenêtre laisserait un
         * trou. D'où le paramètre `windowBuckets` sur [deriveHour]/[deriveDay]
         * — un rattrapage est un appel avec une fenêtre plus large, pas une
         * migration.
         *
         * Trois heures et sept jours : de quoi absorber un redémarrage, un
         * déploiement raté ou une nuit d'astreinte, sans relire des mois à
         * chaque passage.
         */
        const val DERIVE_WINDOW_HOURS = 3
        const val DERIVE_WINDOW_DAYS = 7

        /**
         * Le plafond de lignes qu'une lecture rapporte.
         *
         * **Une de plus que le plafond de l'API**, et le +1 n'est pas un
         * détail : c'est lui qui permet à la route de dire `truncated: true`.
         * Sans lui, une plage qui fait exactement le plafond serait
         * indiscernable d'une plage coupée, et l'app afficherait une courbe
         * tronquée en la croyant complète.
         */
        private val READ_LIMIT = com.jeanloickdt.signal.SignalHistoryQuery.MAX_ROWS + 1
    }

    /**
     * Le seul chemin d'écriture de `signal_min` — les échantillons y
     * arrivent en continu, chacun une vérité neuve, donc la fusion sur
     * conflit ([insertAggregateBatch]) est la bonne réponse.
     *
     * `signal_hour`/`signal_day` n'ont PAS d'équivalent `insertHourBatch`/
     * `insertDayBatch` : rien n'y écrit d'échantillon directement, elles ne
     * reçoivent que ce que [deriveHour]/[deriveDay] y dépose — par
     * remplacement, pas par fusion. Deux chemins d'écriture pour les mêmes
     * tables auraient été une occasion de désaccord.
     */
    fun insertMinuteBatch(rows: List<SignalBucketAccumulator.Snapshot>) =
        insertAggregateBatch(SignalMinTable, rows)

    /**
     * Écrit un lot de seaux — **trois instructions, pas deux par seau**.
     *
     * ## Le chemin le plus chaud du serveur
     *
     * Toutes les minutes se ferment à la seconde 00 : ce lot est aussi large
     * qu'il y a de signaux actifs. La version d'avant faisait un SELECT puis un
     * INSERT ou un UPDATE **par seau**, dans une boucle — à cent mille signaux,
     * deux cent mille instructions dans une seule transaction, toutes les
     * minutes.
     *
     * ## Ce qui n'a PAS changé, et pourquoi
     *
     * Toujours pas de `ON CONFLICT DO UPDATE` en SQL. L'arithmétique de fusion
     * vit une seule fois, dans [SignalMerge] — testée seule, sabotée seule.
     * L'écrire une seconde fois en `CASE WHEN` porterait la même règle à deux
     * endroits, exactement le défaut que ce chantier retire ailleurs.
     *
     * Le regroupement ne touche pas à ce choix : on lit ce qui existe en UNE
     * requête, on fusionne en Kotlin comme avant, et on écrit en deux lots.
     *
     * ## La lecture des existants
     *
     * `signalId IN (…) AND bucketAt IN (…)` plutôt qu'un `IN` de couples : la
     * forme par couples n'est pas portable, et le produit cartésien qu'elle
     * évite est ici sans objet — un lot vient d'un même tour de vidage, donc il
     * partage une poignée de `bucketAt` au plus. Les deux colonnes sont
     * indexées.
     */
    private fun insertAggregateBatch(table: SignalAggregateTable, rows: List<SignalBucketAccumulator.Snapshot>) {
        if (rows.isEmpty()) return
        transaction {
            // 1 ── ce qui existe déjà, en une requête
            val existants = table.select(
                table.signalId, table.ownerId, table.bucketAt,
                table.avgValue, table.minValue, table.minAt, table.maxValue, table.maxAt, table.sampleCount
            ).where {
                (table.signalId inList rows.map { it.signalId }.distinct()) and
                    (table.bucketAt inList rows.map { it.bucketAt }.distinct())
            }.associate {
                (it[table.signalId] to it[table.bucketAt]) to SignalBucketAccumulator.Snapshot(
                    signalId    = it[table.signalId],
                    ownerId     = it[table.ownerId],
                    bucketAt    = it[table.bucketAt],
                    avgValue    = it[table.avgValue],
                    minValue    = it[table.minValue],
                    minAt       = it[table.minAt],
                    maxValue    = it[table.maxValue],
                    maxAt       = it[table.maxAt],
                    sampleCount = it[table.sampleCount]
                )
            }

            // 2 ── ce qui est neuf d'un côté, ce qui fusionne de l'autre
            val (aFusionner, neufs) = rows.partition { existants.containsKey(it.signalId to it.bucketAt) }

            // 3 ── les neufs, en un lot
            if (neufs.isNotEmpty()) {
                table.batchInsert(neufs, shouldReturnGeneratedValues = false) { r ->
                    this[table.signalId]    = r.signalId
                    this[table.ownerId]     = r.ownerId
                    this[table.bucketAt]    = r.bucketAt
                    this[table.avgValue]    = r.avgValue
                    this[table.minValue]    = r.minValue
                    this[table.minAt]       = r.minAt
                    this[table.maxValue]    = r.maxValue
                    this[table.maxAt]       = r.maxAt
                    this[table.sampleCount] = r.sampleCount
                }
            }

            // 4 ── les fusionnés : l'arithmétique reste dans SignalMerge, seule
            //      l'écriture est regroupée. Le redémarrage en milieu de fenêtre
            //      — test d'acceptation §7.2 du brief — passe par ici : la
            //      moitié déjà écrite ne se fait pas écraser par la seconde.
            if (aFusionner.isNotEmpty()) {
                updateAggregateRows(
                    table,
                    aFusionner.map { SignalMerge.merge(existants.getValue(it.signalId to it.bucketAt), it) }
                )
            }
        }
    }

    /**
     * Écrit N lignes agrégées en UN aller-retour, par lot JDBC.
     *
     * Exposed n'offre pas d'`UPDATE` par lot sur une table à clé composite —
     * son `BatchUpdateStatement` s'appuie sur une entité à identifiant unique.
     * Le JDBC brut est donc le chemin le plus court, et c'est déjà celui que
     * [insertRawBatch] emprunte juste en dessous : une seule façon de faire
     * dans ce fichier.
     */
    private fun updateAggregateRows(table: SignalAggregateTable, rows: List<SignalBucketAccumulator.Snapshot>) {
        if (rows.isEmpty()) return
        transaction {
            val sql = """
                UPDATE ${table.tableName}
                   SET avg_value = ?, min_value = ?, min_at = ?, max_value = ?, max_at = ?, sample_count = ?
                 WHERE signal_id = ? AND bucket_at = ?
            """.trimIndent()
            val conn = (connection as org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl).connection
            conn.prepareStatement(sql).use { ps ->
                for (r in rows) {
                    ps.setDouble(1, r.avgValue)
                    ps.setDouble(2, r.minValue)
                    ps.setLong(3, r.minAt)
                    ps.setDouble(4, r.maxValue)
                    ps.setLong(5, r.maxAt)
                    ps.setInt(6, r.sampleCount)
                    ps.setLong(7, r.signalId)
                    ps.setLong(8, r.bucketAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    fun insertRawBatch(rows: List<SignalRawEntry>) {
        if (rows.isEmpty()) return
        transaction {
            // Le doublon est ignore, pas rejete : un lot rejoue apres une
            // coupure ne doit pas mourir sur sa premiere ligne deja ecrite.
            val sql = """
                INSERT INTO signal_raw (signal_id, owner_id, ts, value)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (signal_id, ts) DO NOTHING
            """.trimIndent()
            val conn = (connection as org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl).connection
            conn.prepareStatement(sql).use { ps ->
                for (r in rows) {
                    ps.setLong(1, r.signalId)
                    ps.setString(2, r.ownerId)
                    ps.setLong(3, r.ts)
                    ps.setDouble(4, r.value)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    /** Pour les tests et les futures routes de lecture. */
    fun findMinuteRange(signalId: Long, ownerId: String, fromMs: Long, toMs: Long): List<SignalBucketAccumulator.Snapshot> =
        transaction {
            SignalMinTable.select(
                SignalMinTable.signalId, SignalMinTable.ownerId, SignalMinTable.bucketAt,
                SignalMinTable.avgValue, SignalMinTable.minValue, SignalMinTable.minAt,
                SignalMinTable.maxValue, SignalMinTable.maxAt, SignalMinTable.sampleCount
            ).where {
                (SignalMinTable.signalId eq signalId) and
                    (SignalMinTable.ownerId eq ownerId) and
                    (SignalMinTable.bucketAt greaterEq fromMs) and
                    (SignalMinTable.bucketAt lessEq toMs)
            }.orderBy(SignalMinTable.bucketAt).limit(READ_LIMIT).map {
                SignalBucketAccumulator.Snapshot(
                    signalId    = it[SignalMinTable.signalId],
                    ownerId     = it[SignalMinTable.ownerId],
                    bucketAt    = it[SignalMinTable.bucketAt],
                    avgValue    = it[SignalMinTable.avgValue],
                    minValue    = it[SignalMinTable.minValue],
                    minAt       = it[SignalMinTable.minAt],
                    maxValue    = it[SignalMinTable.maxValue],
                    maxAt       = it[SignalMinTable.maxAt],
                    sampleCount = it[SignalMinTable.sampleCount]
                )
            }
        }

    fun deleteMinuteOlderThan(cutoffMs: Long): Int = transaction {
        SignalMinTable.deleteWhere { bucketAt less cutoffMs }
    }

    /**
     * Le balayage de rétention, par palier — une coupure pour tout le monde
     * plus les comptes qui gardent plus ou moins longtemps.
     *
     * ⚠️ **Doit tourner APRÈS [deriveHour]/[deriveDay].** Une ligne minute
     * supprimée avant d'avoir été dérivée fait un trou définitif dans la
     * courbe heure — et comme la minute sera plafonnée bien plus court que
     * l'heure, le cas se présenterait tous les jours, pas en cas rare.
     * L'appelant garantit cet ordre en enchaînant les deux dans la même
     * boucle.
     */
    fun sweepMinute(sweep: com.jeanloickdt.retention.RetentionSweep): Int =
        transaction { SignalMinTable.sweepRetention(sweep, SignalMinTable.bucketAt, SignalMinTable.ownerId) }

    fun sweepHour(sweep: com.jeanloickdt.retention.RetentionSweep): Int =
        transaction { SignalHourTable.sweepRetention(sweep, SignalHourTable.bucketAt, SignalHourTable.ownerId) }

    fun sweepDay(sweep: com.jeanloickdt.retention.RetentionSweep): Int =
        transaction { SignalDayTable.sweepRetention(sweep, SignalDayTable.bucketAt, SignalDayTable.ownerId) }

    fun sweepRaw(sweep: com.jeanloickdt.retention.RetentionSweep): Int =
        transaction { SignalRawTable.sweepRetention(sweep, SignalRawTable.ts, SignalRawTable.ownerId) }

    /**
     * Efface tout l'historique d'un compte — les quatre paliers.
     *
     * **Doit être appelé AVANT la suppression des lignes `signals`.** Chaque
     * table d'historique porte une clé étrangère vers `signals.id` : supprimer
     * le signal d'abord ferait échouer la contrainte, et la transaction
     * entière de la purge avec elle.
     *
     * `owner_id` est dénormalisé sur chaque ligne précisément pour que ce
     * balayage n'ait aucune jointure à faire — c'est sa seule raison d'être.
     */
    override fun deleteAllByOwner(ownerId: String): Int = transaction {
        var n = 0
        n += SignalRawTable.deleteWhere { SignalRawTable.ownerId eq ownerId }
        for (t in listOf(SignalMinTable, SignalHourTable, SignalDayTable)) {
            n += t.deleteWhere { t.ownerId eq ownerId }
        }
        n
    }

    /**
     * Efface l'historique des signaux portés par ces cartes.
     *
     * **Le moteur fait la jointure, pas nous.** L'appelant résolvait avant les
     * identifiants de signaux lui-même — une requête `listByDevice` par carte,
     * puis un `IN (…)` de tous leurs identifiants. À cent cartes, cent
     * allers-retours et une clause de plusieurs milliers d'éléments.
     *
     * Les tables d'historique ne portent ni `device_id` ni `project_id` — le
     * modèle cible a retiré ces colonnes, qui dupliquaient ce que `signals`
     * détient déjà. La sous-requête rétablit le lien sans les réintroduire.
     *
     * `owner_id` est dans les deux moitiés du prédicat, pas seulement dans la
     * sous-requête : une jointure ne doit jamais être la seule chose qui tienne
     * l'isolation entre comptes.
     */
    override fun deleteAllByDevices(ownerId: String, deviceIds: List<String>): Int {
        if (deviceIds.isEmpty()) return 0
        return transaction {
            val mine = SignalTable
                .select(SignalTable.id)
                .where { (SignalTable.deviceId inList deviceIds) and (SignalTable.ownerId eq ownerId) }

            var n = 0
            n += SignalRawTable.deleteWhere {
                (SignalRawTable.ownerId eq ownerId) and (SignalRawTable.signalId inSubQuery mine)
            }
            for (t in listOf(SignalMinTable, SignalHourTable, SignalDayTable)) {
                n += t.deleteWhere { (t.ownerId eq ownerId) and (t.signalId inSubQuery mine) }
            }
            n
        }
    }

    // ── La dérivation périodique — étape 3 ──────────────────────────────

    /** Minute → heure. Voir [deriveTier] pour ce que « dériver » veut dire ici. */
    fun deriveHour(windowBuckets: Int = DERIVE_WINDOW_HOURS): Int =
        deriveTier(SignalMinTable, SignalHourTable, SignalRollup.HOUR_MS, windowBuckets)

    /** Heure → jour — jamais minute → jour directement, voir le fichier du modèle cible. */
    fun deriveDay(windowBuckets: Int = DERIVE_WINDOW_DAYS): Int =
        deriveTier(SignalHourTable, SignalDayTable, SignalRollup.DAY_MS, windowBuckets)

    /**
     * Relit une FENÊTRE de `source`, regroupe par `(signal_id, seau cible)`,
     * fusionne chaque groupe via [SignalRollup], et **remplace** la ligne
     * correspondante dans `target`.
     *
     * ## Les deux bornes, et pourquoi aucune n'est optionnelle
     *
     * **En haut : jamais le seau cible EN COURS.** Il n'est pas fermé, ses
     * sources sont incomplètes. Le dériver écrirait une heure calculée sur les
     * quelques minutes déjà arrivées et la présenterait comme l'heure entière —
     * puis la remplacerait au passage suivant, donc l'app verrait une valeur
     * qui saute jusqu'à la fin de l'heure.
     *
     * **En bas : une fenêtre glissante.** Sans elle, cette fonction relisait
     * TOUTE la table source à chaque appel — quelques millions de lignes minute
     * chargées dans une liste Kotlin, groupées en mémoire, réécrites en entier.
     * Ce n'était pas une optimisation en attente : c'est un travail qui finit
     * par ne plus aboutir du tout.
     *
     * Et son échec en cachait un second. La purge doit tourner APRÈS la
     * dérivation ; une dérivation qui n'aboutit jamais est une purge qui ne
     * tourne jamais, donc un disque qui grossit sans limite. Rien dans les logs
     * n'aurait distingué les deux.
     *
     * ## Remplacer, pas fusionner — et c'est ce qui rend la fenêtre viable
     *
     * `insertAggregateBatch` (l'écriture d'ingestion) fusionne sur conflit, et
     * c'est juste : chaque échantillon qui arrive est une vérité neuve. Ici,
     * non — une dérivation relancée relit des lignes minute **déjà vues**. Les
     * fusionner compterait deux fois les mêmes échantillons à chaque relance :
     * le `sample_count` de l'heure gonflerait sans fin, un compteur qui ment de
     * plus en plus sans qu'aucune erreur ne se produise.
     *
     * Le remplacement recalcule la vérité entière à chaque passage. C'est ce
     * qui permet à la fenêtre d'être sans état : refaire un seau déjà fait ne
     * coûte que du travail répété.
     *
     * @param windowBuckets combien de seaux cibles clos reprendre. Élargir
     *        rattrape une interruption longue — c'est la sortie de secours du
     *        compromis ci-dessus, et elle ne demande aucune migration.
     */
    private fun deriveTier(
        source: SignalAggregateTable,
        target: SignalAggregateTable,
        targetBucketMs: Long,
        windowBuckets: Int
    ): Int = transaction {
        // Le seau cible en cours : borne HAUTE, exclue. Les lignes source qui
        // lui appartiennent sont ignorées, pas encore mûres.
        val enCours = SignalRollup.truncateTo(now(), targetBucketMs)
        val depuis = enCours - windowBuckets * targetBucketMs

        val rows = source.select(
            source.signalId, source.ownerId, source.bucketAt,
            source.avgValue, source.minValue, source.minAt, source.maxValue, source.maxAt, source.sampleCount
        ).where {
            (source.bucketAt greaterEq depuis) and (source.bucketAt less enCours)
        }.map {
            SignalBucketAccumulator.Snapshot(
                signalId    = it[source.signalId],
                ownerId     = it[source.ownerId],
                bucketAt    = it[source.bucketAt],
                avgValue    = it[source.avgValue],
                minValue    = it[source.minValue],
                minAt       = it[source.minAt],
                maxValue    = it[source.maxValue],
                maxAt       = it[source.maxAt],
                sampleCount = it[source.sampleCount]
            )
        }

        val combines = rows
            .groupBy { it.signalId to SignalRollup.truncateTo(it.bucketAt, targetBucketMs) }
            .map { (key, pieces) -> SignalRollup.combine(key.second, pieces) }

        replaceAggregateRows(target, combines)
        combines.size
    }

    /**
     * Remplace N lignes cibles — même motif que [insertAggregateBatch], et pour
     * la même raison : la boucle de dérivation appelait la version au singulier
     * une fois par seau.
     *
     * « Remplace » et pas « fusionne » : voir [deriveTier]. La ligne cible est
     * recalculée depuis la source, donc ce qu'elle contenait n'entre pas dans
     * le calcul.
     */
    private fun replaceAggregateRows(table: SignalAggregateTable, rows: List<SignalBucketAccumulator.Snapshot>) {
        if (rows.isEmpty()) return
        transaction {
            val existants = table.select(table.signalId, table.bucketAt).where {
                (table.signalId inList rows.map { it.signalId }.distinct()) and
                    (table.bucketAt inList rows.map { it.bucketAt }.distinct())
            }.map { it[table.signalId] to it[table.bucketAt] }.toSet()

            val (aRemplacer, neufs) = rows.partition { (it.signalId to it.bucketAt) in existants }

            if (neufs.isNotEmpty()) {
                table.batchInsert(neufs, shouldReturnGeneratedValues = false) { r ->
                    this[table.signalId]    = r.signalId
                    this[table.ownerId]     = r.ownerId
                    this[table.bucketAt]    = r.bucketAt
                    this[table.avgValue]    = r.avgValue
                    this[table.minValue]    = r.minValue
                    this[table.minAt]       = r.minAt
                    this[table.maxValue]    = r.maxValue
                    this[table.maxAt]       = r.maxAt
                    this[table.sampleCount] = r.sampleCount
                }
            }
            updateAggregateRows(table, aRemplacer)
        }
    }

    /**
     * Ce que la route d'historique rend — un palier quelconque, déjà mis à la
     * forme de l'API.
     *
     * La traduction vit ici plutôt que dans la route pour que la route reste
     * ignorante du moteur, et pour que `raw` (dont les points n'ont ni
     * amplitude ni effectif) et les paliers agrégés se rendent au même
     * endroit — c'est la seule différence entre eux, elle mérite d'être
     * visible d'un coup d'œil.
     */
    fun readForApi(
        signalId: Long, ownerId: String, fromMs: Long, toMs: Long, resolution: String
    ): List<com.jeanloickdt.signal.SignalHistoryPoint> = when (resolution) {
        "raw" -> findRawRange(signalId, ownerId, fromMs, toMs).map {
            com.jeanloickdt.signal.SignalHistoryPoint(t = it.ts, y = it.value)
        }
        else -> {
            val table = when (resolution) {
                "min"  -> SignalMinTable
                "hour" -> SignalHourTable
                else   -> SignalDayTable
            }
            findAggregateRange(table, signalId, ownerId, fromMs, toMs).map {
                com.jeanloickdt.signal.SignalHistoryPoint(
                    t = it.bucketAt, y = it.avgValue,
                    yMin = it.minValue, yMax = it.maxValue,
                    minAt = it.minAt, maxAt = it.maxAt,
                    n = it.sampleCount
                )
            }
        }
    }

    fun findRawRange(signalId: Long, ownerId: String, fromMs: Long, toMs: Long): List<SignalRawEntry> =
        transaction {
            SignalRawTable.select(
                SignalRawTable.signalId, SignalRawTable.ownerId, SignalRawTable.ts, SignalRawTable.value
            ).where {
                (SignalRawTable.signalId eq signalId) and
                    (SignalRawTable.ownerId eq ownerId) and
                    (SignalRawTable.ts greaterEq fromMs) and
                    (SignalRawTable.ts lessEq toMs)
            }.orderBy(SignalRawTable.ts).limit(READ_LIMIT).map {
                SignalRawEntry(
                    signalId = it[SignalRawTable.signalId],
                    ownerId  = it[SignalRawTable.ownerId],
                    ts       = it[SignalRawTable.ts],
                    value    = it[SignalRawTable.value]
                )
            }
        }

    /** Pour les tests et les futures routes de lecture. */
    fun findHourRange(signalId: Long, ownerId: String, fromMs: Long, toMs: Long): List<SignalBucketAccumulator.Snapshot> =
        findAggregateRange(SignalHourTable, signalId, ownerId, fromMs, toMs)

    /** Pour les tests et les futures routes de lecture. */
    fun findDayRange(signalId: Long, ownerId: String, fromMs: Long, toMs: Long): List<SignalBucketAccumulator.Snapshot> =
        findAggregateRange(SignalDayTable, signalId, ownerId, fromMs, toMs)

    private fun findAggregateRange(
        table: SignalAggregateTable, signalId: Long, ownerId: String, fromMs: Long, toMs: Long
    ): List<SignalBucketAccumulator.Snapshot> = transaction {
        table.select(
            table.signalId, table.ownerId, table.bucketAt,
            table.avgValue, table.minValue, table.minAt, table.maxValue, table.maxAt, table.sampleCount
        ).where {
            (table.signalId eq signalId) and
                (table.ownerId eq ownerId) and
                (table.bucketAt greaterEq fromMs) and
                (table.bucketAt lessEq toMs)
        }.orderBy(table.bucketAt).limit(READ_LIMIT).map {
            SignalBucketAccumulator.Snapshot(
                signalId    = it[table.signalId],
                ownerId     = it[table.ownerId],
                bucketAt    = it[table.bucketAt],
                avgValue    = it[table.avgValue],
                minValue    = it[table.minValue],
                minAt       = it[table.minAt],
                maxValue    = it[table.maxValue],
                maxAt       = it[table.maxAt],
                sampleCount = it[table.sampleCount]
            )
        }
    }
}

/** Un échantillon brut, en route vers `signal_raw`. */
data class SignalRawEntry(
    val signalId: Long,
    val ownerId: String,
    val ts: Long,
    val value: Double
)
