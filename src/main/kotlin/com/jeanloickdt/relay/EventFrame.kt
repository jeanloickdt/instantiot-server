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

/**
 * Un **EVENT** — un fait adressé par un octet.
 *
 * ## Pourquoi ce n'est pas un signal
 *
 * Une valeur est idempotente : trois appuis écrivent `1`, `1`, `1`, une seule
 * transition est observable, et deux appuis disparaissent. Un fait ne se
 * comporte pas ainsi — il se compte. C'est la seule raison pour laquelle cette
 * classe de messages existe, et aucun réglage ne la remplace.
 *
 * ## Ce que le serveur en fait
 *
 * Il le relaie et **n'en garde rien**. Pas de stockage, donc pas de rejeu à la
 * reconnexion : une valeur passante ne peut être rejouée par aucun mécanisme,
 * y compris par un bug. C'est une garantie de structure, là où d'autres
 * s'en remettent à une case à cocher correctement réglée — et sur un portail,
 * la différence n'est pas théorique.
 *
 * ## La disposition
 *
 * Celle de toujours, avec l'adresse là où un nom se trouvait :
 *
 * ```
 * AA │ VER │ LEN │ DEV_COUNT=0 │ WID_LEN=1 │ adresse │ TYPE=0x21 │ genre │ charge │ CRC
 * ```
 *
 * Le créneau EVENT porte enfin un événement — `CMD_PRESS`, `CMD_TOGGLE` — ce
 * pour quoi il avait été prévu. Le **type de widget n'y figure pas** : la carte
 * le sait par son propre bloc, l'app par sa mise en page, et le serveur n'en a
 * pas l'usage.
 */
object EventFrame {

    const val TYPE_EVENT = 0x21

    fun isEvent(frame: ByteArray): Boolean =
        FrameParser.extractType(frame) == TYPE_EVENT

    /**
     * L'adresse visée, ou `null` si ce n'est pas un EVENT bien formé.
     *
     * Renvoyer `null` pour un créneau qui ne fait pas exactement un octet est
     * délibéré : un événement dont l'adresse est malformée n'est pas un
     * événement, et deviner vaudrait moins que laisser tomber.
     */
    fun address(frame: ByteArray): Int? {
        if (!isEvent(frame)) return null
        return try {
            var o = 4
            val deviceCount = frame[o++].toInt() and 0xFF
            repeat(deviceCount) {
                val len = frame[o++].toInt() and 0xFF
                o += len
            }
            val widLen = frame[o++].toInt() and 0xFF
            if (widLen != 1) null else frame[o].toInt() and 0xFF
        } catch (e: Exception) {
            null
        }
    }

    /** Le genre — appui, relâchement, bascule — lu dans le créneau EVENT. */
    fun kind(frame: ByteArray): Int? {
        if (!isEvent(frame)) return null
        return try {
            var o = 4
            val deviceCount = frame[o++].toInt() and 0xFF
            repeat(deviceCount) {
                val len = frame[o++].toInt() and 0xFF
                o += len
            }
            val widLen = frame[o++].toInt() and 0xFF
            o += widLen
            o += 1 // TYPE
            frame[o].toInt() and 0xFF
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Pourquoi cette trame ne doit pas partir vers cette carte — ou `null`
     * quand elle le doit.
     *
     * Fonction pure, hors du gestionnaire WebSocket, précisément pour que la
     * règle soit testable. Une règle enfouie dans une boucle de socket est une
     * règle que personne ne vérifie.
     *
     * Ce qu'elle protège : une carte qui reçoit un événement sur une adresse
     * qu'aucun bloc n'écoute ne peut rien en dire. Le silence remonterait
     * jusqu'à l'utilisateur, qui chercherait la panne dans son croquis. Ici on
     * sait — alors on le dit.
     *
     * [signals] à `null` laisse tout passer : un nœud sans dépôt de signaux
     * n'a pas à devenir muet pour autant.
     */
    fun refusalFor(
        frame: ByteArray,
        ownerId: String,
        deviceId: String,
        signals: com.jeanloickdt.signal.domain.SignalRepository?
    ): String? {
        val address = address(frame) ?: return null   // pas un EVENT : pas notre affaire
        if (signals == null) return null
        if (signals.find(ownerId, deviceId, address) != null) return null
        return CommandFailedReason.UNDECLARED_ADDRESS
    }

    /**
     * Construit un EVENT — utilisé par les tests, et par tout ce qui doit en
     * fabriquer un côté serveur.
     *
     * [deviceId] non nul place la carte visée dans la liste d'appareils, comme
     * l'app le fait ; le relais la retire avant de transmettre, et la carte
     * reçoit `DEV_COUNT = 0`.
     */
    fun build(address: Int, kind: Int, payload: ByteArray = ByteArray(0), deviceId: String? = null): ByteArray {
        require(address in 0..255) { "address out of range: $address" }
        val device = deviceId?.encodeToByteArray()
        val head = if (device == null) byteArrayOf(0x00)
        else byteArrayOf(0x01, device.size.toByte()) + device
        val body = head + byteArrayOf(
            0x01,
            address.toByte(),
            TYPE_EVENT.toByte(),
            kind.toByte()
        ) + payload
        val crc = crc8(body)
        return byteArrayOf(
            0xAA.toByte(), 0x01,
            (body.size and 0xFF).toByte(),
            ((body.size shr 8) and 0xFF).toByte()
        ) + body + byteArrayOf(crc)
    }

    /** Même polynôme que la lib et que le relais : 0x07. */
    private fun crc8(bytes: ByteArray): Byte {
        var crc = 0
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc.toByte()
    }
}
