// widget/data/BucketAccumulator.kt
package com.jeanloickdt.widget.data

/**
 * Accumulateur thread-safe d'un bucket d'agrégation en RAM.
 *
 * Une instance représente UN bucket (= UNE paire (widgetId, seriesId)
 * × UN intervalle de temps) en cours de remplissage. Les samples qui
 * arrivent pendant la fenêtre de ce bucket sont accumulés via
 * [addSample], puis le bucket est flushé en DB par [TierAggregator]
 * une fois sa fenêtre fermée.
 *
 * Thread-safety : tous les champs mutables sont protégés par un lock
 * interne (`synchronized`). Plusieurs samples peuvent arriver
 * simultanément depuis différentes coroutines (relay TCP), c'est ok.
 *
 * Sérialisation triviale (types primitifs uniquement) — préparation
 * pour le futur SnapshotManager anti-crash.
 *
 * @param widgetId protocolId du widget (clé partagée app/device)
 * @param projectId projet auquel appartient le widget
 * @param ownerId owner du projet (isolation multi-user)
 * @param seriesId série pour les charts multi-courbes (null = mono-série)
 * @param bucketAt timestamp ms du début du bucket (aligné sur bucketSize)
 */
class BucketAccumulator(
    val widgetId: String,
    val projectId: String,
    val ownerId: String,
    val seriesId: String?,
    val bucketAt: Long
) {
    // Lock interne — synchronized() sur this. Le coût d'un monitor enter
    // sur quelques nanosecondes est négligeable vs le bénéfice de pouvoir
    // traiter des bursts (un capteur à 5Hz × 100 widgets = 500 samples/s).
    private var _minValue: Double = Double.POSITIVE_INFINITY
    private var _maxValue: Double = Double.NEGATIVE_INFINITY
    private var _sumValue: Double = 0.0
    private var _sampleCount: Int = 0

    /**
     * Ajoute un sample dans le bucket. Met à jour min/max/sum/count
     * de manière atomique.
     */
    fun addSample(value: Double) {
        synchronized(this) {
            if (value < _minValue) _minValue = value
            if (value > _maxValue) _maxValue = value
            _sumValue += value
            _sampleCount++
        }
    }

    /**
     * Snapshot immutable des accumulators au moment de l'appel.
     * Utilisé par [TierAggregator.extractClosedBuckets] avant flush DB.
     */
    fun snapshot(): Snapshot = synchronized(this) {
        Snapshot(
            widgetId    = widgetId,
            projectId   = projectId,
            ownerId     = ownerId,
            seriesId    = seriesId,
            bucketAt    = bucketAt,
            minValue    = _minValue,
            maxValue    = _maxValue,
            avgValue    = if (_sampleCount > 0) _sumValue / _sampleCount else 0.0,
            sampleCount = _sampleCount
        )
    }

    /**
     * Représentation immutable d'un bucket prêt à être flushé en DB.
     * Tous les champs sont primitifs / String → sérialisable facilement
     * pour un futur snapshot anti-crash.
     */
    data class Snapshot(
        val widgetId: String,
        val projectId: String,
        val ownerId: String,
        val seriesId: String?,
        val bucketAt: Long,
        val minValue: Double,
        val maxValue: Double,
        val avgValue: Double,
        val sampleCount: Int
    )
}
