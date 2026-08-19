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

package com.jeanloickdt.relay

import com.jeanloickdt.signal.SignalFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L'accord avec la bibliothèque, octet par octet.
 *
 * Les trames d'or viennent de la description du protocole, pas de ce code, et
 * ce sont **exactement** celles que les tests de la lib décodent de leur côté.
 * Les deux dépôts sont compilés par des chaînes différentes : rien d'autre ne
 * verrait un champ déplacé, et le symptôme serait un bouton qui ne fait rien.
 */
class EventFrameTest {

    /** `I5`, appui. Dix octets. */
    private val PRESS_AT_I5 = byteArrayOf(
        0xAA.toByte(), 0x01, 0x05, 0x00, 0x00, 0x01, 0x05, 0x21, 0x01, 0x6A
    )

    // ── L'accord avec la lib ──────────────────────────────────────────────

    @Test
    fun `the frame the library decodes is the frame we build`() {
        val built = EventFrame.build(address = 5, kind = 0x01)

        assertTrue(built.contentEquals(PRESS_AT_I5),
            "octet pour octet : " + built.joinToString(" ") { "%02X".format(it) })
        assertEquals(10, built.size,
            "contre 19 pour la meme chose adressee par un nom de quatre lettres")
    }

    @Test
    fun `an event says where and what, and nothing else`() {
        assertEquals(5, EventFrame.address(PRESS_AT_I5))
        assertEquals(0x01, EventFrame.kind(PRESS_AT_I5))
        assertTrue(EventFrame.isEvent(PRESS_AT_I5))
    }

    @Test
    fun `the widget type is absent — and that is the point`() {
        // Le type ne voyage pas : la carte le sait par son bloc, l'app par sa
        // mise en page. Sur le fil il n'y a que TYPE_EVENT, le meme pour un
        // bouton, un interrupteur et un joystick.
        val fromButton = EventFrame.build(5, 0x01)
        val fromSwitch = EventFrame.build(5, 0x03)

        assertEquals(EventFrame.TYPE_EVENT, FrameParser.extractType(fromButton))
        assertEquals(EventFrame.TYPE_EVENT, FrameParser.extractType(fromSwitch))
    }

    // ── Ce qui distingue un EVENT d'un SIGNAL ─────────────────────────────

    @Test
    fun `a signal at the same address is not an event`() {
        val signal = SignalFrame.build(5, SignalFrame.TAG_FLOAT, SignalFrame.floatBytes(1f))

        assertNull(EventFrame.address(signal),
            "sinon une mesure de temperature declencherait un appui de bouton")
        assertTrue(!EventFrame.isEvent(signal))
    }

    @Test
    fun `an event is not a signal either`() {
        assertNull(SignalFrame.address(PRESS_AT_I5))
        assertTrue(!SignalFrame.isSignal(PRESS_AT_I5))
    }

    // ── La charge utile composée ──────────────────────────────────────────

    @Test
    fun `a position travels whole, in one frame`() {
        // Une position est UNE chose. La decouper en deux adresses ferait
        // traverser a la carte une position qui n'a jamais existe — sur deux
        // servos, ca se voit.
        val payload = SignalFrame.floatBytes(0.5f) + SignalFrame.floatBytes(-0.25f)
        val frame = EventFrame.build(address = 7, kind = 0x01, payload = payload)

        assertEquals(7, EventFrame.address(frame))
        assertEquals(8, FrameParser.extractPayload(frame)!!.size,
            "x et y dans la meme trame, pas dans deux")
    }

    // ── La liste d'appareils, que le relais retire ────────────────────────

    @Test
    fun `the app names the board, the relay strips it, the board gets none`() {
        val fromApp = EventFrame.build(5, 0x01, deviceId = "dev-tt")

        assertEquals(1, fromApp[4].toInt(), "l'app dit a quelle carte elle parle")
        assertEquals(5, EventFrame.address(fromApp), "et l'adresse se relit malgre le decalage")

        val toBoard = FrameParser.trimDeviceHeader(fromApp)!!
        assertEquals(0, toBoard[4].toInt(),
            "la lib EXIGE DEV_COUNT a 0 : c'est ainsi qu'elle distingue un evenement d'un widget")
        assertTrue(toBoard.contentEquals(PRESS_AT_I5),
            "et ce qui sort du relais est exactement la trame d'or")
    }

    // ── Les refus ─────────────────────────────────────────────────────────

    @Test
    fun `a malformed address is not guessed`() {
        // Un TYPE d'evenement avec un creneau WID de plusieurs octets.
        val body = byteArrayOf(0x00, 0x04) + "btn1".toByteArray() +
            byteArrayOf(EventFrame.TYPE_EVENT.toByte(), 0x01)
        var crc = 0
        for (b in body) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        val frame = byteArrayOf(0xAA.toByte(), 0x01, body.size.toByte(), 0x00) + body + byteArrayOf(crc.toByte())

        assertNull(EventFrame.address(frame))
    }

    @Test
    fun `an ordinary widget frame is left alone`() {
        val widget = byteArrayOf(
            0xAA.toByte(), 0x01, 0x08, 0x00,
            0x00, 0x04, 'b'.code.toByte(), 't'.code.toByte(), 'n'.code.toByte(), '1'.code.toByte(),
            0x01, 0x01, 0x00
        )
        assertNull(EventFrame.address(widget),
            "un appui sur btn1 n'est pas un appui a une adresse")
    }

    // ── Ce que le relais laisse passer, et ce qu'il refuse ────────────────
    //
    // La règle vit hors du gestionnaire WebSocket pour être vérifiable ici.
    // Enfouie dans une boucle de socket, elle serait une règle que personne
    // ne teste — et c'est exactement ainsi que trois défauts sont passés
    // aujourd'hui.

    private fun repoWith(vararg declared: Int): com.jeanloickdt.signal.domain.SignalRepository =
        object : com.jeanloickdt.signal.domain.SignalRepository {
            override fun find(ownerId: String, deviceId: String, address: Int) =
                if (address in declared && ownerId == "u1" && deviceId == "tt")
                    com.jeanloickdt.signal.domain.SignalRow(
                        ownerId = ownerId, deviceId = deviceId, address = address,
                        label = "x", type = "bool", unit = "", decimals = 0,
                        minValue = null, maxValue = null, nature = "value",
                        historised = false, replayOnConnect = true,
                        direction = "setpoint", lastPayload = null, lastSeenAt = null
                    ) else null
            override fun listByDevice(ownerId: String, deviceId: String) = emptyList<com.jeanloickdt.signal.domain.SignalRow>()
            override fun listByOwner(ownerId: String) = emptyList<com.jeanloickdt.signal.domain.SignalRow>()
            override fun create(ownerId: String, deviceId: String, address: Int, label: String, type: String,
                                unit: String, decimals: Int, minValue: Double?, maxValue: Double?,
                                nature: String, historised: Boolean, replayOnConnect: Boolean,
                                direction: String, nowMs: Long) = false
            override fun nextFreeAddress(ownerId: String, deviceId: String): Int? = null
            override fun update(ownerId: String, deviceId: String, address: Int, label: String?, unit: String?,
                                decimals: Int?, minValue: Double?, maxValue: Double?, historised: Boolean?,
                                replayOnConnect: Boolean?, direction: String?, type: String?, nowMs: Long) = false
            override fun delete(ownerId: String, deviceId: String, address: Int) = false
            override fun deleteByDevice(ownerId: String, deviceId: String) = 0
            override fun deleteByOwner(ownerId: String) = 0
            override fun touch(ownerId: String, deviceId: String, address: Int, payloadB64: String, atMs: Long) = false
        }

    @Test
    fun `a declared address goes through`() {
        assertNull(EventFrame.refusalFor(PRESS_AT_I5, "u1", "tt", repoWith(5)))
    }

    @Test
    fun `an undeclared address is refused and named`() {
        assertEquals(
            CommandFailedReason.UNDECLARED_ADDRESS,
            EventFrame.refusalFor(PRESS_AT_I5, "u1", "tt", repoWith(6)),
            "relayer en silence renverrait l'utilisateur chercher la panne dans son croquis"
        )
    }

    @Test
    fun `an address declared on ANOTHER board is refused`() {
        assertEquals(
            CommandFailedReason.UNDECLARED_ADDRESS,
            EventFrame.refusalFor(PRESS_AT_I5, "u1", "bb", repoWith(5)),
            "les adresses sont enumerees par carte : le I5 de tt n'est pas celui de bb"
        )
    }

    @Test
    fun `another tenant is refused`() {
        assertEquals(
            CommandFailedReason.UNDECLARED_ADDRESS,
            EventFrame.refusalFor(PRESS_AT_I5, "u2", "tt", repoWith(5))
        )
    }

    @Test
    fun `a node without a signal store relays everything`() {
        assertNull(EventFrame.refusalFor(PRESS_AT_I5, "u1", "tt", null),
            "un noeud sans depot n'a pas a devenir muet")
    }

    @Test
    fun `what is not an event is not this rule's business`() {
        val signal = SignalFrame.build(5, SignalFrame.TAG_FLOAT, SignalFrame.floatBytes(1f))
        assertNull(EventFrame.refusalFor(signal, "u1", "tt", repoWith()),
            "une trame de signal ne passe pas par ce controle")
    }
}
