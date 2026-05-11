// widget/data/TierAggregator.kt
package com.jeanloickdt.widget.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Agrégateur RAM d'un tier (minute, hour ou day).
 *
 * Maintient une [ConcurrentHashMap] de [BucketAccumulator] indexée par
 * (widgetId, seriesId, bucketAt). Quand un sample arrive via [collect],
 * il alimente le bucket courant ; à intervalle régulier, le job de
 * flush appelle [extractClosedBuckets] pour récupérer les buckets
 * dont la fenêtre est terminée et les insérer en DB.
 *
 * Le bucket "courant" (celui dont la fenêtre n'est pas encore fermée)
 * reste en RAM pour continuer à accumuler les samples qui arrivent.
 *
 * Thread-safety : la `ConcurrentHashMap` protège les insertions, et
 * chaque [BucketAccumulator] a son propre lock interne pour les updates
 * atomiques. Pas besoin de lock global → throughput élevé même sous
 * forte charge multi-coroutine.
 *
 * Architecture inspirée de Blynk Legacy Server (open source).
 *
 * @param bucketSizeMs taille du bucket en ms (60_000 / 3_600_000 / 86_400_000)
 */
class TierAggregator(
    val bucketSizeMs: Long
) {
    /**
     * Clé composite identifiant un bucket dans la map. Réduite à un
     * triplet immutable hashable pour servir de clé `ConcurrentHashMap`.
     */
    private data class BucketKey(
        val widgetId: String,
        val seriesId: String?,
        val bucketAt: Long
    )

    private val buckets = ConcurrentHashMap<BucketKey, BucketAccumulator>()

    /**
     * Alimente le bucket correspondant au timestamp `ts` avec `value`.
     * Crée le bucket si absent (race-free via `computeIfAbsent`).
     *
     * @param widgetId protocolId du widget
     * @param seriesId série pour les charts multi-courbes (null = mono)
     * @param ts timestamp du sample (ms epoch)
     * @param value valeur numérique du sample
     * @param projectId propagé jusqu'au bucket pour l'isolation owner
     * @param ownerId propagé jusqu'au bucket pour l'isolation owner
     */
    fun collect(
        widgetId: String,
        seriesId: String?,
        ts: Long,
        value: Double,
        projectId: String,
        ownerId: String
    ) {
        val bucketAt = (ts / bucketSizeMs) * bucketSizeMs
        val key = BucketKey(widgetId, seriesId, bucketAt)
        val bucket = buckets.computeIfAbsent(key) {
            BucketAccumulator(
                widgetId  = widgetId,
                projectId = projectId,
                ownerId   = ownerId,
                seriesId  = seriesId,
                bucketAt  = bucketAt
            )
        }
        bucket.addSample(value)
    }

    /**
     * Extrait (= retire de la map + retourne) tous les buckets dont la
     * fenêtre est terminée à `now`. Un bucket est "fermé" quand
     * `bucketAt + bucketSizeMs <= now` (la fenêtre du bucket commence
     * à `bucketAt` et finit à `bucketAt + bucketSizeMs`).
     *
     * Appelé par le job de flush toutes les 5s. Le bucket courant
     * (`bucketAt + bucketSizeMs > now`) reste dans la map pour
     * continuer à accumuler.
     */
    fun extractClosedBuckets(now: Long): List<BucketAccumulator.Snapshot> {
        // On itère sur les keys ; la map peut être mutée pendant — c'est
        // safe (ConcurrentHashMap) et les insertions concurrentes sur le
        // bucket courant ne nous regardent pas.
        val closed = mutableListOf<BucketAccumulator.Snapshot>()
        val keysToRemove = mutableListOf<BucketKey>()

        for ((key, bucket) in buckets) {
            if (key.bucketAt + bucketSizeMs <= now) {
                closed += bucket.snapshot()
                keysToRemove += key
            }
        }

        // Remove en deuxième passe pour ne pas muter pendant l'itération
        // (même si ConcurrentHashMap supporte, ça reste plus clair).
        for (key in keysToRemove) {
            buckets.remove(key)
        }

        return closed
    }

    /**
     * Extrait TOUS les buckets, y compris le courant (= dont la
     * fenêtre n'est pas encore close). Utilisé UNIQUEMENT au shutdown
     * propre via le hook `ApplicationStopping` pour ne rien perdre
     * lors d'un restart contrôlé.
     */
    fun extractAllBuckets(): List<BucketAccumulator.Snapshot> {
        val all = mutableListOf<BucketAccumulator.Snapshot>()
        val keysToRemove = ArrayList(buckets.keys)
        for (key in keysToRemove) {
            buckets.remove(key)?.let { all += it.snapshot() }
        }
        return all
    }

    /** Nombre de buckets actuellement en RAM (utile pour metrics / debug). */
    fun size(): Int = buckets.size
}
