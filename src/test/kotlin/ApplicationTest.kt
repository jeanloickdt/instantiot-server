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

package com.jeanloickdt

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {

    @Test
    fun testRoot() = testApplication {
        // Boot the REAL module(), but against a throwaway DB — NEVER the user's
        // production ~/.instantiot/instantiot.db. module() runs migrations, marks
        // devices offline, etc.; without an injected dbFile this test would
        // mutate (and could one day delete) real data.
        val tmpDb = File.createTempFile("instantiot-apptest-", ".db").apply { deleteOnExit() }
        application {
            module(dbFile = tmpDb)
        }
        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

}