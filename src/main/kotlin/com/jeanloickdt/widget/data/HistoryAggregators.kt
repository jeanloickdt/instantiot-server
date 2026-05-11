// widget/data/HistoryAggregators.kt
package com.jeanloickdt.widget.data

/**
 * Singleton accessible globalement contenant les 3 tiers d'agrégation
 * en RAM (minute, hour, day).
 *
 * Alimenté par [com.jeanloickdt.relay.DeviceRelay.handleDeviceFrame]
 * dès qu'un sample numérique arrive — les 3 tiers sont mis à jour en
 * parallèle (chacun depuis les samples bruts, pas de cascade SQL).
 *
 * Flushé périodiquement par le job 5s dans `Application.kt` qui appelle
 * [TierAggregator.extractClosedBuckets] sur chaque tier et persiste
 * les buckets fermés en DB.
 *
 * **Pourquoi 3 tiers indépendants** (et pas de re-agrégation
 * hour ← minute, day ← hour comme l'ancien `HistoryAggregator`) :
 *  - Chaque tier consomme directement les samples bruts → fidélité
 *    mathématique parfaite (la moyenne de la journée tient compte de
 *    chaque sample, pas seulement de moyennes de moyennes).
 *  - Pas de dépendance entre tiers → si l'admin désactive le raw,
 *    les 3 tiers continuent de fonctionner.
 *  - Pas de cascade différée → toutes les tables sont toujours à
 *    jour en temps réel (fenêtre 5s).
 *
 * **Données perdues en cas de crash brutal** (sans snapshot anti-crash) :
 *  - raw (si activé) : 5s max (buffer non flushé)
 *  - minute : 1 min max (bucket courant en RAM)
 *  - hour   : 1 h max  (bucket courant en RAM)
 *  - day    : 24 h max (bucket courant en RAM)
 *
 * Le hook `ApplicationStopping` flushe TOUT (y compris buckets en cours)
 * → zéro perte au shutdown propre.
 *
 * **Préparation snapshot anti-crash** (futur) : les buckets contiennent
 * uniquement des types primitifs → sérialisation triviale. Un futur
 * `SnapshotManager` pourra lire l'état sans modifier ce code.
 */
object HistoryAggregators {
    /** Bucket = 1 minute. Alimente `widget_history_min`. */
    val minute = TierAggregator(bucketSizeMs = 60_000L)

    /** Bucket = 1 heure. Alimente `widget_history_hour`. */
    val hour = TierAggregator(bucketSizeMs = 3_600_000L)

    /** Bucket = 1 jour. Alimente `widget_history_day`. */
    val day = TierAggregator(bucketSizeMs = 86_400_000L)
}
