package com.jeanloickdt.auth

/**
 * État du flow de setup serveur, exposé par `GET /api/status` pour
 * diriger le browser vers le bon écran (V1 first-launch flow).
 *
 *   NeedsLicence  → page /setup (licence pas encore activée)
 *   NeedsWelcome  → page /welcome (licence activée mais welcome pas vu)
 *   Ready         → page /login standard
 *
 * Voir [SetupStateService.compute] pour la logique de calcul +
 * l'auto-heal en cas de fichier `setup.done` manquant alors qu'un
 * admin existe déjà en DB.
 */
enum class SetupState {
    NeedsLicence,
    NeedsWelcome,
    Ready
}
