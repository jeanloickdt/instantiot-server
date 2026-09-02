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

package com.jeanloickdt.database

import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.signal.data.SignalTables
import java.io.File

/**
 * Une base neuve, par test.
 *
 * ## Pourquoi une fonction plutot que six lignes recopiees
 *
 * Chaque classe montait sa base a la main, en listant les tables :
 *
 *     val db = File.createTempFile("instantiot-x-", ".db").apply { deleteOnExit() }
 *     DatabaseFactory.init(UserTable, ProjectTable, …, dbFile = db)
 *
 * La liste a change trois fois pendant le portage 2.0, et chaque
 * changement demandait de la corriger partout. Un test qui oublie une
 * table echoue sur « no such table », loin de ce qu'il eprouvait.
 *
 * Ici la liste vit a UN endroit, et c'est la meme que celle de
 * `Application.kt` — un test tourne donc sur le schema de la production,
 * jamais sur un sous-ensemble qui l'arrange.
 *
 * Le pendant du nuage s'appelle `PostgresTestBase` et partage un pool,
 * parce que Postgres n'aime pas qu'on le rouvre. SQLite, lui, offre un
 * fichier neuf pour rien : chaque test part donc d'une table rase, sans
 * `TRUNCATE` ni ordre d'execution a respecter.
 */
object TestDatabase {

    /** Toutes les tables, comme en production : le schema ne se devine pas. */
    private val ALL = arrayOf(
        UserTable, ProjectTable, DeviceTable,
        *SignalTables.ALL, *AutomationTables.ALL
    )

    /** Une base jetable, initialisee, et rendue au cas ou le test la lise. */
    fun fresh(nom: String = "test"): File {
        val db = File.createTempFile("instantiot-$nom-", ".db").apply { delete(); deleteOnExit() }
        DatabaseFactory.init(*ALL, dbFile = db)
        return db
    }
}
