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

package com.jeanloickdt.automation.data

import org.jetbrains.exposed.sql.Table

/**
 * The durable half of notifications & automations — the tables BEFORE the
 * engine, deliberately: [PendingActionTable] is the contract the whole
 * subsystem hangs from, and writing the engine first would mean writing it
 * against a semantics that does not exist yet, then rewriting it at the first
 * lost alert.
 *
 * All schema is additive (new tables only), so the boot migration is the
 * usual `createMissingTablesAndColumns`.
 */

/**
 * One rule = one row. `definition` is JSON the ENGINE owns — thresholds,
 * hysteresis, schedule, the actions to produce. The relational columns are
 * exactly the ones something else queries: the rule cache loads by
 * `(owner_id, trigger_signal_key)`, the REST API scopes by `owner_id`, the
 * scheduler filters by `trigger_kind`. Modelling the rest as columns would
 * freeze the rule vocabulary into the schema and cost a migration per new
 * operator — the engine's vocabulary must stay the engine's.
 */
object AutomationRuleTable : Table("automation_rules") {
    val id        = text("id")
    val ownerId   = text("owner_id")
    val name      = text("name")
    val enabled   = bool("enabled").default(true)

    /** `value` | `presence` | `stale` | `schedule` | `system` — which events feed it. */
    val triggerKind = text("trigger_kind")

    /**
     * La cle du signal surveille — `"deviceId:adresse"`, la meme que partout
     * ailleurs. Null pour une regle qui ne surveille aucun signal : un
     * horaire, la presence d'une carte.
     *
     * La colonne s'appelait `trigger_widget_id`. Le renommage se faisait au
     * demarrage du fabricant SQLite, disparu avec lui : la colonne porte son
     * nom actuel dans Postgres depuis la bascule.
     */
    val triggerSignalKey = text("trigger_signal_key").nullable()

    /** Engine-owned JSON: condition, hysteresis, cooldown, actions. */
    val definition = text("definition")

    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    // ── Le modele 2.0 ────────────────────────────────────────────────────
    //
    // Portees du nuage avec le moteur v2. `createMissingTablesAndColumns` les
    // ajoute a une base existante ; chacune a un defaut, donc les lignes
    // ecrites avant ne deviennent pas invalides.

    /** `info` | `warning` | `critical` — la gravite telle que la regle la declare. */
    val severity = text("severity").default("info")

    /** Le projet dont la regle est originaire. Nul pour une regle qui n'en vise aucun. */
    val homeProjectId = text("home_project_id").nullable()

    /**
     * Le fuseau de la REGLE, et pas celui du serveur.
     *
     * Une plage horaire « entre 22 h et 6 h » n'a de sens que dans un fuseau
     * nomme. Le lire de l'horloge de la machine ferait changer la regle quand
     * la machine voyage.
     */
    val timeZoneId = text("time_zone_id").default("UTC")

    /**
     * Le motif d'invalidite, en clair. Non nul, la regle ne s'evalue jamais.
     *
     * `signal-deleted`, `device-deleted`, `type-mismatch`,
     * `signal-off-for-automation`, `unknown-variant:<type>`. Les quatre
     * premiers sont reparables : la regle redevient active d'elle-meme quand
     * la cause disparait.
     */
    val invalidReason = text("invalid_reason").nullable()

    /** La version du langage de la definition. `v2` depuis le portage. */
    val schemaVersion = text("schema_version").default("v2")

    /** Ce que l'app dessine sur la carte de la regle. */
    val icon = text("icon").nullable()
    val color = text("color").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * The armed/triggered memory of each rule, PERSISTED — without it a nightly
 * restart re-arms the whole fleet and replays yesterday's alerts at dawn.
 * Separate from the rule row because it changes at data cadence while the
 * rule changes at human cadence: one hot row, one cold row.
 */
object AutomationStateTable : Table("automation_state") {
    val ruleId      = text("rule_id")
    val triggered   = bool("triggered").default(false)
    val lastFiredAt = long("last_fired_at").nullable()
    /** The value that armed/fired last — hysteresis needs the previous reading. */
    val lastValue   = double("last_value").nullable()
    val updatedAt   = long("updated_at")

    // ── Le modele 2.0 ────────────────────────────────────────────────────

    /**
     * La valeur precedente, TYPEE, pour le declencheur de transition.
     *
     * `last_value` la portait en `double` : un signal texte n'y entrait pas,
     * et un entier y perdait son type au retour. Le JSON porte les deux.
     */
    val lastValueJson = text("last_value_json").nullable()

    // Les compteurs du JOUR. Ils n'empechent rien — ils EXPLIQUENT. C'est la
    // contrepartie du retrait du cooldown et du fusible : on ne protege plus
    // l'utilisateur de lui-meme, donc on lui doit de comprendre ce qui passe.
    // Cout : zero ligne, contre 86 400 par jour pour un capteur a 1 Hz.

    /** Minuit LOCAL, dans le fuseau de la regle — pas minuit UTC. */
    val dayStartedAt = long("day_started_at").nullable()

    val evaluations      = integer("evaluations").default(0)
    val fired            = integer("fired").default(0)
    val conditionFalse   = integer("condition_false").default(0)
    val conditionUnknown = integer("condition_unknown").default(0)

    override val primaryKey = PrimaryKey(ruleId)
}

/**
 * ═══ THE DURABILITY FRONTIER ═══
 *
 * Everything before this table is best-effort RAM; everything after is
 * replayable. The boundary is one INSERT: after that line, a crash loses
 * nothing.
 *
 * `idempotency_key` carries the exactly-once-ish semantics and its UNIQUE
 * index is **the guarantee, not an optimisation**: the engine may evaluate
 * the same event twice (restart mid-batch), and the second INSERT must die on
 * the constraint rather than send the owner two pushes.
 *
 * The lease (`leased_until`) is what lets a crashed worker's rows be picked
 * up again: a worker takes a 5-minute lease, delivers, marks. If it dies
 * in between, the lease expires and another pass retries — which is exactly
 * why delivery semantics are per type (PUSH/EMAIL at-least-once, COMMAND
 * at-most-once; the worker enforces that, étape 4).
 */
object PendingActionTable : Table("pending_actions") {
    /** La gravite heritee de la regle, pour trier le fil des alertes. */
    val severity = text("severity").default("info")

    /**
     * Pourquoi l'action n'est PAS partie, quand un plafond l'a refusee.
     *
     * Un refus qui ne se dit pas ressemble a une panne. La colonne le nomme,
     * et le fil des alertes peut l'afficher au lieu d'un silence.
     */
    val refusedReason = text("refused_reason").nullable()

    val id             = integer("id").autoIncrement()
    val idempotencyKey = text("idempotency_key")
    val ownerId        = text("owner_id")
    val ruleId         = text("rule_id").nullable()

    /** `PUSH` | `EMAIL` | `COMMAND` — decides the delivery semantics. */
    val type = text("type")

    /** Channel-owned JSON: push title/body, email fields, command frame. */
    val payload = text("payload")

    /** `PENDING` | `SENT` | `DEAD`. */
    val status = text("status").default("PENDING")

    val attempts      = integer("attempts").default(0)
    val nextAttemptAt = long("next_attempt_at")
    val leasedUntil   = long("leased_until").nullable()

    /** When the CAUSE happened — kept through retries, shown to the user. */
    val occurredAt = long("occurred_at")
    val createdAt  = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * The materialised "next fire" of schedule rules. The spec (cron-like, IANA
 * timezone) lives in the rule's `definition`; this row is only the answer to
 * "what is due?", so the scheduler's poll is an indexed range scan instead of
 * parsing every schedule every ten seconds. `next_run_at` is UTC epoch ms —
 * the timezone already did its work when the value was computed.
 */
object ScheduledJobTable : Table("scheduled_jobs") {
    val ruleId    = text("rule_id")
    val nextRunAt = long("next_run_at")
    /** IANA zone of the rule — re-materialisation crosses DST with it. */
    val timezone  = text("timezone")
    override val primaryKey = PrimaryKey(ruleId)
}

/**
 * Where a push lands: FCM tokens, one row per app install. Registered by the
 * app (`POST /api/push-tokens`, étape 6 côté app), consumed by the delivery
 * worker. A token FCM declares dead is deleted on the spot — a growing pile
 * of dead tokens is the classic silent push-rot.
 */
object PushTokenTable : Table("push_tokens") {
    val token     = text("token")
    val ownerId   = text("owner_id")
    /** `android` | `ios` — APNs-via-FCM needs to know. */
    val platform  = text("platform")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(token)
}

/**
 * The `messages.perMonth` flow counter (étape 0b). One row per account per
 * month — RAM-accumulated on the hot path, drained by the 5 s flush, so a
 * frame never costs a DB write. `period` is `"2026-08"`: the reset is a new
 * key, not an UPDATE that could race.
 */
object MessageUsageTable : Table("message_usage") {
    val ownerId = text("owner_id")
    val period  = text("period")
    val count   = long("count").default(0)
    override val primaryKey = PrimaryKey(ownerId, period)
}

object AutomationTables {
    /** Registered together everywhere the schema is built. */
    val ALL = arrayOf<Table>(
        AutomationRuleTable, AutomationStateTable, PendingActionTable,
        ScheduledJobTable, PushTokenTable, MessageUsageTable,
        // Le modele 2.0 : l'historique des passages et les attentes en vol.
        AutomationRunTable, RuleContinuationTable
    )
}

/**
 * Les vingt derniers passages de chaque regle — ce qui alimente « LAST RUNS »
 * sur la fiche.
 *
 * TAMPON CIRCULAIRE, borne par construction : a chaque insertion on supprime
 * au-dela de la vingtieme ligne de cette regle. Vingt lignes par regle,
 * definitivement.
 *
 * C'est la moitie visible de la revision ② ; l'autre est les compteurs du jour
 * dans [AutomationStateTable], qui ne coutent aucune ligne. Les deux ensemble
 * remplacent une trace exhaustive que le disque n'aurait pas supportee.
 *
 * `owner_id` est la comme partout ailleurs : scoping des lectures, et purge
 * d'un compte en une requete.
 */
object AutomationRunTable : Table("automation_runs") {
    val id      = integer("id").autoIncrement()
    val ownerId = text("owner_id")
    val ruleId  = text("rule_id")
    val at      = long("at")

    /**
     * `FIRED` | `SKIPPED` | `TESTED`, et `MUTED` en HISTORIQUE.
     *
     * Plus rien n'ecrit `MUTED` depuis le retrait du fusible, mais des lignes
     * d'avant le portent : une valeur qu'on cesse d'ecrire n'est pas une
     * valeur qu'on peut cesser de lire.
     *
     * `TESTED` existe pour qu'on ne confonde jamais un essai avec un vrai tir
     * en relisant la trace : le bouton « Run actions now » saute le
     * declencheur et la condition, et ne consomme aucun compteur de securite.
     */
    val outcome = text("outcome")

    /**
     * Un texte court, rendu TEL QUEL a l'ecran — « Condition was false — 27 °C »,
     * « Device was offline ».
     *
     * Rendu tel quel et non traduit : la phrase est construite au moment du
     * fait, avec la valeur du moment, et la reconstruire six heures plus tard
     * demanderait de stocker cette valeur quelque part. Le prix est une trace
     * en anglais ; le gain est qu'elle dit exactement ce qui s'est passe.
     */
    val reason  = text("reason").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Une séquence d'actions EN VOL, arrêtée sur une attente.
 *
 * ## Pourquoi une table, et pas `scheduled_jobs`
 *
 * `scheduled_jobs` a `rule_id` pour clé primaire : une ligne par règle, ce que
 * le déclencheur horaire exige. Une règle peut avoir N attentes en vol à la
 * fois — deux déclenchements qui se chevauchent font deux séquences
 * indépendantes. Les mettre là écraserait l'une par l'autre, et casserait
 * l'horaire par la même occasion.
 *
 * ## Ce que la ligne porte, et pourquoi elle le porte
 *
 * **Les actions restantes, en JSON, PAS un index dans la règle.** Une règle
 * modifiée pendant qu'une attente est en vol reprendrait sinon dans une liste
 * différente : « allume, attends, éteins » deviendrait « allume, attends,
 * envoie un courriel » sans que personne l'ait demandé. Une séquence en vol
 * finit comme elle a été écrite. C'est la même leçon que `severity` copiée au
 * moment du tir.
 *
 * **L'heure du FAIT**, jamais celle du réveil : c'est elle qui compose la clé
 * d'idempotence. Sans elle, un réveil rejoué après un redémarrage forgerait
 * une nouvelle clé et enverrait une seconde fois.
 *
 * **Le décalage** de la première action restante dans la liste d'origine. La
 * clé d'idempotence porte l'index de l'action ; repartir de zéro à chaque
 * segment ferait entrer en collision le premier de chaque segment.
 *
 * **La valeur déclenchante**, pour que `{{value}}` dise encore quelque chose
 * après l'attente. « La température a atteint 31.2 » vaut dix fois « seuil
 * franchi », et elle n'existe plus nulle part une heure plus tard.
 */
object RuleContinuationTable : Table("rule_continuations") {
    val id      = integer("id").autoIncrement()
    /** Comme partout : scoping des lectures, et purge d'un compte en une requête. */
    val ownerId = text("owner_id")
    val ruleId  = text("rule_id")

    /** Quand reprendre. C'est ce que la boucle interroge. */
    val dueAt   = long("due_at")

    /** Les actions restantes, encodées par `RuleCodec`. Voir plus haut. */
    val remaining = text("remaining")

    /** L'index, dans la liste d'origine, de la première action restante. */
    val offset  = integer("offset_index")

    /** L'instant du FAIT qui a démarré la séquence — la clé d'idempotence. */
    val factTime = long("fact_time")

    /** La valeur qui a déclenché, encodée — `null` pour un horaire. */
    val triggerValueJson = text("trigger_value_json").nullable()

    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}
