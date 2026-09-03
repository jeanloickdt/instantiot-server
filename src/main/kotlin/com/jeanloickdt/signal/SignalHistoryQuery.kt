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

package com.jeanloickdt.signal


/**
 * Décide quelle résolution servir, et **pourquoi** — jamais une liste vide en
 * silence.
 *
 * ## Ce que le contrat d'avant faisait de travers
 *
 * `granularity` avait `raw` pour défaut. Sur une plage de six mois, c'était
 * le pire choix possible : des centaines de milliers de points sérialisés
 * pour dessiner une courbe qui en montre mille. Une app qui n'a pas d'avis
 * ne devrait rien avoir à savoir des paliers — d'où `auto`, qui devient le
 * défaut.
 *
 * ## Le client garde le dernier mot
 *
 * Demander une résolution précise reste possible et reste servi : c'est son
 * plan, c'est sa donnée. `auto` n'est qu'un défaut utile, pas une politique
 * qu'on impose.
 *
 * ## Un refus dit toujours quoi faire
 *
 * « Le détail minute remonte à 30 jours » est une information, pas une
 * panne. Et le graphique est l'endroit exact où un compte gratuit rencontre
 * sa limite de rétention — c'est un moment de conversion, il ne doit
 * surtout pas ressembler à un serveur cassé.
 */
object SignalHistoryQuery {

    const val AUTO = "auto"

    /**
     * Combien de points une courbe mérite.
     *
     * **Deux mille, et le chiffre est calé sur un cas précis** : les
     * dernières 24 heures, qui est la vue par défaut et de loin la plus
     * regardée. En minute, une journée fait 1440 seaux ; il faut donc que la
     * cible soit au-dessus, sinon `auto` monte à l'heure et rend **24 points
     * pour une journée entière** — une ligne en escalier qui cache tout ce
     * qui s'est passé entre deux heures rondes.
     *
     * Les paliers vont de 60 en 60, donc le seuil décide où bascule chaque
     * palier : minute jusqu'à ~33 h, heure jusqu'à ~83 jours, jour au-delà.
     * Au-dessus de deux mille on paierait de la sérialisation et du réseau
     * pour un détail qu'aucun écran ne montre ; en dessous, on abîme la vue
     * la plus courante.
     */
    const val TARGET_POINTS = 2_000

    /**
     * Plafond dur sur le nombre de lignes rendues, toutes résolutions
     * confondues — y compris `raw`, qui n'en avait aucun.
     *
     * Vingt-quatre heures d'un signal à 1 Hz, c'est 86 400 objets sur le
     * chemin de réponse de l'app. Le plafond tronque et **le dit** ; il ne
     * ment jamais par omission.
     */
    const val MAX_ROWS = 5_000

    /** Du plus fin au plus grossier, avec la taille de leur seau. */
    val BUCKET_MS = linkedMapOf(
        "min"  to 60_000L,
        "hour" to 3_600_000L,
        "day"  to 86_400_000L
    )

    /** Les résolutions qu'un client peut nommer. `raw` n'a pas de seau. */
    val KNOWN = setOf("raw") + BUCKET_MS.keys

    /**
     * Ce que la route va faire, et ce qu'elle doit dire en le faisant.
     *
     * @param notice non-null quand le résultat diffère de la demande —
     *        c'est le texte qui empêche une limite de passer pour une panne.
     */
    data class Decision(
        val resolution: String,
        val notice: String? = null
    )

    /** Une demande qu'on ne peut pas servir du tout. */
    data class Refusal(val reason: String)

    /**
     * Le choix automatique : la résolution la plus fine dont le nombre de
     * seaux tient sous [TARGET_POINTS].
     *
     * **`raw` n'est jamais choisi automatiquement.** Sa densité est
     * inconnaissable d'avance — un signal à 100 Hz et un signal à un
     * échantillon par heure produisent le même intervalle de temps et des
     * volumes qui diffèrent d'un facteur cent mille. On ne peut pas
     * promettre mille points avec lui, donc `auto` ne le propose pas ; il
     * reste servi quand on le demande.
     */
    fun autoPick(fromMs: Long, toMs: Long): String {
        val span = (toMs - fromMs).coerceAtLeast(0L)
        for ((tier, bucketMs) in BUCKET_MS) {
            if (span / bucketMs <= TARGET_POINTS) return tier
        }
        // Une plage si large que même le jour dépasse la cible — presque
        // trois ans. On sert le jour quand même : c'est le plus grossier
        // qui existe, et refuser serait pire que rendre trois mille points.
        return BUCKET_MS.keys.last()
    }

    /**
     * Résout la demande contre les droits du compte.
     *
     * @param requested ce que le client a demandé, ou [AUTO].
     * @param windows   ce que le plan accorde, par palier.
     * @return la décision, ou un refus quand la résolution nommée n'existe pas.
     */
    fun resolve(
        requested: String,
        fromMs: Long,
        toMs: Long,
        windows: List<HistoryWindows.Window>
    ): Result<Decision> {
        val asked = requested.lowercase()
        if (asked != AUTO && asked !in KNOWN) {
            return Result.failure(
                IllegalArgumentException("Unknown resolution '$requested' (use auto|raw|min|hour|day)")
            )
        }

        val chosen = if (asked == AUTO) autoPick(fromMs, toMs) else asked
        val window = windows.firstOrNull { it.granularity == chosen }

        // Aucune fenêtre connue : auto-hébergé, ou la réponse du plan n'est
        // pas encore arrivée. Ne pas savoir n'est pas savoir que non — on
        // sert, sans rien annoncer.
        if (window == null) return Result.success(Decision(chosen))

        if (!window.available) {
            // Le palier n'est pas vendu. On ne rend PAS une liste vide : on
            // bascule sur ce qui existe et on le dit.
            val fallback = coarserAvailable(chosen, windows)
                ?: return Result.success(
                    Decision(chosen, "Aucun historique n'est conservé sur ce plan.")
                )
            return Result.success(
                Decision(fallback, "Le détail « $chosen » n'est pas inclus dans ce plan — vue « $fallback » servie.")
            )
        }

        val bound = window.fromMs
        if (bound != null && fromMs < bound) {
            // La plage déborde la rétention. On sert ce qu'on a et on nomme
            // la frontière — c'est là que se joue la conversion.
            return Result.success(
                Decision(chosen, "Le détail « $chosen » remonte à ${window.retention} — la partie plus ancienne n'est pas conservée.")
            )
        }

        return Result.success(Decision(chosen))
    }

    /** Le premier palier plus grossier que le plan accorde vraiment. */
    private fun coarserAvailable(from: String, windows: List<HistoryWindows.Window>): String? {
        val order = HistoryWindows.TIERS.map { it.granularity }
        val start = order.indexOf(from)
        if (start < 0) return null
        for (i in start + 1 until order.size) {
            val w = windows.firstOrNull { it.granularity == order[i] } ?: continue
            if (w.available) return w.granularity
        }
        return null
    }
}
