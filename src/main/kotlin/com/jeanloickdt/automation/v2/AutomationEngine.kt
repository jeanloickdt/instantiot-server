package com.jeanloickdt.automation.v2

import com.jeanloickdt.automation.DeliveryWorker
import com.jeanloickdt.automation.PendingActionRepository
import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.event.EventSinks
import com.jeanloickdt.event.RelayEvent
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

private val logger = LoggerFactory.getLogger("AutomationEngine")

/**
 * Le moteur v2 — impulsionnel, et rien d'autre qu'évaluer et écrire.
 *
 * ## Impulsionnel, et pourquoi ce n'est pas un détail
 *
 * Chaque événement provoque une ré-évaluation COMPLÈTE.
 *
 * L'alternative — le modèle à niveau avec réarmement, celui du v1 — butait sur
 * une question sans réponse : sur `All(a > 30, b < 40)`, quand la règle
 * redevient-elle armée ? Quand les deux redeviennent fausses ? Quand l'une
 * l'est ? Les deux réponses sont défendables et incompatibles, donc on a
 * supprimé la question.
 *
 * La répétition se règle donc là où elle se décide vraiment : dans le
 * DÉCLENCHEUR. « À chaque trame » tire en permanence, un front tire une fois
 * par franchissement, un horaire tire une fois. Ce choix appartient à celui
 * qui écrit la règle, et il le fait déjà en choisissant son déclencheur.
 *
 * ## Ce que le moteur ne fait plus, et pourquoi
 *
 * Il n'y a plus ni cooldown, ni fusible, ni sourdine. C'étaient des
 * garde-fous contre la configuration de l'utilisateur, et un garde-fou contre
 * l'utilisateur est un aveu de conception : ses règles sont son programme.
 * S'il en écrit une qui déraille, il brûle son quota — c'est son affaire.
 *
 * Le fusible était le pire des trois : il faisait taire une règle d'autorité
 * au moment précis où un capteur s'affole, c'est-à-dire au moment où on veut
 * le plus être prévenu.
 *
 * Les seules bornes qui restent sont les NÔTRES, aux expéditeurs : elles
 * protègent le domaine du signalement pour spam, pas quelqu'un de lui-même.
 *
 * **En échange, on doit la lisibilité.** Les compteurs d'observabilité ne
 * sont plus un confort : la fiche est désormais le seul endroit où comprendre
 * ce qui se passe.
 *
 * ## Le moteur évalue et écrit. Rien d'autre.
 *
 * Aucun appel réseau, aucune I/O bloquante, aucun envoi. Il s'arrête à
 * l'écriture dans `pending_actions`. La livraison appartient au
 * [DeliveryWorker], et cette frontière ne bouge pas — c'est elle qui fait
 * qu'un crash ne perd rien.
 *
 * ## L'ordre canonique, non négociable
 *
 * ```
 * 1.  Événement capté sur le canal
 * 2.  Cache → règles dont le SUJET DU DÉCLENCHEUR est celui-ci
 * 3.  Règle invalide ou désactivée               → abandon
 * 4.  Évaluer la condition (via SignalValueCache)
 * 5.  Résultat ≠ VRAI                            → fin, aucun effet
 * 6.  Marquer lastFiredAt, incrémenter les compteurs du jour
 * 7.  Écrire les actions dans pending_actions, dans l'ordre
 * ```
 *
 * L'ordre reste non négociable même raccourci : la condition avant l'écriture,
 * et l'écriture après le marquage.
 *
 * L'étape 5 ne bouge AUCUN compteur de sécurité — il n'y en a plus —
 * mais elle incrémente les compteurs d'observabilité. Sans eux, la ligne
 * « déclenchée 14 fois · tirée 2 fois · la condition était fausse 12 fois »
 * serait impossible à écrire, et c'est elle qui répond à neuf « pourquoi ça
 * n'a pas marché » sur dix.
 */
class AutomationEngine(
    private val sinks: EventSinks,
    private val cache: RuleCache,
    private val values: SignalValueCache,
    private val actions: PendingActionRepository,
    private val runs: AutomationRuns,
    private val devices: DeviceRepository,
    /**
     * Les attentes en vol, ou `null` pour un moteur qui n'en pose pas.
     *
     * `null` n'est pas « ignorer les attentes » : c'est refuser la sequence
     * entiere a partir de la, et le dire. Executer « allume » sans « eteins »
     * parce qu'on ne sait pas attendre serait pire que ne rien faire.
     */
    private val continuations: ContinuationRepository? = null,
    /** `true` quand la carte est joignable MAINTENANT — voir [Action.SetSignal]. */
    private val isDeviceOnline: (ownerId: String, deviceId: String) -> Boolean = { _, _ -> false },
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val depthRefused = AtomicLong(0)
    val depthRefusedCount: Long get() = depthRefused.get()

    /** La boucle consommatrice — à lancer dans sa propre coroutine. */
    suspend fun run() {
        while (true) {
            // On draine le canal rare et precieux d'abord : `select` est biaise
            // vers sa premiere clause, et sous un deluge de telemetrie la
            // branche des valeurs gagnerait presque toujours — affamant
            // exactement les evenements rares et importants.
            val event: RelayEvent = sinks.next()
            try {
                handle(event)
            } catch (e: Exception) {
                // Une regle cassee ne doit pas tuer les automatisations de tout
                // le monde.
                logger.error("Engine failed on ${event::class.simpleName} — event skipped", e)
            }
        }
    }

    /** Aiguillage pur — public pour les épreuves, qui poussent sans canaux. */
    fun handle(event: RelayEvent) {
        if (event.depth > MAX_DEPTH) {
            depthRefused.incrementAndGet()
            logger.warn("Event at depth ${event.depth} refused — a rule chain is looping")
            return
        }
        val now = clock()

        when (event) {
            is RelayEvent.SignalValue -> {
                val (deviceId, address) = split(event.signalKey) ?: return
                // Le cache des valeurs se remplit AVANT l'evaluation : une
                // condition qui interroge le signal declencheur doit voir la
                // valeur qui vient d'arriver, pas la precedente.
                values.putNumeric(event.ownerId, deviceId, address, event.value)
                onSignal(
                    event.ownerId, deviceId, address,
                    TypedValue.Float(event.value), event.occurredAt, now
                )
            }

            is RelayEvent.SignalText -> {
                val (deviceId, address) = split(event.signalKey) ?: return
                // L'evenement porte les octets bruts : le decodage est ici et
                // pas chez le producteur, parce que seul le consommateur sait
                // s'il en a besoin — la telemetrie textuelle traverse le
                // relais sans jamais etre lue quand aucune regle ne l'attend.
                val text = runCatching {
                    String(java.util.Base64.getDecoder().decode(event.payloadBase64), Charsets.UTF_8)
                }.getOrNull() ?: return
                values.putText(event.ownerId, deviceId, address, text)
                onSignal(
                    event.ownerId, deviceId, address,
                    TypedValue.Text(text), event.occurredAt, now
                )
            }

            is RelayEvent.DeviceOnline -> {
                cache.rulesTriggeredBy(SubjectKey.Device(event.ownerId, event.deviceId))
                    .forEach { rule ->
                        // Le rebond ne tire jamais : une carte qui revient
                        // annule la confirmation en attente, gratuitement.
                        rule.state.pendingOfflineSince = null
                        when (val t = rule.logic?.trigger) {
                            is Trigger.DeviceConnected -> {
                                if (t.afterMs == null) {
                                    evaluate(rule, event.occurredAt, now)
                                } else if (rule.state.pendingOnlineSince == null) {
                                    // JAMAIS d'attente ici : une seule regle
                                    // ne doit pas geler les autres pendant
                                    // cinq minutes. Le balayage confirme.
                                    //
                                    // Et seulement si rien n'attend deja : un
                                    // second DeviceOnline sans deconnexion
                                    // entre les deux repousserait l'echeance a
                                    // l'infini sur une carte bavarde.
                                    rule.state.pendingOnlineSince = event.occurredAt
                                }
                            }
                            else -> Unit
                        }
                    }
            }

            is RelayEvent.DeviceOffline -> {
                cache.rulesTriggeredBy(SubjectKey.Device(event.ownerId, event.deviceId))
                    .forEach { rule ->
                        // LA CARTE N'EST PAS RESTEE. Une attente de « connectee
                        // depuis X » que la carte quitte avant l'echeance
                        // n'aurait plus rien de vrai a annoncer : un
                        // aller-retour de trois secondes ne doit pas finir par
                        // declencher une regle qui dit cinq minutes.
                        rule.state.pendingOnlineSince = null
                        when (val t = rule.logic?.trigger) {
                            is Trigger.DeviceDisconnected -> {
                                if (t.afterMs == null) {
                                    evaluate(rule, event.occurredAt, now)
                                } else if (rule.state.pendingOfflineSince == null) {
                                    // JAMAIS d'attente ici : une seule regle
                                    // hors ligne ne doit pas geler toutes les
                                    // autres pendant trente secondes. Le
                                    // balayage periodique confirme.
                                    rule.state.pendingOfflineSince = event.occurredAt
                                }
                            }
                            else -> Unit
                        }
                    }
            }

            is RelayEvent.TimeReached -> {
                val rule = cache.scheduleRule(event.ruleId) ?: return
                evaluate(rule, event.scheduledFor, now)
            }

            // Le silence n'est pas publie aujourd'hui : rien n'emet « ce signal
            // s'est tu ». `SignalStale` est dans le scelle, sans producteur.
            else -> Unit
        }
    }

    /**
     * Les confirmations de déconnexion.
     *
     * Appelé par le battement périodique, pas par la boucle : « toujours
     * absent après N secondes » est un fait sur du temps écoulé, et le moteur
     * n'attend jamais.
     */
    fun tick(nowMs: Long) {
        cache.index.byId.values.forEach { rule ->
            when (val t = rule.logic?.trigger) {
                is Trigger.DeviceDisconnected -> {
                    val since = rule.state.pendingOfflineSince ?: return@forEach
                    val after = t.afterMs ?: return@forEach
                    if (nowMs - since < after) return@forEach

                    // Toujours en attente = aucun DeviceOnline ne l'a annulee :
                    // la carte est vraiment partie. Le fait a eu lieu quand
                    // elle a DISPARU, pas quand on l'a confirme — sinon une
                    // re-detection apres redemarrage forgerait une nouvelle cle
                    // d'idempotence et l'utilisateur recevrait un push par
                    // battement.
                    rule.state.pendingOfflineSince = null
                    evaluate(rule, factTime = since, nowMs = nowMs)
                }

                is Trigger.DeviceConnected -> {
                    val since = rule.state.pendingOnlineSince ?: return@forEach
                    val after = t.afterMs ?: return@forEach
                    if (nowMs - since < after) return@forEach

                    // Meme regle sur le fait : il a eu lieu quand la carte est
                    // REVENUE, pas quand on l'a confirme. « Connectee depuis
                    // cinq minutes » date de la reconnexion, et c'est cette
                    // date que la fiche affichera.
                    rule.state.pendingOnlineSince = null
                    evaluate(rule, factTime = since, nowMs = nowMs)
                }

                else -> Unit
            }
        }
    }

    // ── L'ordre canonique ────────────────────────────────────────────────

    private fun onSignal(
        ownerId: String, deviceId: String, address: Int,
        value: TypedValue, factTime: Long, nowMs: Long
    ) {
        val rules = cache.rulesTriggeredBy(SubjectKey.Signal(ownerId, deviceId, address))
        for (rule in rules) {
            val trigger = rule.logic?.trigger
            val previous = rule.state.lastValue
            // On note la valeur precedente MEME si la regle n'est pas
            // evaluable : sinon une regle reactivee comparerait la valeur
            // d'avant sa desactivation, et tirerait sur un front qui n'a
            // jamais eu lieu.
            rule.state.lastValue = value

            when (trigger) {
                is Trigger.SignalChanged -> evaluate(rule, factTime, nowMs, value)

                is Trigger.SignalTransition -> {
                    // Le FRONT, pas le niveau : c'est ce qui evite deux cents
                    // notifications pour un bouton maintenu enfonce.
                    //
                    // Un front est un predicat qui passe de FAUX a VRAI.
                    // « devient 30 » et « depasse 30 » sont le meme mecanisme
                    // avec deux predicats — d'ou l'operateur, la ou seule
                    // l'egalite existait.
                    val reachesTarget = holds(value, trigger.op, trigger.to)
                    val wasAlreadyThere = previous != null && holds(previous, trigger.op, trigger.to)
                    val comesFromExpected = trigger.from == null ||
                        (previous != null && sameValue(previous, trigger.from))
                    if (reachesTarget && !wasAlreadyThere && comesFromExpected) {
                        evaluate(rule, factTime, nowMs, value)
                    }
                }

                else -> Unit
            }
        }
    }

    /**
     * Les étapes 3 à 9 pour une règle.
     *
     * [factTime] est l'heure du FAIT, jamais celle de la détection : c'est
     * elle qui compose la clé d'idempotence, et une re-détection ne doit pas
     * en forger une nouvelle.
     */
    private fun evaluate(
        rule: LoadedRule, factTime: Long, nowMs: Long,
        /**
         * La valeur QUI A DECLENCHE, quand il y en a une.
         *
         * `null` pour un horaire ou une presence : il n'y a rien a rendre, et
         * inventer un zero serait la coercition que la politique refuse.
         */
        triggerValue: TypedValue? = null
    ) {
        // ── 3. invalide ou desactivee ────────────────────────────────────
        if (!rule.evaluable()) return
        val logic = rule.logic ?: return

        rule.state.rollDayIfNeeded(nowMs, rule.zone)
        rule.state.evaluations++

        // ── 4. la condition ──────────────────────────────────────────────
        val ctx = object : EvalContext {
            override fun valueOf(ref: SignalRef) = values.valueOf(rule.ownerId, ref)
            override fun isOnline(ref: DeviceRef): Boolean? =
                if (devices.findById(rule.ownerId, ref.deviceId) == null) null
                else isDeviceOnline(rule.ownerId, ref.deviceId)
            override fun nowMs() = nowMs
        }
        val truth = ConditionEval.evaluate(logic.condition, ctx, rule.zone)

        // ── 5. pas VRAI : fin, aucun compteur de securite ─────────────────
        if (truth != Truth.TRUE) {
            // FAUX et INDETERMINE menent au meme abandon, mais restent
            // distincts dans la trace : « la condition etait fausse » et
            // « valeur absente » ne disent pas la meme chose a quelqu'un qui
            // cherche pourquoi sa serre s'est tue.
            if (truth == Truth.FALSE) rule.state.conditionFalse++ else rule.state.conditionUnknown++
            noteSkip(rule, nowMs, if (truth == Truth.FALSE) "Condition was false" else "No value for a referenced signal")
            return
        }

        // ── 6. on marque ─────────────────────────────────────────────────
        //
        // Plus rien entre la condition VRAIE et l'ecriture. Le cooldown et le
        // fusible tenaient ici, et ils decidaient a la place de celui qui a
        // ecrit la regle. La repetition se choisit au DECLENCHEUR.
        rule.state.lastFiredAt = factTime
        rule.state.fired++

        // ── 7. on ecrit, dans l'ordre ────────────────────────────────────
        fire(rule, logic, factTime, nowMs, RunOutcome.FIRED, triggerValue)
    }

    /**
     * Écrit les actions dans l'outbox, dans l'ordre — jusqu'à la première
     * attente.
     *
     * L'ordre de la liste est l'ordre d'écriture. Ce n'est pas l'ordre de
     * remise — deux canaux différents ne se synchronisent pas — mais une
     * action n'est jamais écrite avant celle qui la précède.
     *
     * ## Le découpage en segments
     *
     * Tout ce qui précède la première attente part TOUT DE SUITE. L'attente
     * pose une échéance et le moteur rend la main. Ce qui suit reprendra au
     * réveil, dans l'ordre, par le même chemin — donc deux attentes font trois
     * segments, sans que rien ici ne compte les segments.
     */
    private fun fire(
        rule: LoadedRule, logic: RuleLogic,
        factTime: Long, nowMs: Long, outcome: RunOutcome,
        triggerValue: TypedValue? = null
    ) {
        val queued = runSegment(
            rule, logic.actions, offset = 0, factTime = factTime, nowMs = nowMs,
            outcome = outcome, triggerValue = triggerValue
        )
        transaction { cache.save(rule.id, rule.state, nowMs) }
        runs.record(rule.ownerId, rule.id, nowMs, outcome, queued.reason)
        logger.info("Rule ${outcome.name.lowercase()} — id=${rule.id} owner=${rule.ownerId}")
    }

    /** Ce qu'un segment a donné, pour la ligne de trace. */
    private class Segment(val reason: String)

    /**
     * Un SEGMENT : les actions jusqu'à la prochaine attente, puis l'échéance.
     *
     * Le même code sert le premier segment et tous les suivants. Deux chemins
     * — un pour le tir, un pour la reprise — auraient divergé au premier
     * correctif, et c'est exactement le genre de divergence qu'on ne remarque
     * qu'en production.
     *
     * [offset] est l'index de `actions[0]` dans la liste D'ORIGINE. La clé
     * d'idempotence le porte : repartir de zéro à chaque segment ferait entrer
     * en collision la première action de chacun.
     */
    private fun runSegment(
        rule: LoadedRule, actions: List<Action>, offset: Int,
        factTime: Long, nowMs: Long, outcome: RunOutcome,
        triggerValue: TypedValue?
    ): Segment {
        val skipped = mutableListOf<String>()
        var written = 0
        var waited: String? = null

        transaction {
            for ((i, action) in actions.withIndex()) {
                if (action is Action.Wait) {
                    waited = poseWait(
                        rule, action, actions.drop(i + 1), offset + i + 1,
                        factTime, nowMs, triggerValue
                    )
                    return@transaction
                }
                val payload = payloadOf(rule, action, skipped, triggerValue) ?: continue
                val enqueued = this@AutomationEngine.actions.enqueue(
                    // La cle derive de l'heure du FAIT. Une re-detection
                    // (redemarrage en plein lot, evenement rejoue, reprise
                    // rejouee) frapperait l'index unique au lieu d'envoyer un
                    // second push.
                    idempotencyKey = "${rule.id}:${outcome.name}:$factTime:${offset + i}",
                    ownerId = rule.ownerId,
                    ruleId = rule.id,
                    type = wireType(action),
                    payload = payload,
                    // L'heure du FAIT, jamais celle de l'envoi : une commande
                    // perimee ne doit pas se faire passer pour fraiche.
                    occurredAt = factTime,
                    nowMs = nowMs,
                    severity = rule.severity
                )
                if (enqueued) written++
                else logger.debug("Duplicate fire deduped — rule=${rule.id} fact=$factTime")
            }
        }

        return Segment(
            waited ?: skipped.firstOrNull() ?: "$written action(s) queued"
        )
    }

    /**
     * Pose l'échéance, ou dit pourquoi on ne l'a pas posée.
     *
     * Rend le texte de la trace : c'est la seule chose que l'appelant a besoin
     * de savoir, et ça évite de lui rendre un état qu'il devrait interpréter.
     */
    private fun poseWait(
        rule: LoadedRule, wait: Action.Wait, rest: List<Action>, restOffset: Int,
        factTime: Long, nowMs: Long, triggerValue: TypedValue?
    ): String {
        if (rest.isEmpty()) {
            // Attendre puis ne rien faire ne fait rien. La validation refuse
            // deja d'ecrire ca ; ceci est le filet pour une definition venue
            // d'ailleurs.
            return "Nothing follows the wait — sequence ends here"
        }
        val repo = continuations
            ?: return "This server does not run waits — the rest of the sequence was dropped"

        val due = dueAtOf(wait.delay, nowMs, rule.zone)
        val posed = repo.schedule(
            ownerId = rule.ownerId, ruleId = rule.id, dueAt = due,
            remaining = rest, offset = restOffset, factTime = factTime,
            triggerValue = triggerValue, nowMs = nowMs
        )
        if (!posed) {
            // On REFUSE la sequence entiere plutot que d'en executer la
            // moitie : « allume » sans « eteins » laisse le chauffage allume,
            // et personne ne saurait pourquoi.
            logger.warn("Rule ${rule.id}: too many waits in flight for ${rule.ownerId} — sequence dropped")
            return "Too many waits in flight for this account — the rest was not scheduled"
        }
        return "Waiting until $due, then ${rest.size} more action(s)"
    }

    /**
     * Le réveil : reprendre une séquence là où elle s'était arrêtée.
     *
     * Rien n'est relu depuis la règle — ni ses actions, ni sa condition. Les
     * actions viennent de la ligne, parce qu'une règle modifiée pendant
     * l'attente ne doit pas changer ce qui était déjà décidé ; et la condition
     * a été évaluée au tir, une fois, ce qui est le seul moment où elle
     * décrivait le monde qui a déclenché la règle.
     *
     * Ce qui est relu, c'est l'INTERRUPTEUR : une règle éteinte ou devenue
     * invalide pendant l'attente ne reprend pas. Éteindre une règle doit
     * l'arrêter, y compris ce qui était déjà en vol — sinon « éteins-la » ne
     * veut plus rien dire.
     */
    fun resume(c: Continuation): Boolean {
        val rule = cache.byId(c.ruleId)
        if (rule == null || !rule.evaluable()) {
            logger.info("Continuation ${c.id} dropped — rule ${c.ruleId} is gone, off or invalid")
            return false
        }
        val nowMs = clock()
        val segment = runSegment(
            rule, c.remaining, offset = c.offset, factTime = c.factTime,
            nowMs = nowMs, outcome = RunOutcome.FIRED, triggerValue = c.triggerValue
        )
        runs.record(rule.ownerId, rule.id, nowMs, RunOutcome.FIRED, "Resumed — ${segment.reason}")
        return true
    }

    /**
     * « Run actions now » — un ESSAI, pas un tir.
     *
     * Saute le déclencheur et la condition, exécute les actions. Ne touche pas
     * `lastFiredAt` : un essai n'est pas un tir, et la trace doit pouvoir les
     * distinguer six heures plus tard.
     *
     * Refusé sur une règle INVALIDE — on ne teste pas ce qui ne peut pas
     * tourner. Autorisé sur une règle désactivée : c'est même l'usage
     * principal, on vérifie avant d'armer.
     */
    fun runNow(ruleId: String): TestRun {
        val rule = cache.byId(ruleId) ?: return TestRun.NOT_FOUND
        // On ne teste pas ce qui ne peut pas tourner : le bouton dirait
        // « lance » sur une regle que le moteur n'evaluera jamais.
        if (rule.invalidReason != null) return TestRun.INVALID
        val logic = rule.logic ?: return TestRun.INVALID

        val now = clock()
        // Un appel toutes les dix secondes par regle.
        //
        // Cette borne-ci RESTE, et ce n'est pas une contradiction : elle borne
        // un BOUTON, pas une regle. Le declencheur d'une regle est le
        // programme de l'utilisateur ; un bouton qu'on peut marteler est un
        // chemin d'ecriture sans limite dans l'outbox, c'est-a-dire notre
        // ressource, pas la sienne.
        val last = lastTestAt[ruleId]
        if (last != null && now - last < TEST_MIN_INTERVAL_MS) return TestRun.TOO_SOON
        lastTestAt[ruleId] = now

        fire(rule, logic, factTime = now, nowMs = now, outcome = RunOutcome.TESTED)
        return TestRun.OK
    }

    /**
     * Ce qu'un essai a donne.
     *
     * `DESACTIVEE` n'y figure pas, et c'est voulu : verifier une regle AVANT
     * de l'armer est l'usage principal du bouton.
     */
    enum class TestRun { OK, NOT_FOUND, INVALID, TOO_SOON }

    /**
     * Le dernier essai de chaque regle — RAM seule.
     *
     * Un redemarrage rouvre la fenetre. Accepte : la borne protege d'un doigt
     * qui reste appuye, pas d'un adversaire, et persister une date par regle
     * pour une seconde de tolerance apres reboot serait une colonne de trop.
     */
    private val lastTestAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ── Traduction vers l'outbox ─────────────────────────────────────────

    private fun wireType(action: Action): String = when (action) {
        is Action.Push -> DeliveryWorker.TYPE_PUSH
        is Action.Email -> DeliveryWorker.TYPE_EMAIL
        is Action.SetSignal -> DeliveryWorker.TYPE_COMMAND
        // Ni `Webhook` ni `StartRule` n'ont de canal : ils sont dans le scelle
        // pour que le format ne bouge plus, pas pour etre livres aujourd'hui.
        else -> DeliveryWorker.TYPE_PUSH
    }

    /** `null` = l'action est abandonnée, et [skipped] dit pourquoi. */
    private fun payloadOf(
        rule: LoadedRule, action: Action, skipped: MutableList<String>,
        triggerValue: TypedValue?
    ): String? = when (action) {
        is Action.Push -> buildJsonObject {
            put("title", render(action.title, triggerValue))
            put("body", render(action.body, triggerValue))
        }.toString()

        is Action.Email -> buildJsonObject {
            put("subject", render(action.subject, triggerValue))
            put("body", render(action.body, triggerValue))
            // L'expediteur lisait deja `to` et retombait sur l'adresse du
            // compte quand il manquait. Il ne manquait que de quoi l'ecrire.
            action.to?.let { put("to", it) }
        }.toString()

        is Action.SetSignal -> {
            val device = devices.findById(rule.ownerId, action.target.deviceId)
            when {
                // Jamais la carte d'un autre locataire. Le meme reflexe que
                // partout : un identifiant ne se croit pas sans son
                // proprietaire.
                device == null -> {
                    skipped += "Target device is not yours"
                    logger.error(
                        "Rule ${rule.id}: SetSignal targets device ${action.target.deviceId} " +
                            "not owned by ${rule.ownerId} — REFUSED"
                    )
                    null
                }
                // Une commande est « au plus une fois ». Une vanne qui s'ouvre
                // quand la carte se reconnecte deux heures plus tard est
                // exactement le scenario qu'on refuse : on ABANDONNE.
                !isDeviceOnline(rule.ownerId, action.target.deviceId) -> {
                    skipped += "Device was offline"
                    logger.info("Rule ${rule.id}: SetSignal dropped — device offline")
                    null
                }
                else -> commandPayload(action.target, action.value)
            }
        }

        is Action.Webhook, is Action.StartRule -> {
            skipped += "${action::class.simpleName} has no delivery channel yet"
            null
        }

        // Une attente n'a pas de charge utile : elle ne part JAMAIS dans
        // l'outbox. Elle est traitee bien avant, par le decoupage en segments
        // — si elle arrivait ici, c'est que ce decoupage a un trou.
        is Action.Wait -> {
            logger.error("Rule ${rule.id}: a Wait reached the outbox — the segment split has a hole")
            null
        }
    }

    // ── Petites choses ───────────────────────────────────────────────────

    /**
     * `{{value}}` devient la valeur qui a déclenché la règle.
     *
     * Le seul gabarit du système, et il reste volontairement seul : une
     * notification qui dit « la température a atteint 31.2 » vaut dix fois
     * celle qui dit « seuil franchi », et c'est le motif que la v1 avait déjà.
     *
     * Sans valeur déclenchante — un horaire, une carte qui se connecte — le
     * gabarit reste TEL QUEL dans le texte. Le remplacer par `0` ou par du
     * vide fabriquerait une mesure qui n'a pas eu lieu ; le laisser visible
     * dit à l'utilisateur qu'il l'a écrit là où rien ne peut le remplir.
     */
    private fun render(text: String, value: TypedValue?): String {
        if (value == null || TEMPLATE_VALUE !in text) return text
        val rendered = when (value) {
            is TypedValue.Int -> value.value.toString()
            // Un entier reste un entier a l'affichage : « 31.0 °C » se lit
            // moins bien que « 31 », et le fil rend tout numerique en double.
            // Rendu comme un FLOAT32, parce que c'est ce que le fil porte.
            //
            // Une trame numerique transporte quatre octets. Elargis en
            // `Double`, ils portent l'ecart de la representation :
            // `37.4f` devient `37.400001525878906`, et c'est ce que
            // l'utilisateur lisait dans sa notification — vu en production le
            // 9 septembre 2026, « Current value: 37.379913330078125 ».
            //
            // `toFloat().toString()` rend la plus COURTE chaine qui revient
            // exactement au meme float32, donc « 37.4 ». Ce n'est pas un
            // arrondi d'affichage : c'est la valeur telle que la carte l'a
            // envoyee, sans les chiffres que l'elargissement a inventes.
            is TypedValue.Float ->
                if (value.value % 1.0 == 0.0) value.value.toLong().toString()
                else value.value.toFloat().toString()
            is TypedValue.Text -> value.value
        }
        return text.replace(TEMPLATE_VALUE, rendered)
    }

    /** `"deviceId:adresse"` → la paire, ou `null` si la clé est malformée. */
    private fun split(signalKey: String): Pair<String, Int>? {
        val i = signalKey.lastIndexOf(':')
        if (i <= 0) return null
        val address = signalKey.substring(i + 1).toIntOrNull() ?: return null
        return signalKey.substring(0, i) to address
    }

    /**
     * Deux valeurs sont-elles la même ?
     *
     * Les numériques se comparent après promotion — `Int(1)` et `Float(1.0)`
     * sont la même valeur, et une transition déclarée sur `1` doit reconnaître
     * le `1.0` que le fil rend.
     */
    /**
     * Le prédicat du front est-il vrai pour cette valeur ?
     *
     * Les quatre opérateurs d'ORDRE exigent du numérique : `"9" > "10"` est
     * vrai en lexicographique et faux pour n'importe qui. Sur du texte ils
     * rendent `false` plutôt que de comparer des lettres — la validation les
     * refuse déjà à l'écriture, ceci est le filet.
     */
    private fun holds(value: TypedValue, op: Op, target: TypedValue): Boolean {
        if (op == Op.EQ) return sameValue(value, target)
        if (op == Op.NEQ) return !sameValue(value, target)
        val x = value.asDouble ?: return false
        val y = target.asDouble ?: return false
        return when (op) {
            Op.GT -> x > y
            Op.GTE -> x >= y
            Op.LT -> x < y
            Op.LTE -> x <= y
            Op.EQ, Op.NEQ -> false   // traites plus haut
        }
    }

    private fun sameValue(a: TypedValue, b: TypedValue): Boolean {
        val x = a.asDouble; val y = b.asDouble
        if (x != null && y != null) return x == y
        return a is TypedValue.Text && b is TypedValue.Text && a.value == b.value
    }

    /**
     * Un passage sans tir n'est trace et persiste qu'une fois par periode et
     * par regle. Chaque evaluation a FAUX ecrivait trois requetes (la trace
     * et l'etat) : une regle « a chaque trame » dont la condition est fausse,
     * c'est dix trames par seconde et quarante requetes par seconde, pour
     * une ligne qui repete « la condition etait fausse ». Un tir, lui,
     * persiste toujours tout de suite.
     */
    private val skipNotedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun noteSkip(rule: LoadedRule, nowMs: Long, reason: String) {
        val last = skipNotedAt[rule.id] ?: 0L
        if (nowMs - last < SKIP_NOTE_PERIOD_MS) return
        skipNotedAt[rule.id] = nowMs
        runs.record(rule.ownerId, rule.id, nowMs, RunOutcome.SKIPPED, reason)
        cache.save(rule.id, rule.state, nowMs)
    }

    companion object {
        /** Un passage sans tir n'est trace qu'une fois par periode, par regle. */
        const val SKIP_NOTE_PERIOD_MS = 5_000L

        /** Le seul gabarit du systeme — voir `render`. */
        const val TEMPLATE_VALUE = "{{value}}"

        /** Un essai toutes les dix secondes par regle. */
        const val TEST_MIN_INTERVAL_MS = 10_000L

        /** Au-delà, une chaîne de règles se nourrit elle-même — refuser et compter. */
        const val MAX_DEPTH = 3

    }
}

/**
 * La charge d'une commande, telle qu'elle voyage jusqu'a l'expediteur.
 *
 * Extraite pour UNE raison : elle est un CONTRAT entre deux moities qui ne
 * se voient pas. Le moteur ecrivait ici, l'expediteur lisait `payloadB64` --
 * un champ que personne n'a jamais ecrit -- et abandonnait chaque commande
 * avec « empty command frame ». Les deux etaient testes, chacun avec sa
 * propre idee de la forme.
 *
 * Tant que les deux cotes passent par cette fonction, ils ne peuvent plus
 * diverger en silence.
 */
fun commandPayload(target: SignalRef, value: TypedValue): String = buildJsonObject {
    put("deviceId", target.deviceId)
    put("address", target.address)
    when (value) {
        is TypedValue.Int -> put("value", value.value)
        is TypedValue.Float -> put("value", value.value)
        is TypedValue.Text -> put("value", value.value)
    }
    put("type", value.typeName)
}.toString()
