package com.jeanloickdt.common

/**
 * Ce que le serveur vérifie d'une apparence : la FORME, rien d'autre.
 *
 * ## Pourquoi ces fonctions vivent ICI
 *
 * Elles étaient privées dans les routes des projets. Les règles ont
 * maintenant la même apparence, et recopier trois lignes de validation dans
 * un second fichier est exactement la façon dont deux vérités finissent par
 * diverger : une borne relevée d'un côté, un caractère autorisé de l'autre,
 * et un `.iiot` valide pour un projet devient invalide pour la règle qu'il
 * contient.
 *
 * ## Ce qu'elles ne vérifient PAS
 *
 * Ni le catalogue d'icônes ni la palette. C'est voulu : l'app en ajoute quand
 * elle veut sans qu'on déploie ici, et une clé qu'une version ne connaît pas
 * retombe chez elle sur un repli. Valider la liste ici obligerait à déployer
 * le serveur pour ajouter une icône, et ferait refuser un projet exporté par
 * une version plus récente.
 *
 * Ce qu'on refuse, c'est ce qui n'est manifestement pas une clé : trop long,
 * ou avec autre chose que des minuscules, des chiffres, un tiret ou un
 * souligné. Sans cette borne, ce champ devient un endroit où ranger n'importe
 * quoi dans la base de quelqu'un d'autre.
 */
object Appearance {

    /**
     * ABSENTE ou VIDE est valide, et veut dire « pas de clé ». Une première
     * version rendait 400 sur une chaîne vide, ce qui aurait fait échouer le
     * retrait d'une icône — le geste le plus banal après l'avoir posée.
     */
    fun isKey(raw: String?): Boolean {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return true
        return v.length <= KEY_MAX &&
            v.all { it.isDigit() || it in 'a'..'z' || it == '-' || it == '_' }
    }

    /** La clé telle qu'on la range : sans blancs, et `null` plutôt que vide. */
    fun key(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    const val KEY_MAX = 32

    const val BAD_REQUEST = "icon and color must be at most 32 chars of [a-z0-9_-]"
}
