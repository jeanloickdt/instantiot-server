package com.jeanloickdt.auth

import com.jeanloickdt.auth.domain.UserRepository
import org.slf4j.LoggerFactory

/**
 * Service qui combine [LicenceValidator], [UserRepository] et
 * [SetupStateStore] pour calculer l'état du setup serveur.
 *
 * Utilisé par :
 *  - `GET /api/status` pour répondre `setup_state` au browser
 *  - le boot serveur pour log l'état (visibilité opérationnelle)
 *
 * **Logique** :
 * ```
 * licence non activée            → NeedsLicence
 * marker setup.done existe       → Ready
 * marker absent + admin existe   → Ready (auto-heal : marker recréé)
 * marker absent + pas d'admin    → NeedsWelcome (cas légitime)
 * ```
 *
 * **Auto-heal** : si quelqu'un delete `setup.done` accidentellement
 * (ou backup/restore qui oublie le fichier), on présume qu'un admin
 * existant a forcément déjà vu le welcome (puisque c'est la seule
 * façon de finaliser ce flow). On recrée le marker silencieusement
 * + log warning, et on retourne Ready.
 */
class SetupStateService(
    private val licenceValidator: LicenceValidator = LicenceValidator,
    private val userRepository: UserRepository,
    private val setupStateStore: SetupStateStore = SetupStateStore()
) {

    private val log = LoggerFactory.getLogger("SetupStateService")

    fun compute(): SetupState {
        if (!licenceValidator.isActivated()) return SetupState.NeedsLicence

        if (setupStateStore.isComplete()) return SetupState.Ready

        // Marker absent — vérifier auto-heal via DB
        val adminExists = userRepository.findByUsername("admin") != null
        if (adminExists) {
            log.warn(
                "setup.done missing but admin user exists — auto-healing " +
                    "(re-creating marker, returning Ready)"
            )
            setupStateStore.markComplete()
            return SetupState.Ready
        }

        return SetupState.NeedsWelcome
    }
}
