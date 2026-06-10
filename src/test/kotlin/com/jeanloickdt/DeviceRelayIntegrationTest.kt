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

import com.jeanloickdt.auth.JwtConfig
import com.jeanloickdt.auth.configureAuth
import com.jeanloickdt.auth.data.UserTable
import com.jeanloickdt.database.DatabaseFactory
import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.device.domain.DeviceConnectivity
import com.jeanloickdt.device.domain.DeviceType
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.relay.FrameParser
import com.jeanloickdt.relay.configureAppRelay
import com.jeanloickdt.relay.startDeviceRelay
import com.jeanloickdt.widget.data.WidgetHistoryDayTable
import com.jeanloickdt.widget.data.WidgetHistoryHourTable
import com.jeanloickdt.widget.data.WidgetHistoryMinTable
import com.jeanloickdt.widget.data.WidgetHistoryNumericTable
import com.jeanloickdt.widget.data.WidgetHistoryTable
import com.jeanloickdt.widget.data.WidgetTable
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeoutOrNull
import org.mindrot.jbcrypt.BCrypt
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end integration test of the device TCP relay AS IT IS TODAY (blocking
 * java.net sockets). This is the REFERENCE NET for the non-blocking ktor-network
 * rewrite: it must pass on the current code, then pass UNCHANGED on the rewritten
 * code — proving identical externally-observable behaviour.
 *
 * Each test wires the real relay (real ServerSocket on a free port) + the real
 * app WebSocket relay, against an isolated temp DB. A fake ESP (raw TCP socket)
 * connects and a fake app (Ktor test WS client) observes the broadcast.
 *
 * Cases (the three that break silently if the rewrite is wrong):
 *  1. nominal      — connect → handshake → valid frame → app receives device_online + the frame
 *  2. fragmented   — the frame split across two TCP writes → still reassembled & broadcast
 *  3. timeout      — connect → handshake → send nothing → device_offline is broadcast
 */
class DeviceRelayIntegrationTest {

    private val deviceToken = "esp-token-abcdef-123456"
    private lateinit var jwt: String
    private lateinit var projectId: String
    private val widgetId = "w1"

    @BeforeTest
    fun setup() {
        val tmpDb = java.io.File.createTempFile("instantiot-relay-test-", ".db").apply { deleteOnExit() }
        DatabaseFactory.init(
            UserTable, ProjectTable, DeviceTable, WidgetTable,
            WidgetHistoryTable, WidgetHistoryNumericTable,
            WidgetHistoryMinTable, WidgetHistoryHourTable, WidgetHistoryDayTable,
            dbFile = tmpDb
        )
        JwtConfig.init("instantiot-server", "instantiot-app")

        val userId = userRepository.create("alice", BCrypt.hashpw("pw", BCrypt.gensalt()))
        jwt = JwtConfig.generateToken(userId)
        projectId = projectRepository.create(ownerId = userId, name = "P")
        deviceRepository.create(
            name = "esp1",
            projectId = projectId,
            ownerId = userId,
            tokenHash = FrameParser.hashDeviceToken(deviceToken),
            deviceType = DeviceType.ESP32,
            connectivity = DeviceConnectivity.WIFI
        )
    }

    @Test
    fun `nominal — handshake then a valid frame is broadcast to the app`() = testApplication {
        val tcpPort = reserveFreePort()
        wireRelay(tcpPort)
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/ws/app", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
            send(Frame.Text(projectId))
            send(Frame.Text("install-1"))

            val frame = deviceFrame(widgetId, TYPE_GAUGE, EV_SETVALUE, floatLE(23.5f))
            Socket("localhost", awaitBoundPort(tcpPort)).use { esp ->
                esp.getOutputStream().apply {
                    write(handshake(deviceToken))
                    write(frame)
                    flush()
                }
                val (texts, binary) = collectUntilOnlineAndBinary()
                assertTrue(texts.any { it.contains("device_online") }, "app must receive device_online")
                assertTrue(binary != null, "app must receive the relayed binary frame")
                assertContentEquals(frame, binary!!)
            }
        }
    }

    @Test
    fun `fragmented — a frame split across two TCP writes is reassembled and broadcast`() = testApplication {
        val tcpPort = reserveFreePort()
        wireRelay(tcpPort)
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/ws/app", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
            send(Frame.Text(projectId))
            send(Frame.Text("install-1"))

            val frame = deviceFrame(widgetId, TYPE_GAUGE, EV_SETVALUE, floatLE(42.0f))
            Socket("localhost", awaitBoundPort(tcpPort)).use { esp ->
                val out = esp.getOutputStream()
                out.write(handshake(deviceToken)); out.flush()
                // split the frame: 4-byte header first, pause, then body+CRC
                out.write(frame, 0, 4); out.flush()
                Thread.sleep(150)
                out.write(frame, 4, frame.size - 4); out.flush()

                val (texts, binary) = collectUntilOnlineAndBinary()
                assertTrue(texts.any { it.contains("device_online") })
                assertContentEquals(frame, binary!!)
            }
        }
    }

    @Test
    fun `timeout — a silent device after handshake is detected offline`() = testApplication {
        val tcpPort = reserveFreePort()
        wireRelay(tcpPort)
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/ws/app", request = { header(HttpHeaders.Authorization, "Bearer $jwt") }) {
            send(Frame.Text(projectId))
            send(Frame.Text("install-1"))

            // heartbeat=800ms → soTimeout = 800*2.5 = 2000ms (the min clamp). Then send NOTHING.
            Socket("localhost", awaitBoundPort(tcpPort)).use { esp ->
                esp.getOutputStream().apply { write(handshake("$deviceToken:800")); flush() }

                val texts = mutableListOf<String>()
                withTimeoutOrNull(8000) {
                    while (texts.none { it.contains("device_offline") }) {
                        when (val f = incoming.receive()) {
                            is Frame.Text -> texts += f.readText()
                            else -> {}
                        }
                    }
                }
                assertTrue(texts.any { it.contains("device_online") }, "should have come online first")
                assertTrue(texts.any { it.contains("device_offline") }, "silent device must be detected offline")
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────

    /**
     * The ONLY place relay wiring lives. The 3 test assertions are the
     * behavioural contract and never change — only this wiring evolves.
     * With DI, every test builds its OWN SessionRegistry + broadcaster:
     * no shared global state, so no cross-test cleanup is needed.
     */
    private fun io.ktor.server.testing.ApplicationTestBuilder.wireRelay(tcpPort: Int) {
        val connections = com.jeanloickdt.relay.ConnectionRegistry()
        val buffers     = com.jeanloickdt.relay.HistoryBuffers()
        val lastValues  = com.jeanloickdt.relay.InMemoryLastValueCache()
        val presence    = com.jeanloickdt.relay.DbBackedPresenceStore(deviceRepository)
        val events      = com.jeanloickdt.relay.ControlEventBroadcaster(connections)
        application {
            configureAuth(userRepository)
            configureAppRelay(projectRepository, connections, events)
            startDeviceRelay(
                deviceRepository, widgetRepository,
                connections, buffers, lastValues, presence, events,
                tcpPort = tcpPort
            )
        }
    }

    /** Collects WS frames until both a device_online text and a binary frame are seen (or timeout). */
    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.collectUntilOnlineAndBinary(
        timeoutMs: Long = 6000
    ): Pair<List<String>, ByteArray?> {
        val texts = mutableListOf<String>()
        var binary: ByteArray? = null
        withTimeoutOrNull(timeoutMs) {
            while (binary == null || texts.none { it.contains("device_online") }) {
                when (val f = incoming.receive()) {
                    is Frame.Text -> texts += f.readText()
                    is Frame.Binary -> binary = f.readBytes()
                    else -> {}
                }
            }
        }
        return texts to binary
    }

    /** Handshake payload framed as [1-byte length][UTF-8 payload]. */
    private fun handshake(payload: String): ByteArray {
        val bytes = payload.toByteArray()
        return byteArrayOf(bytes.size.toByte()) + bytes
    }

    /** Polls until the relay's ServerSocket is bound (it binds asynchronously after app start). */
    private fun awaitBoundPort(port: Int, timeoutMs: Long = 4000): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try { Socket("localhost", port).close(); return port } catch (_: Exception) { Thread.sleep(50) }
        }
        error("device relay never bound on port $port")
    }

    private fun reserveFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) =
        assertTrue(expected.contentEquals(actual), "broadcast frame differs from the sent frame")

    // ── iWidgets v1 frame builders (mirror of FrameParser's wire format) ──
    private val TYPE_GAUGE = 0x03
    private val EV_SETVALUE = 0x01

    private fun crc8(data: ByteArray): Byte {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return (crc and 0xFF).toByte()
    }

    private fun floatLE(f: Float): ByteArray {
        val bits = f.toRawBits()
        return byteArrayOf(
            (bits and 0xFF).toByte(), ((bits ushr 8) and 0xFF).toByte(),
            ((bits ushr 16) and 0xFF).toByte(), ((bits ushr 24) and 0xFF).toByte()
        )
    }

    /** Device→server frame: AA|VER|LEN(LE)| DEV_COUNT(0)|WID_LEN|WID|TYPE|EVENT|PAYLOAD |CRC8 */
    private fun deviceFrame(widgetId: String, type: Int, event: Int, payload: ByteArray): ByteArray {
        val wid = widgetId.toByteArray()
        val body = byteArrayOf(0x00) +
            byteArrayOf(wid.size.toByte()) + wid +
            byteArrayOf(type.toByte(), event.toByte()) + payload
        val len = body.size
        return byteArrayOf(0xAA.toByte(), 0x01, (len and 0xFF).toByte(), ((len ushr 8) and 0xFF).toByte()) +
            body + byteArrayOf(crc8(body))
    }
}
