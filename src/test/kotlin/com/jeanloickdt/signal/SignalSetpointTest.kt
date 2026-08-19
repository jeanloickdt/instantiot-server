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

import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.automation.data.AutomationTables
import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.signal.data.SignalTable
import com.jeanloickdt.signal.data.SqliteSignalRepository
import com.jeanloickdt.widget.data.WidgetHistoryDayTable
import com.jeanloickdt.widget.data.WidgetHistoryHourTable
import com.jeanloickdt.widget.data.WidgetHistoryMinTable
import com.jeanloickdt.widget.data.WidgetHistoryNumericTable
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The setpoint — what the app WANTS, as opposed to what the board says it IS.
 *
 * The property these tests defend is the one §1 of the model rests on: a
 * gesture is an event and dies with the connection, a setpoint is a state and
 * survives it. Everything here is about surviving.
 */
class SignalSetpointTest {

    private val OWNER = "u1"
    private val TT = "dev-tt"

    private lateinit var signals: SqliteSignalRepository
    private val sent = mutableListOf<ByteArray>()
    private var deviceOnline = true

    @BeforeTest
    fun setup() {
        val db = File.createTempFile("instantiot-setpoint-", ".db").apply { deleteOnExit() }
        DatabaseFactory.init(
            UserTable, ProjectTable, DeviceTable, WidgetTable,
            WidgetHistoryTable, WidgetHistoryNumericTable,
            WidgetHistoryMinTable, WidgetHistoryHourTable, WidgetHistoryDayTable,
            SignalTable, *AutomationTables.ALL,
            dbFile = db
        )
        signals = SqliteSignalRepository()
        sent.clear()
        broadcast.clear()
        deviceOnline = true
    }

    private val send: suspend (String, ByteArray) -> Boolean = { _, frame ->
        if (deviceOnline) { sent += frame; true } else false
    }

    /** What the apps watching the project would receive. */
    private val broadcast = mutableListOf<ByteArray>()

    private fun declare(
        address: Int,
        type: String = SignalTable.TYPE_FLOAT,
        direction: String = SignalTable.DIRECTION_SETPOINT,
        min: Double? = null,
        max: Double? = null
    ) = signals.create(
        ownerId = OWNER, deviceId = TT, address = address, label = "s$address",
        type = type, minValue = min, maxValue = max, direction = direction, nowMs = 0
    )

    private suspend fun write(address: Int, value: Double? = null, text: String? = null) =
        SignalSetpoint.write(signals, OWNER, TT, address, value, text, 1_000, send) { broadcast += it }

    // ── Le chemin nominal ─────────────────────────────────────────────────

    @Test
    fun `a setpoint is stored AND sent`(): Unit = runBlocking {
        declare(5)

        val r = write(5, 21.5)

        assertTrue(r is SignalSetpoint.Outcome.Delivered)
        assertEquals(1, sent.size)
        assertEquals(5, SignalFrame.address(sent[0]))
        assertEquals(21.5, SignalFrame.numericValue(sent[0])!!, 0.001)
        assertNotNull(signals.find(OWNER, TT, 5)!!.lastPayload,
            "stored too — the board may reboot a second later")
    }

    @Test
    fun `an offline board does not lose the setpoint — it is stored anyway`(): Unit = runBlocking {
        declare(5)
        deviceOnline = false

        val r = write(5, 19.0)

        assertTrue(r is SignalSetpoint.Outcome.Stored,
            "202, not 200: claiming OK would say the board acted on something it never saw")
        assertTrue(sent.isEmpty())
        assertNotNull(signals.find(OWNER, TT, 5)!!.lastPayload,
            "the intent is recorded — that is what makes the restore possible")
    }

    @Test
    fun `the board reconnects and finds its setpoint again`(): Unit = runBlocking {
        declare(5); declare(6, type = SignalTable.TYPE_BOOL)
        deviceOnline = false
        write(5, 19.0)
        write(6, 1.0)
        deviceOnline = true
        sent.clear()

        val restored = SignalSetpoint.restoreOnConnect(signals, OWNER, TT, send)

        assertEquals(2, restored)
        assertEquals(setOf(5, 6), sent.mapNotNull { SignalFrame.address(it) }.toSet())
        assertEquals(19.0, sent.first { SignalFrame.address(it) == 5 }.let { SignalFrame.numericValue(it)!! }, 0.001)
    }

    @Test
    fun `a measure is NEVER replayed — what the board is, is the board's to say`(): Unit = runBlocking {
        declare(1, direction = SignalTable.DIRECTION_MEASURE)
        // The board itself reported a value at some point.
        signals.touch(OWNER, TT, 1, java.util.Base64.getEncoder().encodeToString(SignalFrame.floatBytes(30f)), 500)

        assertEquals(0, SignalSetpoint.restoreOnConnect(signals, OWNER, TT, send))
        assertTrue(sent.isEmpty(), "replaying a measure would tell the board what it measured")
    }

    @Test
    fun `nothing to restore is not an error`(): Unit = runBlocking {
        declare(5)   // declared, never written
        assertEquals(0, SignalSetpoint.restoreOnConnect(signals, OWNER, TT, send))
    }

    // ── Les autres observateurs ───────────────────────────────────────────

    @Test
    fun `a display bound to the signal hears about it, even written from the app`(): Unit = runBlocking {
        declare(5)

        write(5, 21.5)

        assertEquals(1, broadcast.size,
            "a gauge showing this setpoint must react the instant a switch next to it changes it")
        assertEquals(21.5, SignalFrame.numericValue(broadcast[0])!!, 0.001)
    }

    @Test
    fun `the apps hear it even when the board is asleep`(): Unit = runBlocking {
        declare(5)
        deviceOnline = false

        write(5, 19.0)

        assertEquals(1, broadcast.size,
            "the SIGNAL changed — an observer subscribed to that, not to the board's reachability")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a refused write tells nobody`(): Unit = runBlocking {
        declare(1, direction = SignalTable.DIRECTION_MEASURE)

        write(1, 42.0)

        assertTrue(broadcast.isEmpty(), "nothing changed, so there is nothing to announce")
    }

    // ── Les refus ─────────────────────────────────────────────────────────

    @Test
    fun `the app cannot write a measure`(): Unit = runBlocking {
        declare(1, direction = SignalTable.DIRECTION_MEASURE)

        val r = write(1, 42.0)

        assertTrue(r is SignalSetpoint.Outcome.Refused)
        assertTrue("measure" in (r as SignalSetpoint.Outcome.Refused).reason,
            "otherwise a value would assert something no sensor ever reported: ${r.reason}")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `direction both accepts the app`(): Unit = runBlocking {
        declare(7, direction = SignalTable.DIRECTION_BOTH)
        assertTrue(write(7, 1.0) is SignalSetpoint.Outcome.Delivered)
    }

    @Test
    fun `an undeclared address is refused and names itself`(): Unit = runBlocking {
        val r = write(9, 1.0)
        assertTrue(r is SignalSetpoint.Outcome.Refused)
        assertTrue("I9" in (r as SignalSetpoint.Outcome.Refused).reason, r.reason)
    }

    @Test
    fun `a string signal needs text, not a number`(): Unit = runBlocking {
        declare(3, type = SignalTable.TYPE_STRING)

        assertTrue(write(3, value = 1.0) is SignalSetpoint.Outcome.Refused)
        assertTrue(write(3, text = "OK") is SignalSetpoint.Outcome.Delivered)
    }

    // ── Les bornes écrêtent, elles ne rejettent pas ───────────────────────

    @Test
    fun `a value out of range is clamped, never refused`(): Unit = runBlocking {
        declare(5, min = 0.0, max = 50.0)

        val r = write(5, 80.0)

        assertTrue(r is SignalSetpoint.Outcome.Delivered)
        assertEquals(50.0, (r as SignalSetpoint.Outcome.Delivered).value!!, 0.001)
        assertEquals(50.0, SignalFrame.numericValue(sent[0])!!, 0.001,
            "the board receives the clamped value — a spike must not vanish, but it must not travel either")
    }

    @Test
    fun `a bool takes anything non-zero as true`(): Unit = runBlocking {
        declare(6, type = SignalTable.TYPE_BOOL)

        write(6, 1.0); write(6, 0.0)

        assertEquals(1.0, SignalFrame.numericValue(sent[0])!!, 0.0)
        assertEquals(0.0, SignalFrame.numericValue(sent[1])!!, 0.0)
    }

    @Test
    fun `an int keeps its exact value on the wire`(): Unit = runBlocking {
        declare(8, type = SignalTable.TYPE_INT)
        write(8, 4242.0)
        assertEquals(4242.0, SignalFrame.numericValue(sent[0])!!, 0.0)
    }

    @Test
    fun `the stored payload is the value, replayable as-is`(): Unit = runBlocking {
        declare(5)
        write(5, 21.5)
        val stored = signals.find(OWNER, TT, 5)!!.lastPayload!!
        sent.clear()

        SignalSetpoint.restoreOnConnect(signals, OWNER, TT, send)

        assertEquals(21.5, SignalFrame.numericValue(sent.single())!!, 0.001,
            "the restore rebuilds a frame from the stored bytes — no second encoding path to drift")
        assertNull(java.util.Base64.getDecoder().decode(stored).let { if (it.size == 4) null else "wrong size" })
    }

    // ── Le rejeu est un choix du signal, pas une regle en dur ─────────────

    @Test
    fun `an action is never replayed — nothing is there to open a gate by itself`(): Unit = runBlocking {
        signals.create(
            ownerId = OWNER, deviceId = TT, address = 9, label = "Portail",
            type = SignalTable.TYPE_BOOL, direction = SignalTable.DIRECTION_SETPOINT,
            replayOnConnect = false, nowMs = 0
        )
        write(9, 1.0)
        assertNotNull(signals.find(OWNER, TT, 9)!!.lastPayload, "precondition : une valeur a circule")
        sent.clear()

        assertEquals(0, SignalSetpoint.restoreOnConnect(signals, OWNER, TT, send),
            "rejouer « ouvre le portail » a chaque hoquet du WiFi n'est pas un defaut d'affichage")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a setpoint still comes back — that is what makes it a state`(): Unit = runBlocking {
        declare(5)   // replayOnConnect vaut true par defaut
        write(5, 21.5)
        sent.clear()

        assertEquals(1, SignalSetpoint.restoreOnConnect(signals, OWNER, TT, send))
    }

    @Test
    fun `the flag defaults to true, so nothing changed for what existed`(): Unit = runBlocking {
        declare(5)
        assertTrue(signals.find(OWNER, TT, 5)!!.replayOnConnect,
            "le comportement d'avant ce champ est le defaut : le desactiver est une decision")
    }
}
