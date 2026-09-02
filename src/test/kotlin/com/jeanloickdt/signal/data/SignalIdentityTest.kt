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

package com.jeanloickdt.signal.data

import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `signals.id` comme clé primaire — ce que l'étape 0 du passage au modèle
 * signal change, et ce qu'elle doit continuer à garantir.
 *
 * La clé composite `(owner, device, address)` rendait la vérification
 * d'appartenance gratuite : `find(ownerId, ...)` ne pouvait résoudre que ce
 * qui était déjà au bon compte. Un entier global n'a pas cette propriété —
 * ces tests vérifient que [ExposedSignalRepository.findById] la reconstruit
 * explicitement, plutôt que de supposer que rien ne peut mal tourner.
 */
class SignalIdentityTest {

    private lateinit var repo: ExposedSignalRepository

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.fresh()
        repo = ExposedSignalRepository()
    }

    // ── L'entier existe, et il est stable ─────────────────────────────────

    @Test
    fun `a declared signal receives an id`() {
        repo.create("u1", "dev1", 5, "temp", "float", nowMs = 1000L)
        val row = repo.find("u1", "dev1", 5)!!
        assertNotEquals(0L, row.id, "id=0 est la valeur par défaut de test, pas une vraie identité")
    }

    @Test
    fun `the id survives a lookup by triplet or by id alike`() {
        repo.create("u1", "dev1", 5, "temp", "float", nowMs = 1000L)
        val byTriplet = repo.find("u1", "dev1", 5)!!
        val byId = repo.findById("u1", byTriplet.id)!!
        assertEquals(byTriplet.id, byId.id)
        assertEquals(byTriplet.label, byId.label)
    }

    // ── (device_id, address) reste unique — owner_id n'y est plus ─────────

    @Test
    fun `a board cannot declare the same address twice`() {
        assertTrue(repo.create("u1", "dev1", 5, "a", "float", nowMs = 1000L))
        assertEquals(false, repo.create("u1", "dev1", 5, "b", "int", nowMs = 1000L))
    }

    @Test
    fun `two different accounts get two different ids for the same address`() {
        // Deux comptes, chacun son device, tous deux declarent I5. La cle
        // primaire est GLOBALE : rien ne garantit que les deux id different
        // par construction comme (owner, device, address) le garantissait —
        // c'est le sequencement de la base qui s'en charge, et ce test le
        // constate plutot que de le supposer.
        repo.create("u1", "dev-u1", 5, "a", "float", nowMs = 1000L)
        repo.create("u2", "dev-u2", 5, "b", "float", nowMs = 1000L)
        val idU1 = repo.find("u1", "dev-u1", 5)!!.id
        val idU2 = repo.find("u2", "dev-u2", 5)!!.id
        assertNotEquals(idU1, idU2)
    }

    // ── La regle 6 : findById sans le bon owner ne resout rien ────────────

    @Test
    fun `findById refuses a signal that belongs to another account`() {
        // LE test qui justifie tout le fichier. La cle composite d'avant
        // rendait cette isolation gratuite ; l'entier global ne l'a plus,
        // et c'est findById(ownerId, id) qui doit la reconstruire — pas
        // par accident, par vérification explicite du owner_id.
        repo.create("u1", "dev1", 5, "temp", "float", nowMs = 1000L)
        val id = repo.find("u1", "dev1", 5)!!.id

        assertNull(
            repo.findById("u2", id),
            "u2 a lu la ligne de u1 par un id devine — c'est exactement la fuite que la regle 6 interdit"
        )
        assertNotEquals(null, repo.findById("u1", id))
    }

    @Test
    fun `an id that does not exist resolves to nothing, for anyone`() {
        assertNull(repo.findById("u1", 999_999L))
    }
}
