package com.jeanloickdt.automation

import com.jeanloickdt.automation.data.AutomationRuleTable
import com.jeanloickdt.automation.data.AutomationStateTable
import com.jeanloickdt.automation.data.ScheduledJobTable
import com.jeanloickdt.automation.v2.Action
import com.jeanloickdt.automation.v2.AutomationEngine
import com.jeanloickdt.automation.v2.AutomationRuns
import com.jeanloickdt.automation.v2.RuleCache
import com.jeanloickdt.automation.v2.RuleCodec
import com.jeanloickdt.automation.v2.RuleLogic
import com.jeanloickdt.automation.v2.RuleValidation
import com.jeanloickdt.automation.v2.Trigger
import com.jeanloickdt.automation.v2.allSignalRefs
import com.jeanloickdt.automation.v2.selfTriggeringSignals
import com.jeanloickdt.common.ApiError
import com.jeanloickdt.common.Appearance
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable
import com.jeanloickdt.automation.data.PendingActionTable
import com.jeanloickdt.automation.data.RuleContinuationTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Une règle, telle que l'app la lit.
 *
 * Tout ce que la liste affiche — nom, interrupteur, état, motif — vient de
 * COLONNES. C'est ce que la révision ① achète : l'écran de liste rend une
 * règle sans parser quoi que ce soit, et une app qui ne sait pas décoder la
 * définition peut quand même la montrer et l'éteindre.
 */
@Serializable
data class RuleResponse(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val severity: String,
    val timeZoneId: String,

    /**
     * L'APPARENCE de la carte : une icone et une couleur, par leurs CLES.
     *
     * Absentes tant que rien n'a ete choisi. Le serveur ne connait ni le
     * catalogue ni la palette — voir [Appearance].
     */
    val icon: String? = null,
    val color: String? = null,

    /**
     * TOUJOURS `0` — le cooldown n'existe plus.
     *
     * Le champ survit une version parce qu'il est REQUIS dans le DTO de
     * l'app : le retirer maintenant rendrait toute réponse indécodable pour
     * une app pas encore mise à jour, et la règle « le serveur se déploie
     * avant l'app » garantit que cette fenêtre existe. `0` dit la vérité —
     * aucun intervalle minimum entre deux tirs.
     *
     * À retirer d'ici, du contrat et de l'app, une fois l'app déployée.
     */
    val cooldownMs: Long = 0,
    val homeProjectId: String? = null,
    val schemaVersion: String,

    /**
     * Les trois blocs, en JSON, avec les libellés REPOSÉS.
     *
     * Retirés à l'écriture, réhydratés ici depuis la jointure vivante : l'app
     * a de quoi écrire sa phrase même hors ligne, sans qu'un libellé périmé
     * survive jamais en base.
     */
    val definition: String,

    /**
     * `ACTIVE` | `DISABLED` | `INVALID`.
     *
     * DÉRIVÉ, jamais stocké : deux vérités — celle du calcul et celle d'une
     * colonne — divergeraient au premier oubli de mise à jour.
     *
     * `MUTED` est parti avec le fusible. Les trois qui restent ont une cause
     * que l'utilisateur peut nommer ; un quatrième que le serveur posait tout
     * seul laissait quelqu'un devant une règle éteinte qu'il n'avait pas
     * éteinte.
     */
    val state: String,
    val invalidReason: String? = null,
    /**
     * TOUJOURS absents — plus rien ne met une règle en sourdine.
     *
     * Nullables et à défaut dans le DTO de l'app, donc les omettre ne casse
     * aucune version : une app pas encore mise à jour n'affiche simplement
     * plus jamais le badge. À retirer du contrat avec `cooldownMs`.
     */
    val mutedUntil: Long? = null,
    val muteReason: String? = null,
    val lastFiredAt: Long? = null,

    /** Les compteurs du JOUR — ce que « TODAY » affiche sur la fiche. */
    val todayEvaluations: Int = 0,
    val todayFired: Int = 0,
    val todayConditionFalse: Int = 0,
    val todayConditionUnknown: Int = 0,

    /**
     * Les envois qu'un plafond de sortie a refusés aujourd'hui.
     *
     * Il ne vient PAS des mêmes compteurs que les autres : ceux-là comptent
     * des évaluations, celui-ci compte des lignes de `pending_actions`. Une
     * règle peut avoir tiré et n'avoir rien envoyé — c'est même exactement le
     * cas qu'il faut pouvoir lire.
     *
     * Sans lui, une règle qui tire cent fois et n'envoie rien affiche « tirée
     * 100 fois » et l'utilisateur cherche la panne dans sa règle, là où elle
     * n'est pas. C'est la contrepartie du retrait du cooldown et du fusible :
     * on ne protège plus l'utilisateur de lui-même, donc on lui doit de
     * pouvoir comprendre.
     */
    val todayRefused: Int = 0,

    val createdAtMs: Long
)

/**
 * Un passage de règle, tel que « LAST RUNS » l'affiche.
 *
 * [reason] est rendu TEL QUEL, sans traduction : la phrase est construite au
 * moment du fait, avec la valeur du moment — « Condition was false — 27 °C ».
 * La reconstruire six heures plus tard demanderait de stocker cette valeur
 * quelque part. Le prix est une trace en anglais ; le gain est qu'elle dit
 * exactement ce qui s'est passé.
 */
@Serializable
data class RuleRunResponse(
    val id: Int,
    val atMs: Long,
    /** `FIRED` | `SKIPPED` | `TESTED`, et `MUTED` en historique. */
    val outcome: String,
    val reason: String? = null
)

/**
 * Une page de passages.
 *
 * [nextBefore] est le curseur de la suivante, ou `null` quand il n'y a plus
 * rien. Il ne descendra jamais loin — le tampon fait vingt lignes — mais la
 * forme ne changera pas le jour où on en garderait plus.
 */
@Serializable
data class RuleRunPage(
    val runs: List<RuleRunResponse>,
    val nextBefore: Int? = null
)

/**
 * Ce qu'on envoie pour REMPLACER la définition d'une règle.
 *
 * Séparé de `UpdateRuleRequest` et servi par une route à part, pas ajouté au
 * `PATCH` : le `PATCH` est la soupape qui laisse éteindre une règle qu'on ne
 * sait pas lire, et lui donner le pouvoir d'écrire la définition retirerait
 * la garantie qui fait tout son intérêt. Les deux gestes n'ont ni les mêmes
 * validations, ni les mêmes effets, ni le même risque.
 */
@Serializable
data class ReplaceDefinitionRequest(
    /** Les trois blocs, comme à la création. */
    val definition: String,
    /**
     * La version de schéma que l'appelant a LUE.
     *
     * Elle est comparée à celle en base : si une autre version a réécrit la
     * règle entre la lecture et l'envoi, on refuse plutôt que d'écraser. Le
     * jour où une v3 existera, c'est ce champ qui empêchera une app v2 de
     * réenregistrer une règle dont elle n'a compris que la moitié.
     */
    val schemaVersion: String? = null,
    /** L'app confirme avoir montré l'avertissement de boucle. Voir la création. */
    val acknowledgeLoop: Boolean = false
)

@Serializable
data class CreateRuleRequest(
    val name: String,
    /** Les trois blocs — déclencheur, condition, actions. Et rien d'autre. */
    val definition: String,
    val homeProjectId: String? = null,
    /** IANA. Un seul par règle : une feuille qui en porte un est refusée. */
    val timeZoneId: String = "UTC",
    /**
     * ACCEPTÉ ET IGNORÉ. Le cooldown n'existe plus.
     *
     * Refuser aurait été plus propre et plus cassant : une app pas encore
     * mise à jour envoie encore ce champ, et un `400` l'empêcherait de créer
     * la moindre règle pendant toute la fenêtre entre les deux déploiements.
     * Ignorer ne coûte rien à personne.
     *
     * À retirer du contrat une fois l'app déployée.
     */
    val cooldownMs: Long? = null,
    val severity: String = "info",
    val enabled: Boolean = true,
    /**
     * L'apparence, choisie dans le meme formulaire que le reste.
     *
     * Elle voyage avec la regle plutot que par un geste a part, contrairement
     * a celle d'un projet : une regle se cree dans un editeur ou tout est
     * deja sous la main, alors qu'un projet se cree d'un nom et se decore
     * plus tard, depuis sa carte.
     */
    val icon: String? = null,
    val color: String? = null,
    /**
     * L'app confirme avoir montré l'avertissement de boucle.
     *
     * On AVERTIT, on ne bloque pas : un asservissement volontaire est un usage
     * légitime. Mais l'avertissement doit être vu, donc le serveur refuse une
     * première fois et dit quoi renvoyer.
     */
    val acknowledgeLoop: Boolean = false
)

/**
 * Ce qu'on peut changer sans toucher à la définition.
 *
 * `definition` n'y figure PAS : l'édition d'une règle existante n'est pas dans
 * le MVP. L'interrupteur, si — et il n'est pas négociable. Sans lui, faire
 * taire une alerte ce soir oblige à supprimer la règle, ce qui réinitialise
 * `automation_state`, ce qui fait tirer immédiatement une règle recréée alors
 * que le seuil est déjà franchi.
 *
 * C'est aussi la soupape de sécurité du §5 : `enabled` étant une colonne, ce
 * `PATCH` ne peut effacer aucune branche d'une définition qu'on n'aurait pas
 * comprise.
 */
@Serializable
data class UpdateRuleRequest(
    val name: String? = null,
    val enabled: Boolean? = null,
    val severity: String? = null,
    /** ACCEPTÉ ET IGNORÉ — voir [CreateRuleRequest.cooldownMs]. */
    val cooldownMs: Long? = null,
    val timeZoneId: String? = null,
    /**
     * L'apparence. `null` = « ne touche pas », comme les autres champs de ce
     * PATCH ; une chaine vide = « retire ».
     *
     * La distinction compte : sans elle, un PATCH qui ne parle que du nom
     * effacerait l'icone au passage.
     */
    val icon: String? = null,
    val color: String? = null,
    /** ACCEPTÉ ET IGNORÉ : plus rien ne met une règle en sourdine. */
    val unmute: Boolean = false
)

/**
 * Les règles de décision, injectées plutôt que codées en dur.
 *
 * [allowedActionTypes] est la frontière de l'OFFRE. Un type d'action sans
 * expéditeur enfilerait des lignes que le livreur ne peut que marquer DEAD —
 * et une alerte qui meurt en silence est pire que pas d'alerte du tout.
 *
 * Le défaut EXCLUT `PUSH`, et c'est le point : il demande un projet Firebase
 * et une route d'enregistrement de jetons, donc il ne s'active QUE si
 * l'appelant le dit. Un défaut permissif se paie toujours du même côté.
 */
class RulePolicies(
    val allowedActionTypes: Set<String> = setOf(
        DeliveryWorker.TYPE_EMAIL, DeliveryWorker.TYPE_COMMAND
    ),
    val quotaGate: suspend (call: ApplicationCall, ownerId: String, isAutomation: Boolean, current: () -> Int) -> Boolean =
        { _, _, _, _ -> true }
)

/**
 * Le CRUD des règles, scopé au propriétaire, avec [RuleCache.reload] comme
 * unique point de couplage au moteur : chaque mutation finit par un
 * rechargement, et le portail `watches()` des producteurs bascule dans le même
 * instant.
 */
fun Route.ruleRoutes(
    cache: RuleCache,
    resolver: RuleValidation.Resolver,
    runs: AutomationRuns,
    policies: RulePolicies = RulePolicies(),
    /**
     * Le moteur, pour « Run actions now ».
     *
     * `null` = le bouton rend 503. Un noeud sans moteur cable — un test de
     * route, un deploiement partiel — doit le DIRE, pas repondre 200 sur une
     * action qui n'est jamais partie.
     */
    engine: AutomationEngine? = null
) {
    authenticate("jwt") {

        get("/api/rules") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(HttpStatusCode.OK, listRules(ownerId, cache, resolver))
        }

        post("/api/rules") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val body = call.receive<CreateRuleRequest>()

            val name = body.name.trim()
            if (name.length !in 1..64) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("Name must be 1-64 characters"))
            }
            if (!Appearance.isKey(body.icon) || !Appearance.isKey(body.color)) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError(Appearance.BAD_REQUEST))
            }
            val zone = runCatching { ZoneId.of(body.timeZoneId) }.getOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("Unknown timezone '${body.timeZoneId}' — expected an IANA id")
                )
            // ── La forme ──────────────────────────────────────────────────
            val logic = when (val decoded = RuleCodec.decode(body.definition)) {
                is RuleCodec.Outcome.Invalid ->
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("${decoded.code}: ${decoded.detail}")
                    )
                is RuleCodec.Outcome.Ok -> decoded.logic
            }

            // ── Le sens, contre l'inventaire ──────────────────────────────
            RuleValidation.check(logic, ownerId, resolver)?.let {
                // `signal-deleted` et `device-deleted` rendent 404 : confirmer
                // l'existence d'une reference serait un bit de l'inventaire de
                // quelqu'un d'autre.
                val status = when (it.code) {
                    RuleValidation.E_SIGNAL_DELETED, RuleValidation.E_DEVICE_DELETED ->
                        HttpStatusCode.NotFound
                    else -> HttpStatusCode.BadRequest
                }
                return@post call.respond(status, ApiError("${it.code}: ${it.detail}"))
            }

            // ── Ce qu'on sait livrer ──────────────────────────────────────
            undeliverable(logic, policies)?.let {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError(it))
            }

            // ── La boucle : on avertit, on ne bloque pas ──────────────────
            val loops = logic.selfTriggeringSignals()
            if (loops.isNotEmpty() && !body.acknowledgeLoop) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    ApiError(
                        "self-triggering: this rule writes ${loops.joinToString()} which it also " +
                            "watches — it can trigger itself. Resend with acknowledgeLoop=true to proceed."
                    )
                )
            }

            val isAutomation = logic.actions.any { it is Action.SetSignal }
            val allowed = policies.quotaGate(call, ownerId, isAutomation) {
                countRules(ownerId, automation = isAutomation)
            }
            if (!allowed) return@post   // la porte a repondu (402)

            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            transaction {
                AutomationRuleTable.insert {
                    it[AutomationRuleTable.id] = id
                    it[AutomationRuleTable.ownerId] = ownerId
                    it[AutomationRuleTable.name] = name
                    it[enabled] = body.enabled
                    it[triggerKind] = kindOf(logic.trigger)
                    it[triggerSignalKey] = triggerKeyOf(logic.trigger)
                    // On persiste la forme SANS libelles : un libelle perime en
                    // base est un libelle qui ment pour toujours.
                    it[definition] = RuleCodec.encode(RuleCodec.stripLabels(logic))
                    it[AutomationRuleTable.severity] = normalizeSeverity(body.severity)
                    it[homeProjectId] = body.homeProjectId
                    it[timeZoneId] = zone.id
                    it[schemaVersion] = RuleCodec.SCHEMA_VERSION
                    it[AutomationRuleTable.icon] = Appearance.key(body.icon)
                    it[AutomationRuleTable.color] = Appearance.key(body.color)
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            cache.reload()   // le portail des producteurs bascule ici
            materializeSchedule(id, logic.trigger, zone, body.enabled)
            call.respond(
                HttpStatusCode.Created,
                listRules(ownerId, cache, resolver).first { it.id == id }
            )
        }

        patch("/api/rules/{id}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@patch call.respond(HttpStatusCode.Unauthorized)
            val ruleId = call.parameters["id"]
                ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiError("Missing rule id"))
            val body = call.receive<UpdateRuleRequest>()

            // L'appartenance d'abord, motif 404 — ne jamais reveler la regle
            // d'un autre locataire.
            val row = findRule(ownerId, ruleId)
                ?: return@patch call.respond(HttpStatusCode.NotFound, ApiError("Rule not found"))

            val zone = body.timeZoneId?.let {
                runCatching { ZoneId.of(it) }.getOrNull()
                    ?: return@patch call.respond(
                        HttpStatusCode.BadRequest, ApiError("Unknown timezone '$it'")
                    )
            }
            body.name?.let {
                if (it.trim().length !in 1..64) return@patch call.respond(
                    HttpStatusCode.BadRequest, ApiError("Name must be 1-64 characters")
                )
            }
            if (!Appearance.isKey(body.icon) || !Appearance.isKey(body.color)) {
                return@patch call.respond(HttpStatusCode.BadRequest, ApiError(Appearance.BAD_REQUEST))
            }

            val now = System.currentTimeMillis()
            transaction {
                AutomationRuleTable.update({
                    (AutomationRuleTable.id eq ruleId) and (AutomationRuleTable.ownerId eq ownerId)
                }) {
                    body.name?.let { n -> it[name] = n.trim() }
                    body.enabled?.let { e -> it[enabled] = e }
                    body.severity?.let { sv -> it[severity] = normalizeSeverity(sv) }
                    zone?.let { z -> it[timeZoneId] = z.id }
                    // `null` ne touche a rien, une chaine vide retire. Sans
                    // cette distinction, un PATCH qui ne parle que du nom
                    // effacerait l'icone au passage.
                    body.icon?.let { k -> it[icon] = Appearance.key(k) }
                    body.color?.let { k -> it[color] = Appearance.key(k) }
                    it[updatedAt] = now
                }
            }
            cache.reload()

            // Le prochain tir se recalcule depuis la ligne COURANTE : le fuseau
            // et l'interrupteur ont pu changer tous les deux.
            (RuleCodec.decode(row.definition) as? RuleCodec.Outcome.Ok)?.logic?.let { logic ->
                val effectiveZone = zone
                    ?: runCatching { ZoneId.of(row.timeZoneId) }.getOrDefault(ZoneId.of("UTC"))
                materializeSchedule(ruleId, logic.trigger, effectiveZone, body.enabled ?: row.enabled)
            }
            call.respond(
                HttpStatusCode.OK,
                listRules(ownerId, cache, resolver).first { it.id == ruleId }
            )
        }

        /**
         * REMPLACE la definition d'une regle.
         *
         * ## Pourquoi une route a part
         *
         * `PATCH /api/rules/{id}` ne touche JAMAIS la definition, et c'est ce
         * qui permet a une app qui ne sait pas decoder une regle de l'eteindre
         * sans en effacer les branches qu'elle n'a pas comprises. Ajouter
         * `definition` a ce corps aurait retire cette garantie a tous les
         * appels, y compris ceux qui ne voulaient qu'eteindre.
         *
         * Ici, au contraire, remplacer est le geste demande. Les validations
         * sont donc EXACTEMENT celles de la creation : meme codec, meme
         * verification contre l'inventaire, meme refus des actions qu'on ne
         * sait pas livrer, meme avertissement de boucle. Une regle modifiee ne
         * doit pas pouvoir devenir ce qu'une regle neuve n'aurait pas eu le
         * droit d'etre.
         *
         * ## Ce que le remplacement change en plus
         *
         * La SOURDINE se leve. On vient de reecrire la regle qui s'emballait :
         * lui laisser sa sourdine ferait attendre une heure pour verifier une
         * correction qu'on vient de faire.
         *
         * L'ECHEANCE se recalcule. Passer de « tous les jours a 7 h » a « les
         * lundis a 18 h » sans toucher `scheduled_jobs` laisserait le serveur
         * tirer a 7 h le lendemain, sur une regle qui ne le demande plus.
         *
         * Les COMPTEURS DU JOUR restent. Ils disent ce qui s'est passe
         * aujourd'hui, ce qui reste vrai, et ils se remettent a zero seuls.
         */
        put("/api/rules/{id}/definition") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@put call.respond(HttpStatusCode.Unauthorized)
            val ruleId = call.parameters["id"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, ApiError("Missing rule id"))
            val body = call.receive<ReplaceDefinitionRequest>()

            // L'appartenance d'abord, motif 404 — ne jamais reveler la regle
            // d'un autre locataire.
            val row = findRule(ownerId, ruleId)
                ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("Rule not found"))

            // Ce que l'appelant croyait remplacer. Un ecart signale qu'une
            // autre version a reecrit la regle entre sa lecture et son envoi.
            if (body.schemaVersion != null && body.schemaVersion != row.schemaVersion) {
                return@put call.respond(
                    HttpStatusCode.Conflict,
                    ApiError(
                        "schema-changed: this rule is stored as '${row.schemaVersion}' and you " +
                            "read it as '${body.schemaVersion}' — reload it before replacing."
                    )
                )
            }

            // ── La forme ──────────────────────────────────────────────────
            val logic = when (val decoded = RuleCodec.decode(body.definition)) {
                is RuleCodec.Outcome.Invalid ->
                    return@put call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("${decoded.code}: ${decoded.detail}")
                    )
                is RuleCodec.Outcome.Ok -> decoded.logic
            }

            // ── Le sens, contre l'inventaire ──────────────────────────────
            RuleValidation.check(logic, ownerId, resolver)?.let {
                val status = when (it.code) {
                    RuleValidation.E_SIGNAL_DELETED, RuleValidation.E_DEVICE_DELETED ->
                        HttpStatusCode.NotFound
                    else -> HttpStatusCode.BadRequest
                }
                return@put call.respond(status, ApiError("${it.code}: ${it.detail}"))
            }

            // ── Ce qu'on sait livrer ──────────────────────────────────────
            undeliverable(logic, policies)?.let {
                return@put call.respond(HttpStatusCode.BadRequest, ApiError(it))
            }

            // ── La boucle : on avertit, on ne bloque pas ──────────────────
            val loops = logic.selfTriggeringSignals()
            if (loops.isNotEmpty() && !body.acknowledgeLoop) {
                return@put call.respond(
                    HttpStatusCode.Conflict,
                    ApiError(
                        "self-triggering: this rule writes ${loops.joinToString()} which it also " +
                            "watches — it can trigger itself. Resend with acknowledgeLoop=true to proceed."
                    )
                )
            }

            // ── Le quota, quand la NATURE de la regle change ──────────────
            //
            // Une regle qui n'ecrivait rien et se met a piloter une carte
            // franchit une porte de quota qu'elle n'avait pas franchie. Sans ce
            // controle, creer une regle de notification puis la reecrire en
            // automatisation contournerait la limite en deux appels.
            val wasAutomation = (RuleCodec.decode(row.definition) as? RuleCodec.Outcome.Ok)
                ?.logic?.actions?.any { it is Action.SetSignal } == true
            val isAutomation = logic.actions.any { it is Action.SetSignal }
            if (isAutomation && !wasAutomation) {
                val allowed = policies.quotaGate(call, ownerId, true) {
                    countRules(ownerId, automation = true)
                }
                if (!allowed) return@put   // la porte a repondu (402)
            }

            val zone = runCatching { ZoneId.of(row.timeZoneId) }.getOrDefault(ZoneId.of("UTC"))
            val now = System.currentTimeMillis()
            transaction {
                AutomationRuleTable.update({
                    (AutomationRuleTable.id eq ruleId) and (AutomationRuleTable.ownerId eq ownerId)
                }) {
                    it[triggerKind] = kindOf(logic.trigger)
                    it[triggerSignalKey] = triggerKeyOf(logic.trigger)
                    // Sans libelles, comme a la creation : un libelle perime en
                    // base est un libelle qui ment pour toujours.
                    it[definition] = RuleCodec.encode(RuleCodec.stripLabels(logic))
                    it[schemaVersion] = RuleCodec.SCHEMA_VERSION
                    it[updatedAt] = now
                }
                // Les attentes en vol portent l'ANCIENNE sequence. La laisser
                // finir executerait une version que l'utilisateur vient de
                // remplacer, et il n'aurait aucun moyen de comprendre pourquoi
                // sa carte fait ce qu'elle fait.
                RuleContinuationTable.deleteWhere { RuleContinuationTable.ruleId eq ruleId }
            }
            cache.reload()
            materializeSchedule(ruleId, logic.trigger, zone, row.enabled)
            call.respond(
                HttpStatusCode.OK,
                listRules(ownerId, cache, resolver).first { it.id == ruleId }
            )
        }

        /**
         * « LAST RUNS » — les vingt derniers passages de la regle.
         *
         * Vingt, parce que la table est un tampon circulaire purge a
         * l'insertion. Au-dela, la question n'est plus « qu'est-ce qui s'est
         * passe » mais « quelle est la tendance », et ce sont les compteurs du
         * jour de `GET /api/rules` qui y repondent — eux ne coutent aucune
         * ligne.
         */
        get("/api/rules/{id}/runs") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val ruleId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Missing rule id"))

            // L'appartenance d'abord : sans elle, un identifiant devine
            // rendrait la trace de quelqu'un d'autre.
            findRule(ownerId, ruleId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Rule not found"))

            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                ?: AutomationRuns.KEEP_PER_RULE
            val before = call.request.queryParameters["before"]?.toIntOrNull()

            val page = runs.list(ownerId, ruleId, limit, before)
            call.respond(HttpStatusCode.OK, RuleRunPage(
                runs = page.map {
                    RuleRunResponse(it.id, it.atMs, it.outcome.name, it.reason)
                },
                // Une page pleine SUGGERE une suite ; une page courte prouve
                // qu'il n'y en a pas. Rendre un curseur dans le second cas
                // ferait faire un aller-retour pour apprendre qu'il n'y a
                // rien.
                nextBefore = if (page.size >= limit) page.lastOrNull()?.id else null
            ))
        }

        /**
         * « Run actions now » — un ESSAI, pas un tir.
         *
         * Saute le declencheur et la condition, execute les actions. Ne touche
         * pas `lastFiredAt` : un essai n'est pas un tir, et la trace doit
         * pouvoir les distinguer six heures plus tard.
         *
         * Ecrit tout de meme une ligne `TESTED` dans la trace, pour qu'on ne
         * confonde jamais un essai avec un vrai tir en la relisant.
         */
        post("/api/rules/{id}/run") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val ruleId = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Missing rule id"))

            findRule(ownerId, ruleId)
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("Rule not found"))

            if (engine == null) {
                return@post call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiError("No engine on this node — actions would not be delivered")
                )
            }

            when (engine.runNow(ruleId)) {
                AutomationEngine.TestRun.OK ->
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Actions queued", "id" to ruleId))

                // Le cache ne connait pas cette regle alors que la table si :
                // elle vient d'etre creee et le rechargement n'a pas eu lieu,
                // ou elle est indechiffrable. Dans les deux cas on ne teste
                // pas ce que le moteur n'evaluera pas.
                AutomationEngine.TestRun.NOT_FOUND, AutomationEngine.TestRun.INVALID ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        ApiError("This rule cannot run — fix what makes it invalid first")
                    )

                AutomationEngine.TestRun.TOO_SOON ->
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ApiError("One test every ${AutomationEngine.TEST_MIN_INTERVAL_MS / 1000}s per rule")
                    )
            }
        }

        /**
         * Supprimer PLUSIEURS regles.
         *
         * La meme forme et le meme raisonnement que le fil des notifications :
         * l'ecran fait selectionner puis supprimer, et vingt lignes cochees
         * feraient vingt appels dont certains echoueraient — il faudrait alors
         * decider quoi montrer d'une suppression a moitie faite.
         *
         * Une regle inconnue ou appartenant a quelqu'un d'autre est IGNOREE,
         * pas refusee : le lot vient d'un ecran qui peut avoir vieilli d'une
         * seconde, et faire echouer dix-neuf suppressions legitimes parce
         * qu'une vingtieme n'existe plus serait absurde. Le compte rendu dit
         * combien sont parties.
         */
        delete("/api/rules") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)

            val body = runCatching { call.receive<DeleteRulesRequest>() }.getOrNull()
                ?: return@delete call.respond(
                    HttpStatusCode.BadRequest, ApiError("corps illisible")
                )
            if (body.ids.isEmpty()) {
                return@delete call.respond(HttpStatusCode.BadRequest, ApiError("aucune regle a supprimer"))
            }
            if (body.ids.size > MAX_DELETE_RULES) {
                return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("au plus $MAX_DELETE_RULES regles par appel")
                )
            }

            // Les identifiants qui sont VRAIMENT a ce compte. Filtrer d'abord
            // evite d'effacer l'etat ou les attentes d'une regle qu'on n'a
            // finalement pas le droit de supprimer.
            val mine = transaction {
                AutomationRuleTable.selectAll()
                    .where {
                        (AutomationRuleTable.ownerId eq ownerId) and
                            (AutomationRuleTable.id inList body.ids)
                    }
                    .map { it[AutomationRuleTable.id] }
            }
            if (mine.isNotEmpty()) {
                transaction {
                    AutomationStateTable.deleteWhere { AutomationStateTable.ruleId inList mine }
                    ScheduledJobTable.deleteWhere { ScheduledJobTable.ruleId inList mine }
                    RuleContinuationTable.deleteWhere { RuleContinuationTable.ruleId inList mine }
                    // Les envois PAS ENCORE PARTIS, pour la meme raison qu'a
                    // l'unite : supprimer une regle doit la faire taire.
                    PendingActionTable.deleteWhere {
                        (PendingActionTable.ruleId inList mine) and
                            (PendingActionTable.status eq PendingAction.PENDING)
                    }
                    AutomationRuleTable.deleteWhere { AutomationRuleTable.id inList mine }
                }
                mine.forEach { runs.deleteForRule(it) }
                cache.reload()
            }
            call.respond(HttpStatusCode.OK, mapOf("deleted" to mine.size))
        }

        delete("/api/rules/{id}") {
            val ownerId = call.principal<JWTPrincipal>()?.subject
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)
            val ruleId = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError("Missing rule id"))

            findRule(ownerId, ruleId)
                ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("Rule not found"))

            transaction {
                AutomationStateTable.deleteWhere { AutomationStateTable.ruleId eq ruleId }
                ScheduledJobTable.deleteWhere { ScheduledJobTable.ruleId eq ruleId }
                // Les attentes en vol partent avec la regle. Sans ca, un
                // « eteins le chauffage » se reveillerait une heure apres la
                // suppression de la regle qui l'avait pose.
                RuleContinuationTable.deleteWhere { RuleContinuationTable.ruleId eq ruleId }
                // Et les envois PAS ENCORE PARTIS.
                //
                // Sans ca, supprimer une regle ne la fait pas taire : le
                // livreur expedie ensuite ce qu'elle avait deja enfile, et
                // l'utilisateur recoit une alerte d'une regle qu'il vient de
                // supprimer — apres avoir confirme. C'est le seul cas ou
                // l'application contredit un geste explicite.
                //
                // `PENDING` SEULEMENT. Ce qui est parti reste : le fil des
                // notifications est un historique, et effacer le passe d'une
                // regle supprimee ferait disparaitre l'alerte qu'on a
                // reellement recue hier.
                PendingActionTable.deleteWhere {
                    (PendingActionTable.ruleId eq ruleId) and
                        (PendingActionTable.status eq PendingAction.PENDING)
                }
                AutomationRuleTable.deleteWhere {
                    (AutomationRuleTable.id eq ruleId) and (AutomationRuleTable.ownerId eq ownerId)
                }
            }
            runs.deleteForRule(ruleId)
            cache.reload()
            call.respond(HttpStatusCode.OK, mapOf("message" to "Rule deleted", "id" to ruleId))
        }
    }
}

// ── Ce qu'on sait livrer ──────────────────────────────────────────────────

/**
 * `null` quand toutes les actions ont un canal ; sinon le refus, en clair.
 *
 * La frontière de ce qu'on sait LIVRER, pas de ce qu'on sait décrire.
 * `Webhook` et `StartRule` existent dans le scellé pour que le format ne bouge
 * plus, pas pour partir aujourd'hui — les enfiler ferait des lignes que le
 * livreur ne peut que marquer DEAD.
 */
private fun undeliverable(logic: RuleLogic, policies: RulePolicies): String? {
    for (action in logic.actions) {
        val type = when (action) {
            is Action.Push -> DeliveryWorker.TYPE_PUSH
            is Action.Email -> DeliveryWorker.TYPE_EMAIL
            is Action.SetSignal -> DeliveryWorker.TYPE_COMMAND
            is Action.Webhook -> return "Webhook actions have no delivery channel yet"
            is Action.StartRule -> return "StartRule actions have no delivery channel yet"
            // Une attente ne se livre pas : elle decoupe la sequence. Elle n'a
            // donc aucun canal a exiger, et la sauter ici est ce qui permet a
            // une regle qui en contient une de rester enregistrable.
            is Action.Wait -> continue
        }
        if (type !in policies.allowedActionTypes) {
            return "Action type '$type' is not deliverable on this server — no channel carries it"
        }
    }
    return null
}

// ── L'ordonnancement ──────────────────────────────────────────────────────

/**
 * Garde `scheduled_jobs` en phase avec la règle.
 *
 * Le sondage est un balayage de plage indexé sur des prochains tirs
 * PRÉ-CALCULÉS, jamais une analyse de tous les horaires toutes les dix
 * secondes. Une règle éteinte ou non horaire n'a simplement pas de ligne.
 */
private fun materializeSchedule(ruleId: String, trigger: Trigger, zone: ZoneId, enabled: Boolean) {
    val schedule = trigger as? Trigger.Schedule
    transaction {
        ScheduledJobTable.deleteWhere { ScheduledJobTable.ruleId eq ruleId }
        if (schedule != null && enabled) {
            ScheduledJobTable.insert {
                it[ScheduledJobTable.ruleId] = ruleId
                it[nextRunAt] = ScheduleMath.nextRunAfter(System.currentTimeMillis(), schedule, zone)
                it[timezone] = zone.id
            }
        }
    }
}

// ── Colonnes derivees de la definition ────────────────────────────────────

/**
 * Le genre de déclencheur, en colonne.
 *
 * Dérivé de la définition à l'écriture, pas une seconde vérité : c'est ce que
 * l'ordonnanceur et les pages de santé interrogent sans avoir à décoder du
 * JSON.
 */
private fun kindOf(trigger: Trigger): String = when (trigger) {
    is Trigger.SignalChanged -> "value"
    is Trigger.SignalTransition -> "transition"
    is Trigger.DeviceConnected -> "online"
    is Trigger.DeviceDisconnected -> "offline"
    is Trigger.SignalStale -> "stale"
    is Trigger.Schedule -> "schedule"
}

/** La clé du signal surveillé, ou `null` quand le déclencheur n'en vise aucun. */
private fun triggerKeyOf(trigger: Trigger): String? = when (trigger) {
    is Trigger.SignalChanged -> trigger.signal.signalKey
    is Trigger.SignalTransition -> trigger.signal.signalKey
    is Trigger.SignalStale -> trigger.signal.signalKey
    is Trigger.DeviceConnected, is Trigger.DeviceDisconnected, is Trigger.Schedule -> null
}

private class RuleRow(
    val id: String,
    val definition: String,
    val timeZoneId: String,
    val enabled: Boolean,
    /** Ce que l'appelant doit avoir lu pour avoir le droit de remplacer. */
    val schemaVersion: String
)

private fun findRule(ownerId: String, ruleId: String): RuleRow? = transaction {
    AutomationRuleTable.selectAll()
        .where { (AutomationRuleTable.id eq ruleId) and (AutomationRuleTable.ownerId eq ownerId) }
        .singleOrNull()
        ?.let {
            RuleRow(
                id = it[AutomationRuleTable.id],
                definition = it[AutomationRuleTable.definition],
                timeZoneId = it[AutomationRuleTable.timeZoneId],
                enabled = it[AutomationRuleTable.enabled],
                schemaVersion = it[AutomationRuleTable.schemaVersion]
            )
        }
}

/**
 * Combien de règles de ce genre ce compte possède.
 *
 * `internal` et non privée : c'est cette fonction qui OPPOSE le quota à la
 * création, et c'est elle qui doit l'AFFICHER dans `/api/me/entitlements`.
 * Deux comptages différents pour le même droit finiraient par se contredire,
 * et l'utilisateur verrait « 2 / 3 » sur un formulaire qui refuse.
 */
/**
 * Les regles d'un compte, rangees par ce qu'elles FONT.
 *
 * Une regle est une automatisation si elle commande un signal, une alerte
 * sinon. La distinction ne vit pas dans une colonne : elle se lit dans le
 * JSON de la definition, donc il faut le decoder pour trancher.
 *
 * ## Pourquoi les deux comptes sortent ENSEMBLE
 *
 * Ils se demandaient un par un, et le panneau d'usage les demande tous les
 * deux. Chaque appel relisait toutes les regles du compte et decodait
 * chacune ; deux appels faisaient donc le travail deux fois pour lire la
 * meme table. Au plafond du plan le plus large, cent regles, cela faisait
 * deux cents decodages JSON par affichage du profil.
 *
 * Une seule passe, et la seule colonne qui sert : la definition. `selectAll`
 * ramenait les dix-sept colonnes de la ligne, dont le nom, le fuseau et
 * l'icone, pour n'en lire qu'une.
 */
internal data class RuleCounts(val automations: Int, val alertes: Int) {
    fun of(automation: Boolean): Int = if (automation) automations else alertes
}

internal fun countRules(ownerId: String): RuleCounts = transaction {
    var automations = 0
    var alertes = 0
    AutomationRuleTable
        .select(AutomationRuleTable.definition)
        .where { AutomationRuleTable.ownerId eq ownerId }
        .forEach { row ->
            val logic = (RuleCodec.decode(row[AutomationRuleTable.definition])
                as? RuleCodec.Outcome.Ok)?.logic ?: return@forEach
            if (logic.actions.any { it is Action.SetSignal }) automations++ else alertes++
        }
    RuleCounts(automations, alertes)
}

internal fun countRules(ownerId: String, automation: Boolean): Int =
    countRules(ownerId).of(automation)

/**
 * Les règles à supprimer, par identifiant.
 *
 * Une borne sur la taille du lot : le plan le plus large en vend cent, et une
 * liste de dix mille serait une transaction qu'on tient pour quelqu'un qui n'a
 * pas dix mille règles à l'écran.
 */
@Serializable
data class DeleteRulesRequest(val ids: List<String>)

private const val MAX_DELETE_RULES = 200

/** Le niveau accepté, filtré — un client qui envoie n'importe quoi retombe sur info. */
private fun normalizeSeverity(v: String): String = when (v.lowercase()) {
    "info", "warning", "critical" -> v.lowercase()
    else -> "info"
}

/**
 * Combien d'envois un plafond de sortie a refusés aujourd'hui, par règle.
 *
 * ## Une requête pour tout le compte, pas une par règle
 *
 * L'écran de liste rend toutes les règles d'un coup. Une requête par règle en
 * ferait vingt-cinq sur un plan Maker, sur le chemin d'un écran qu'on ouvre
 * souvent — et le nombre grandirait avec ce que le client achète.
 *
 * Le filtre porte le propriétaire et l'instant, dans cet ordre : c'est
 * exactement `idx_pending_by_owner_time`.
 *
 * ## Pourquoi minuit UTC ici, et minuit LOCAL ailleurs
 *
 * Les compteurs d'évaluation vivent dans `automation_state` et se réinitialisent
 * au fuseau de LA règle — « aujourd'hui » doit y dire la même chose que
 * l'horloge de celui qui lit. Ici, une seule requête sert toutes les règles
 * d'un compte, qui peuvent porter des fuseaux différents ; découper par fuseau
 * rendrait une requête par fuseau pour une ligne d'information.
 *
 * On prend donc la borne la PLUS LARGE — le minuit le plus ancien parmi les
 * fuseaux possibles, soit UTC−12 — et on assume que le compte peut voir
 * quelques refus de la veille au petit matin. Un refus de trop affiché vaut
 * mieux qu'un refus manquant : le but est de dire « ça ne part pas », pas de
 * tenir une comptabilité.
 */
private fun refusedTodayByRule(ownerId: String, nowMs: Long): Map<String, Int> {
    val since = nowMs - DAY_MS
    val out = HashMap<String, Int>()
    PendingActionTable.selectAll()
        .where {
            (PendingActionTable.ownerId eq ownerId) and
                (PendingActionTable.occurredAt greaterEq since) and
                (PendingActionTable.status eq PendingAction.REFUSED)
        }
        .forEach { row ->
            val ruleId = row[PendingActionTable.ruleId] ?: return@forEach
            out[ruleId] = (out[ruleId] ?: 0) + 1
        }
    return out
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * La lecture d'une règle, état et compteurs compris.
 *
 * Les libellés sont REPOSÉS ici, depuis la jointure vivante. L'état vient du
 * cache quand la règle y est — il porte la validité recalculée au chargement —
 * et se dérive de la ligne sinon.
 */
private fun listRules(
    ownerId: String,
    cache: RuleCache,
    resolver: RuleValidation.Resolver
): List<RuleResponse> {
    val now = System.currentTimeMillis()
    return transaction {
        val refused = refusedTodayByRule(ownerId, now)
        AutomationRuleTable.selectAll()
            .where { AutomationRuleTable.ownerId eq ownerId }
            .orderBy(AutomationRuleTable.createdAt)
            .map { row ->
                val id = row[AutomationRuleTable.id]
                val loaded = cache.byId(id)
                val decoded = RuleCodec.decode(row[AutomationRuleTable.definition])

                val rendered = when (decoded) {
                    is RuleCodec.Outcome.Ok ->
                        RuleCodec.encode(RuleValidation.rehydrate(decoded.logic, ownerId, resolver))
                    // Indechiffrable : on rend le texte brut plutot qu'un vide.
                    // L'app affichera « fonction plus recente » et laissera
                    // l'interrupteur actif — c'est la soupape du §5.
                    is RuleCodec.Outcome.Invalid -> row[AutomationRuleTable.definition]
                }
                val invalid = loaded?.invalidReason
                    ?: (decoded as? RuleCodec.Outcome.Invalid)?.code
                    ?: row[AutomationRuleTable.invalidReason]

                val st = loaded?.state
                RuleResponse(
                    id = id,
                    name = row[AutomationRuleTable.name],
                    enabled = row[AutomationRuleTable.enabled],
                    severity = row[AutomationRuleTable.severity],
                    timeZoneId = row[AutomationRuleTable.timeZoneId],
                    homeProjectId = row[AutomationRuleTable.homeProjectId],
                    schemaVersion = row[AutomationRuleTable.schemaVersion],
                    icon = row[AutomationRuleTable.icon],
                    color = row[AutomationRuleTable.color],
                    definition = rendered,
                    state = loaded?.displayState() ?: when {
                        invalid != null -> "INVALID"
                        !row[AutomationRuleTable.enabled] -> "DISABLED"
                        else -> "ACTIVE"
                    },
                    invalidReason = invalid,
                    lastFiredAt = st?.lastFiredAt,
                    todayEvaluations = st?.evaluations ?: 0,
                    todayFired = st?.fired ?: 0,
                    todayConditionFalse = st?.conditionFalse ?: 0,
                    todayConditionUnknown = st?.conditionUnknown ?: 0,
                    todayRefused = refused[id] ?: 0,
                    createdAtMs = row[AutomationRuleTable.createdAt]
                )
            }
    }
}
