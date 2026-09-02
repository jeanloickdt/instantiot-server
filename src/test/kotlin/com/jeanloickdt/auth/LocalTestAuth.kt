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

package com.jeanloickdt.auth

/**
 * L'authentification locale, pour les tests.
 *
 * Le pendant du nuage s'appelle `IiaTestAuth` et frappe des jetons RS256
 * verifies par un JWKS en memoire, parce que le relais du nuage ne sait
 * plus verifier un mot de passe : l'identite appartient a iia.
 *
 * Ici c'est l'inverse, et c'est la difference qui compte entre les deux
 * editions : ce serveur EST l'autorite. Il frappe ses jetons et les
 * verifie, avec le meme [HmacTokenService] qu'en production — un test
 * passe donc par le chemin reel, pas par une porte ouverte pour lui.
 */
object LocalTestAuth {

    val service = HmacTokenService("test-secret", "instantiot-server", "instantiot-app")

    /**
     * Frappe un jeton pour ce sujet.
     *
     * @param tokenVersion le plancher de revocation. Un jeton plus vieux que
     *        la version du compte est refuse — c'est ce que « deconnecter
     *        partout » ferme.
     */
    fun token(sub: String, tokenVersion: Int = 0): String = service.issue(sub, tokenVersion)
}
