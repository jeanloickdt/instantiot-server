// widget/data/HistoryAggregator.kt
package com.jeanloickdt.widget.data

import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Orchestrateur de downsampling 4 tiers :
 *
 *   widget_history_numeric (raw, 1 pt / 5s max)
 *          ↓ aggregateRawToMinute()   avg/min/max/count
 *   widget_history_min     (buckets 1 min)
 *          ↓ aggregateMinuteToHour()  moyenne pondérée par sample_count
 *   widget_history_hour    (buckets 1 h)
 *          ↓ aggregateHourToDay()
 *   widget_history_day     (buckets 1 j, rétention infinie par défaut)
 *
 * Idempotence : chaque étape utilise `INSERT OR IGNORE` sur un INDEX UNIQUE
 * `(widget_id, COALESCE(series_id, ''), bucket_at)` créé dans
 * `DatabaseFactory.init`. Relancer le cron plusieurs fois ne double pas
 * les rows.
 *
 * Stabilité : on n'agrège qu'un bucket dont la fin est < `now - 1s` pour
 * éviter de créer un bucket partiel qui ne pourra plus être complété
 * ensuite (car on a INSERT OR IGNORE, pas REPLACE).
 */
object HistoryAggregator {

    private val log = LoggerFactory.getLogger("HistoryAggregator")

    private const val BUCKET_MIN_MS  = 60_000L
    private const val BUCKET_HOUR_MS = 3_600_000L
    private const val BUCKET_DAY_MS  = 86_400_000L

    /**
     * Lance les 3 agrégations successives.
     * Safe de rappeler plusieurs fois — idempotent via INSERT OR IGNORE.
     *
     * @param now Timestamp de référence (utile pour tests ; défaut = clock
     *            actuelle).
     */
    fun runAll(now: Long = System.currentTimeMillis()) {
        val startedAt = System.currentTimeMillis()
        try {
            aggregateRawToMinute(now)
            aggregateMinuteToHour(now)
            aggregateHourToDay(now)
            val tookMs = System.currentTimeMillis() - startedAt
            log.info("History downsample completed in ${tookMs}ms (raw→min→hour→day)")
        } catch (e: Exception) {
            log.error("History downsample failed", e)
        }
    }

    // ================================================================
    // Tier 1 : raw → minute
    // ================================================================

    fun aggregateRawToMinute(now: Long) {
        // On n'agrège que les buckets complets (bucket_end <= now - 1s) pour
        // éviter les buckets partiels.
        val latestStableBucketEnd = now - 1_000L
        transaction {
            exec(
                """
                INSERT OR IGNORE INTO widget_history_min
                    (widget_id, project_id, owner_id, series_id,
                     avg_value, min_value, max_value, sample_count, bucket_at)
                SELECT
                    widget_id, project_id, owner_id, series_id,
                    AVG(value)  AS avg_value,
                    MIN(value)  AS min_value,
                    MAX(value)  AS max_value,
                    COUNT(*)    AS sample_count,
                    (recorded_at / $BUCKET_MIN_MS) * $BUCKET_MIN_MS AS bucket_at
                FROM widget_history_numeric
                WHERE recorded_at < $latestStableBucketEnd
                GROUP BY widget_id, series_id, bucket_at
                """.trimIndent()
            )
        }
    }

    // ================================================================
    // Tier 2 : minute → hour
    //
    // Moyenne pondérée par sample_count pour rester fidèle à la distrib.
    // AVG(avg) d'avgs serait faux si les buckets min n'ont pas le même
    // nombre d'échantillons.
    // ================================================================

    fun aggregateMinuteToHour(now: Long) {
        val latestStableBucketEnd = now - 1_000L
        transaction {
            exec(
                """
                INSERT OR IGNORE INTO widget_history_hour
                    (widget_id, project_id, owner_id, series_id,
                     avg_value, min_value, max_value, sample_count, bucket_at)
                SELECT
                    widget_id, project_id, owner_id, series_id,
                    SUM(avg_value * sample_count) / NULLIF(SUM(sample_count), 0) AS avg_value,
                    MIN(min_value) AS min_value,
                    MAX(max_value) AS max_value,
                    SUM(sample_count) AS sample_count,
                    (bucket_at / $BUCKET_HOUR_MS) * $BUCKET_HOUR_MS AS bucket_at
                FROM widget_history_min
                WHERE (bucket_at + $BUCKET_MIN_MS) <= $latestStableBucketEnd
                GROUP BY widget_id, series_id, (bucket_at / $BUCKET_HOUR_MS)
                """.trimIndent()
            )
        }
    }

    // ================================================================
    // Tier 3 : hour → day
    // ================================================================

    fun aggregateHourToDay(now: Long) {
        val latestStableBucketEnd = now - 1_000L
        transaction {
            exec(
                """
                INSERT OR IGNORE INTO widget_history_day
                    (widget_id, project_id, owner_id, series_id,
                     avg_value, min_value, max_value, sample_count, bucket_at)
                SELECT
                    widget_id, project_id, owner_id, series_id,
                    SUM(avg_value * sample_count) / NULLIF(SUM(sample_count), 0) AS avg_value,
                    MIN(min_value) AS min_value,
                    MAX(max_value) AS max_value,
                    SUM(sample_count) AS sample_count,
                    (bucket_at / $BUCKET_DAY_MS) * $BUCKET_DAY_MS AS bucket_at
                FROM widget_history_hour
                WHERE (bucket_at + $BUCKET_HOUR_MS) <= $latestStableBucketEnd
                GROUP BY widget_id, series_id, (bucket_at / $BUCKET_DAY_MS)
                """.trimIndent()
            )
        }
    }
}
