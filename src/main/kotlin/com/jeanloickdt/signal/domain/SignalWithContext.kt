package com.jeanloickdt.signal.domain

/**
 * Où vit un signal — le projet et l'appareil qui le portent, avec leurs
 * libellés vivants.
 *
 * Le sélecteur de l'éditeur de règles liste TOUS les signaux du compte, tous
 * projets confondus : une règle peut lire dans Serre et écrire dans Atelier.
 * Sans ce contexte, l'utilisateur choisit entre trois « Temp » identiques —
 * et l'app afficherait un identifiant d'appareil pour les distinguer, ce qui
 * ne distingue rien.
 *
 * Les libellés viennent d'une jointure vivante, jamais d'une copie stockée :
 * un projet renommé doit apparaître renommé au rafraîchissement suivant.
 * C'est la même politique que `cachedLabel` côté définition de règle — retiré
 * à l'écriture, reposé à la lecture. Un libellé périmé ne survit jamais en
 * base.
 */
data class SignalContext(
    val projectId: String,
    val projectLabel: String,
    val deviceLabel: String
)

/** Un signal et son contexte — ce que le sélecteur consomme. */
data class SignalWithContext(
    val signal: SignalRow,
    val context: SignalContext
)

/**
 * La lecture jointe signal ↔ appareil ↔ projet.
 *
 * ## Pourquoi ce n'est PAS une méthode de [SignalRepository]
 *
 * [SignalRepository] est le chemin chaud, et il est enveloppé par
 * [com.jeanloickdt.signal.data.CachedSignalRepository], qui garde une entrée
 * par signal pour toute la durée de vie du processus. Un libellé de projet ou
 * d'appareil n'a rien à faire dans ce cache : il change au renommage, et rien
 * dans le chemin d'invalidation de ce cache — qui n'écoute que les mutations
 * de signaux — ne le verrait passer. Un nom périmé y resterait jusqu'au
 * redémarrage.
 *
 * Séparer les deux interfaces rend cette erreur impossible à commettre plutôt
 * qu'interdite par convention.
 *
 * ## Coût
 *
 * Chemin froid, exclusivement. Ces deux lectures servent la déclaration et
 * l'éditeur de règles ; aucune trame ne passe par ici.
 */
interface SignalContextReader {

    /**
     * Tous les signaux du compte, avec leur projet et leur appareil.
     *
     * Un signal dont l'appareil ou le projet ne résout plus est **omis** :
     * c'est une incohérence référentielle, et le proposer dans le sélecteur
     * ne mènerait qu'à une règle immédiatement marquée `signal-deleted`.
     */
    fun listByOwnerWithContext(ownerId: String): List<SignalWithContext>

    /**
     * Le contexte d'un appareil déjà résolu — pour les routes qui travaillent
     * appareil par appareil et n'ont donc pas besoin de la liste entière.
     *
     * `null` quand l'appareil n'existe pas, ne vous appartient pas, ou pointe
     * un projet disparu.
     */
    fun contextOf(ownerId: String, deviceId: String): SignalContext?
}
