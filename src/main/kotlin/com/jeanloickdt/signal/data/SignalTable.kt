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

import org.jetbrains.exposed.sql.Table

/**
 * A **signal** — the value contract between a board and everything that reads
 * it. See `PROTOCOLE-2.0.md`.
 *
 * ## The key is `id` — a global integer, not (owner, device, address)
 *
 * The address space `I0..I255` is still enumerated **per board**, so each
 * sketch is written without knowing what the other boards use — that part is
 * unchanged, and it is why the wire still carries one byte and no board
 * identity.
 *
 * But the STORAGE key is `id`, not the triplet. Every row of history — five
 * tables, potentially billions of rows — points at `signals.id`. A composite
 * key of two strings and an int would have meant carrying that weight on
 * every one of them; an integer carries it once.
 *
 * `(device_id, address)` remains a **uniqueness constraint** — a board may
 * not declare `I5` twice — but `owner_id` does not belong in it. A device
 * belongs to exactly one account (`devices.owner_id`), so `device_id` alone
 * already scopes the address space to one tenant; repeating `owner_id` here
 * would be redundant, not protective.
 *
 * ## The cost of losing the free ownership check
 *
 * The composite key used to make cross-tenant reads impossible **by
 * construction**: `find(ownerId, deviceId, address)` could not resolve a row
 * that was not the caller's, because `ownerId` was literally part of what
 * identified the row. An integer id has no such property — `id = 84172`
 * resolves to whoever owns it, and worse, sequential ids are guessable in a
 * way the old string keys never were.
 *
 * **The repository never exposes `findById(id: Long)` alone.** Every lookup
 * by id is `findById(ownerId: String, id: Long)` — the compiler enforces
 * what the composite key used to enforce for free. See
 * [SignalRepository.findById].
 *
 * ## What lives here and not on a widget
 *
 * Unit, min/max, type: several widgets share one signal, so anything that must
 * look the same to all of them belongs here. Decimals, labels and colors stay on
 * the widget — they are drawing, not data.
 *
 * ## What deliberately does NOT live here
 *
 * **No send rate.** It is not a per-signal setting: the only throttle is the
 * node's ceiling — the same number that already feeds the fuse, pushed to the
 * board so it stops before being disconnected. Where that number comes from is
 * the operator's business, not the signal's.
 *
 * **No history tier.** [historised] is a boolean. The minute/hour/day cascade is
 * internal and automatic. Whether the raw tier is kept is decided elsewhere,
 * by whoever runs the node.
 */
object SignalTable : Table("signals") {

    /**
     * The universal foreign key. `BIGINT`, not `INT` — the target of every
     * history row this account will ever write, and a column resized after
     * the fact means rewriting the history that already points at it.
     */
    val id = long("id").autoIncrement()

    val ownerId  = text("owner_id")
    val deviceId = text("device_id")
    /** `0..255` — what travels on the wire, one byte. Rendered `I0`..`I255`. */
    val address  = integer("address")

    /** The human name. Never on the wire: "Température serre". */
    val label    = text("label")

    /** `bool` · `int` · `float` · `string` — validation and decoding. */
    val type     = text("type")
    val unit     = text("unit").default("")
    /** Default decimals; a widget may show more or fewer. */
    val decimals = integer("decimals").default(1)

    /** Bounds are a CLAMP, not a rejection — a sensor spike must not vanish. */
    val minValue = double("min_value").nullable()
    val maxValue = double("max_value").nullable()

    /**
     * Do we keep a trace of this signal?
     *
     * `true` feeds the minute/hour/day cascade — measured at 275 bytes per
     * minute row, so **~35,6 MB per signal per 90 days**, of which the minute
     * tier is 98%. `false` keeps only [lastPayload], which costs nothing.
     *
     * There is no tier to pick: the cascade is internal, and `raw` is granted
     * by the plan.
     */
    /** `value` ou `action`. Défaut `value` : ce qui existait le reste. */
    val nature = varchar("nature", 16).default(NATURE_VALUE)

    val historised = bool("historised").default(true)

    /**
     * La carte retrouve-t-elle cette valeur en se reconnectant ?
     *
     * **C'est l'interrupteur de sûreté du modèle.** Une consigne doit revenir —
     * la pompe était à 19 °C avant la coupure, elle doit y retourner. Une
     * action ne doit jamais revenir : rejouer « ouvre le portail » à chaque
     * hoquet du WiFi n'est pas un défaut d'affichage.
     *
     * Par défaut `true`, ce qui préserve le comportement d'avant ce champ :
     * toute consigne ayant une valeur était rejouée, sans qu'on demande à
     * personne. Le passer à `false` est une décision, pas un oubli.
     */
    val replayOnConnect = bool("replay_on_connect").default(true)

    /**
     * Ce signal se propose-t-il dans l'éditeur de règles ?
     *
     * **C'est un filtre de liste, pas une permission.** Une installation
     * mûre déclare des dizaines de signaux dont la plupart ne déclencheront
     * jamais rien : un compteur d'uptime, une tension d'alimentation, un
     * code de version. Les faire défiler à chaque règle écrite est un coût
     * qui ne s'amortit pas.
     *
     * Ce que ce champ ne fait pas : protéger. Décrocher un signal déjà
     * utilisé par une règle **ne casse pas la règle** — elle continue de
     * tourner. Le champ décide de ce qu'on propose, pas de ce qui a le
     * droit d'exister. Refuser l'écriture d'une règle est le travail des
     * droits, et il se fait ailleurs.
     *
     * Par défaut `true` : avant ce champ tout signal était éligible, et une
     * base existante ne doit pas voir ses signaux disparaître de l'éditeur
     * parce qu'on a ajouté une colonne.
     */
    val automationVisible = bool("automation_visible").default(true)

    /** `measure` (board writes) · `setpoint` (app writes) · `both`. */
    val direction = text("direction").default(DIRECTION_MEASURE)

    /** Last value seen, base64 payload — written by the relay only. */
    val lastPayload = text("last_payload").nullable()
    /** Timestamp of the last frame from the board — written by the relay only. */
    val lastSeenAt  = long("last_seen_at").nullable()

    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        // Un device ne redéclare pas deux fois la même adresse. `owner_id`
        // n'y figure pas : un device appartient à un seul compte
        // (`devices.owner_id`), donc `device_id` seul scope déjà l'espace
        // d'adresses à un tenant — le répéter ici serait redondant, pas
        // protecteur.
        uniqueIndex(deviceId, address)
        // `listByOwner` filtre sur cette colonne à chaque appel ; sans
        // index, un compte à mille signaux force un scan de la table.
        index(isUnique = false, ownerId)
    }

    const val DIRECTION_MEASURE  = "measure"
    const val DIRECTION_SETPOINT = "setpoint"
    const val DIRECTION_BOTH     = "both"

    /**
     * Ce qu'une adresse porte — et ce n'est pas déductible du reste.
     *
     * Une **action** n'a ni sens de lecture (elle va toujours de l'app vers la
     * carte), ni historique (il n'y a pas de courbe), ni bornes, ni rejeu
     * (rien n'est gardé). Ce n'est donc pas une valeur avec d'autres réglages,
     * c'est une autre déclaration — d'où une colonne plutôt qu'une déduction.
     */
    const val NATURE_VALUE  = "value"
    const val NATURE_ACTION = "action"

    /**
     * Le type `bool` ne se déclare plus — il reste ici pour LIRE ce qui a été
     * écrit avant, et pour traduire une app plus ancienne.
     *
     * Il ne restreignait aucune liaison : un interrupteur, une LED et une
     * jauge acceptaient déjà tout le numérique. Il ne faisait qu'une chose,
     * aplatir — et la convention des gestes (1 appui, 0 relâchement, 2 appui
     * long) y perdait son 2. Un appui long arrivait comme un appui court.
     *
     * Une adresse à deux états est un entier qui vaut 0 ou 1.
     */
    const val TYPE_BOOL   = "bool"
    const val TYPE_INT    = "int"
    const val TYPE_FLOAT  = "float"
    const val TYPE_STRING = "string"

    /** The address space, per board — one byte on the wire. */
    const val ADDRESS_MIN = 0
    const val ADDRESS_MAX = 255

    /** `5` → `"I5"`. The wire never carries this form; humans and sketches do. */
    fun render(address: Int): String = "I$address"
}
