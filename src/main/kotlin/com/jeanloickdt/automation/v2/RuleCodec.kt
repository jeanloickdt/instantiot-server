package com.jeanloickdt.automation.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Le codec de la colonne `definition` — décodage et encodage de [RuleLogic].
 *
 * ## Pourquoi une descente récursive écrite à la main
 *
 * kotlinx aurait pu faire ça avec du polymorphisme scellé et beaucoup moins de
 * lignes. Deux raisons de ne pas s'en servir ici.
 *
 * **La borne de profondeur doit tomber AVANT toute validation métier.** La
 * définition est du JSON fourni par le client ; `All(All(All(…)))` sur dix
 * mille niveaux fait sauter la pile pendant la désérialisation elle-même,
 * c'est-à-dire avant que le moindre garde-fou puisse s'exécuter. Un
 * désérialiseur généré n'offre aucun endroit où compter.
 *
 * **Les codes d'erreur sont un contrat.** `depth-exceeded`, `degenerate-tree`,
 * `tz-in-leaf` : chacun est écrit dans le document de sémantique, rendu par
 * l'API en 400, et attendu par une fixture. Une exception de désérialisation
 * générique ne les distingue pas.
 *
 * ## Aller-retour
 *
 * `decode(encode(x)) == x` pour toute logique valide, et `encode` produit une
 * forme canonique — clés dans un ordre fixe, aucun champ nul écrit. C'est ce
 * qui permet aux fixtures d'être comparées octet à octet, des deux côtés du
 * fil.
 */
object RuleCodec {

    /**
     * La profondeur maximale de l'arbre de condition.
     *
     * Huit. Un humain qui imbrique huit ET/OU dans une seule règle a un
     * problème que l'éditeur ne résoudra pas ; au-delà, c'est du JSON fabriqué,
     * et le seul enjeu est de ne pas faire sauter la pile.
     */
    const val MAX_DEPTH = 8

    /**
     * Le plafond d'une attente : vingt-quatre heures.
     *
     * Pas une politique, une RESSOURCE. Chaque attente en vol est une ligne
     * qui vit jusqu'à son échéance ; sans plafond, « attends six mois » est
     * une ligne qu'on porte six mois, et une règle qui tire chaque seconde en
     * accumule quinze millions.
     *
     * Vingt-quatre heures couvre tout ce qu'une attente veut dire — « éteins
     * ce soir », « rappelle-moi demain matin ». Au-delà, ce n'est plus une
     * attente, c'est un horaire, et il y a un déclencheur pour ça.
     */
    const val MAX_WAIT_SECONDS = 24 * 60 * 60L

    /** La version que ce codec sait lire ET écrire. Vérifiée des deux côtés. */
    const val SCHEMA_VERSION = "v2"

    // ── Les codes d'erreur, tels que l'API et les fixtures les attendent ──

    const val E_DEPTH = "depth-exceeded"
    const val E_DEGENERATE_TREE = "degenerate-tree"
    const val E_DEGENERATE_TIMERANGE = "degenerate-timerange"
    const val E_EMPTY_ACTIONS = "empty-actions"
    const val E_TZ_IN_LEAF = "tz-in-leaf"
    const val E_MALFORMED = "malformed"

    /** `unknown-variant:<type>` — une variante que cette version ne connaît pas. */
    fun unknownVariant(type: String) = "unknown-variant:$type"

    /** `unknown-schema:<valeur>` — jamais réinterprété, toujours refusé. */
    fun unknownSchema(value: String?) = "unknown-schema:${value ?: "absent"}"

    sealed interface Outcome {
        data class Ok(val logic: RuleLogic) : Outcome
        /** [code] est l'un des `E_*` ci-dessus ; [detail] éclaire l'humain. */
        data class Invalid(val code: String, val detail: String) : Outcome
    }

    private val json = Json { prettyPrint = false }

    // ── Décodage ─────────────────────────────────────────────────────────

    fun decode(raw: String): Outcome {
        return try {
            val root = Json.parseToJsonElement(raw).jsonObject

            val trigger = when (val t = decodeTrigger(root.obj("trigger")
                ?: return invalid(E_MALFORMED, "missing 'trigger'"))) {
                is Err -> return t.outcome
                is Val -> t.value
            }

            // `null` explicite et champ absent disent la meme chose : pas de
            // condition, donc toujours vrai. L'UI, elle, doit afficher
            // « Toujours » plutot qu'un cadre vide — la difference entre « pas de
            // condition » et « condition oubliee » doit rester lisible.
            val conditionNode = root["condition"]
            val condition = if (conditionNode == null || conditionNode is kotlinx.serialization.json.JsonNull) {
                null
            } else {
                when (val c = decodeCondition(conditionNode.asObj() ?: return invalid(E_MALFORMED, "'condition' is not an object"), depth = 1)) {
                    is Err -> return c.outcome
                    is Val -> c.value
                }
            }

            val actionsNode = root["actions"]?.asArray()
                ?: return invalid(E_MALFORMED, "missing 'actions'")
            // Une regle qui ne fait rien n'est pas une regle : c'est un bug en
            // amont qu'on refuse d'enregistrer plutot que de laisser tirer dans
            // le vide chaque nuit.
            if (actionsNode.isEmpty()) return invalid(E_EMPTY_ACTIONS, "a rule with no action does nothing")
            val actions = actionsNode.map { el ->
                when (val a = decodeAction(el.asObj() ?: return invalid(E_MALFORMED, "action is not an object"))) {
                    is Err -> return a.outcome
                    is Val -> a.value
                }
            }

            Outcome.Ok(RuleLogic(trigger, condition, actions))
        } catch (e: Exception) {
            invalid(E_MALFORMED, e.message ?: e::class.simpleName ?: "unparseable")
        }
    }

    // Un petit couple pour propager soit une valeur, soit un echec, sans
    // exceptions : les codes d'erreur sont un contrat, et une exception
    // traversant huit niveaux de recursion perdrait le code exact en route.
    private sealed interface Res<out T>
    private class Val<T>(val value: T) : Res<T>
    private class Err(val outcome: Outcome.Invalid) : Res<Nothing>

    private fun invalid(code: String, detail: String) = Outcome.Invalid(code, detail)
    private fun err(code: String, detail: String) = Err(Outcome.Invalid(code, detail))

    private fun decodeTrigger(node: JsonObject): Res<Trigger> {
        // Le fuseau appartient a la REGLE, jamais a une feuille : deux fuseaux
        // dans une meme regle rendraient legale une regle programmee a Toronto
        // qui teste les heures de Teheran.
        if (node.containsKey("tz") || node.containsKey("timeZoneId")) {
            return err(E_TZ_IN_LEAF, "a trigger carries no timezone — the rule does")
        }
        return when (val kind = node.str("kind")) {
            "signalChanged" -> signalRef(node.obj("signal")).map { Trigger.SignalChanged(it) }

            "signalTransition" -> signalRef(node.obj("signal")).flatMap { ref ->
                val to = typedValue(node.obj("to")) ?: return err(E_MALFORMED, "signalTransition needs 'to'")
                val fromNode = node.obj("from")
                val from = if (fromNode == null) null
                           else typedValue(fromNode) ?: return err(E_MALFORMED, "bad 'from'")
                // ABSENT vaut `eq` : les regles ecrites avant cet operateur
                // gardent exactement leur sens, et rien ne migre.
                val opWire = node.str("op")
                val op = if (opWire == null) Op.EQ
                         else Op.ofWire(opWire)
                             ?: return err(unknownVariant(opWire), "unknown operator")
                Val(Trigger.SignalTransition(ref, to, op, from))
            }

            "deviceConnected" -> deviceRef(node.obj("device")).flatMap { ref ->
                // MEME REGLE QUE LA DECONNEXION, et pour la meme raison : un
                // delai negatif n'a pas de sens, et l'absence de champ vaut
                // « tout de suite ». Les regles ecrites avant ce champ
                // traversent donc ce decodeur sans changer de comportement.
                val after = node["afterMs"]?.asLong()
                if (after != null && after < 0) return err(E_MALFORMED, "afterMs must be >= 0")
                Val(Trigger.DeviceConnected(ref, after))
            }

            "deviceDisconnected" -> deviceRef(node.obj("device")).flatMap { ref ->
                val after = node["afterMs"]?.asLong()
                if (after != null && after < 0) return err(E_MALFORMED, "afterMs must be >= 0")
                Val(Trigger.DeviceDisconnected(ref, after))
            }

            "signalStale" -> signalRef(node.obj("signal")).flatMap { ref ->
                val after = node["afterMs"]?.asLong()
                    ?: return err(E_MALFORMED, "signalStale needs afterMs")
                if (after <= 0) return err(E_MALFORMED, "afterMs must be > 0")
                Val(Trigger.SignalStale(ref, after))
            }

            "schedule" -> {
                val minute = node["minuteOfDay"]?.asInt()
                    ?: return err(E_MALFORMED, "schedule needs minuteOfDay")
                if (minute !in 0..1439) return err(E_MALFORMED, "minuteOfDay must be 0..1439")
                val days = node["days"]?.asArray()?.map { d ->
                    Trigger.Day.ofWire(d.jsonPrimitive.content)
                        ?: return err(E_MALFORMED, "unknown day '${d.jsonPrimitive.content}'")
                }?.toSet() ?: emptySet()
                Val(Trigger.Schedule(minute, days))
            }

            null -> err(E_MALFORMED, "trigger without 'kind'")
            else -> err(unknownVariant(kind), "unknown trigger kind '$kind'")
        }
    }

    private fun decodeCondition(node: JsonObject, depth: Int): Res<Condition> {
        // La borne tombe ICI, avant tout le reste : on est deja dans la
        // recursion, et c'est le seul endroit ou compter a un sens.
        if (depth > MAX_DEPTH) {
            return err(E_DEPTH, "condition tree deeper than $MAX_DEPTH")
        }
        if (node.containsKey("tz") || node.containsKey("timeZoneId")) {
            return err(E_TZ_IN_LEAF, "a condition carries no timezone — the rule does")
        }
        return when (val kind = node.str("kind")) {
            "compare" -> signalRef(node.obj("left")).flatMap { left ->
                val op = Op.ofWire(node.str("op") ?: return err(E_MALFORMED, "compare needs 'op'"))
                    ?: return err(unknownVariant(node.str("op")!!), "unknown operator")
                val rightNode = node.obj("right") ?: return err(E_MALFORMED, "compare needs 'right'")
                when (val r = decodeOperand(rightNode)) {
                    is Err -> r
                    is Val -> Val(Condition.Compare(left, op, r.value))
                }
            }

            "deviceState" -> deviceRef(node.obj("device")).flatMap { ref ->
                val st = Condition.Presence.ofWire(node.str("state") ?: return err(E_MALFORMED, "deviceState needs 'state'"))
                    ?: return err(unknownVariant(node.str("state")!!), "unknown presence")
                Val(Condition.DeviceState(ref, st))
            }

            "timeOfDay" -> {
                val from = node["from"]?.asInt() ?: return err(E_MALFORMED, "timeOfDay needs 'from'")
                val to = node["to"]?.asInt() ?: return err(E_MALFORMED, "timeOfDay needs 'to'")
                if (from !in 0..1439 || to !in 0..1439) {
                    return err(E_MALFORMED, "from/to must be 0..1439")
                }
                // `from == to` ne designe ni un instant ni une journee entiere
                // — c'est une plage dont on ne peut pas deviner l'intention.
                if (from == to) return err(E_DEGENERATE_TIMERANGE, "from == to designates nothing")
                Val(Condition.TimeOfDay(from, to))
            }

            "all", "any" -> {
                val children = node["children"]?.asArray()
                    ?: return err(E_MALFORMED, "'$kind' needs 'children'")
                // Un `All` a un seul enfant EST cet enfant : l'accepter
                // laisserait deux ecritures pour une meme regle, donc deux
                // formes canoniques, donc un aller-retour qui ne boucle pas.
                if (children.size < 2) {
                    return err(E_DEGENERATE_TREE, "'$kind' needs at least 2 children, got ${children.size}")
                }
                val decoded = children.map { c ->
                    val obj = c.asObj() ?: return err(E_MALFORMED, "child is not an object")
                    when (val d = decodeCondition(obj, depth + 1)) {
                        is Err -> return d
                        is Val -> d.value
                    }
                }
                Val(if (kind == "all") Condition.All(decoded) else Condition.Any(decoded))
            }

            "not" -> {
                val childNode = node.obj("child") ?: return err(E_MALFORMED, "'not' needs 'child'")
                when (val d = decodeCondition(childNode, depth + 1)) {
                    is Err -> d
                    is Val -> Val(Condition.Not(d.value))
                }
            }

            null -> err(E_MALFORMED, "condition without 'kind'")
            else -> err(unknownVariant(kind), "unknown condition kind '$kind'")
        }
    }

    private fun decodeOperand(node: JsonObject): Res<Operand> {
        return when (val kind = node.str("kind")) {
            "literal" -> {
                val v = typedValue(node) ?: return err(E_MALFORMED, "bad literal")
                Val(Operand.Literal(v))
            }
            "signal" -> signalRef(node).map { Operand.Signal(it) }
            null -> err(E_MALFORMED, "operand without 'kind'")
            else -> err(unknownVariant(kind), "unknown operand kind '$kind'")
        }
    }

    private fun decodeAction(node: JsonObject): Res<Action> {
        return when (val kind = node.str("kind")) {
            "push" -> Val(Action.Push(
                node.str("title") ?: return err(E_MALFORMED, "push needs 'title'"),
                node.str("body") ?: return err(E_MALFORMED, "push needs 'body'")
            ))
            "email" -> {
                // Le destinataire est FACULTATIF — absent, c'est le compte —
                // mais s'il est la, il doit ressembler a une adresse. Un
                // "bonjour" ecrit dans ce champ ne partirait nulle part, et
                // l'echec n'arriverait qu'au premier tir, la nuit.
                val to = node.str("to")?.trim()?.takeIf { it.isNotEmpty() }
                if (to != null && !looksLikeEmail(to)) {
                    return err(E_MALFORMED, "email 'to' is not an address")
                }
                Val(Action.Email(
                    node.str("subject") ?: return err(E_MALFORMED, "email needs 'subject'"),
                    node.str("body") ?: return err(E_MALFORMED, "email needs 'body'"),
                    to
                ))
            }
            "setSignal" -> signalRef(node.obj("target")).flatMap { ref ->
                val v = typedValue(node.obj("value")) ?: return err(E_MALFORMED, "setSignal needs 'value'")
                Val(Action.SetSignal(ref, v))
            }
            "webhook" -> Val(Action.Webhook(
                node.str("url") ?: return err(E_MALFORMED, "webhook needs 'url'"),
                node.str("method") ?: return err(E_MALFORMED, "webhook needs 'method'"),
                node.str("body")
            ))
            "startRule" -> Val(Action.StartRule(
                node.str("ruleId") ?: return err(E_MALFORMED, "startRule needs 'ruleId'")
            ))
            "wait" -> {
                // EXACTEMENT une des deux formes. Les deux ensemble ne veulent
                // rien dire, aucune non plus — et c'est ici que ca se refuse,
                // parce que le modele les rend deja impossibles a representer.
                val seconds = node["seconds"]?.asLong()
                val until = node["untilMinuteOfDay"]?.asLong()
                if ((seconds == null) == (until == null)) {
                    return err(E_MALFORMED, "wait needs exactly one of 'seconds' or 'untilMinuteOfDay'")
                }
                if (seconds != null) {
                    if (seconds <= 0) return err(E_MALFORMED, "wait 'seconds' must be > 0")
                    // Le plafond est ICI, au decodage, et pas seulement a
                    // l'ecriture : une definition venue d'ailleurs — un import,
                    // une version plus ancienne — ne doit pas pouvoir poser une
                    // echeance a six mois.
                    if (seconds > MAX_WAIT_SECONDS) {
                        return err(E_MALFORMED, "wait 'seconds' must be <= $MAX_WAIT_SECONDS (24 h)")
                    }
                    Val(Action.Wait(Action.Delay.For(seconds)))
                } else {
                    if (until!! !in 0..1439) {
                        return err(E_MALFORMED, "wait 'untilMinuteOfDay' must be 0..1439")
                    }
                    Val(Action.Wait(Action.Delay.Until(until.toInt())))
                }
            }
            null -> err(E_MALFORMED, "action without 'kind'")
            else -> err(unknownVariant(kind), "unknown action kind '$kind'")
        }
    }

    // ── Les briques ──────────────────────────────────────────────────────

    private fun signalRef(node: JsonObject?): Res<SignalRef> {
        if (node == null) return err(E_MALFORMED, "missing signal reference")
        val p = node.str("projectId") ?: return err(E_MALFORMED, "signal ref needs projectId")
        val d = node.str("deviceId") ?: return err(E_MALFORMED, "signal ref needs deviceId")
        val a = node["address"]?.asInt() ?: return err(E_MALFORMED, "signal ref needs address")
        if (a !in 0..255) return err(E_MALFORMED, "address must be 0..255")
        return Val(SignalRef(p, d, a, node.str("label")))
    }

    private fun deviceRef(node: JsonObject?): Res<DeviceRef> {
        if (node == null) return err(E_MALFORMED, "missing device reference")
        val p = node.str("projectId") ?: return err(E_MALFORMED, "device ref needs projectId")
        val d = node.str("deviceId") ?: return err(E_MALFORMED, "device ref needs deviceId")
        return Val(DeviceRef(p, d, node.str("label")))
    }

    private fun typedValue(node: JsonObject?): TypedValue? {
        if (node == null) return null
        val raw = node["value"]?.jsonPrimitive ?: return null
        return when (node.str("type")) {
            "int" -> raw.longOrNull?.let { TypedValue.Int(it) }
            "float" -> raw.doubleOrNull?.let { TypedValue.Float(it) }
            "string" -> TypedValue.Text(raw.content)
            else -> null
        }
    }

    // ── Encodage — forme canonique, comparable octet a octet ─────────────

    /**
     * Une LISTE d'actions, seule — ce qu'une attente en vol doit persister.
     *
     * Le même encodeur que dans une règle, donc la même forme : une action
     * relue depuis une continuation est exactement l'action qui y a été
     * écrite, et un champ renommé casse aux deux endroits à la fois.
     */
    fun encodeActions(actions: List<Action>): String =
        json.encodeToString(
            kotlinx.serialization.json.JsonArray.serializer(),
            kotlinx.serialization.json.JsonArray(actions.map { encodeAction(it) })
        )

    /** `null` quand la liste est indéchiffrable — l'appelant décide quoi en faire. */
    fun decodeActions(raw: String): List<Action>? {
        val array = runCatching {
            json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray
        }.getOrNull() ?: return null
        val out = ArrayList<Action>(array.size)
        for (element in array) {
            val node = element as? JsonObject ?: return null
            when (val r = decodeAction(node)) {
                is Val -> out += r.value
                else -> return null
            }
        }
        return out
    }

    /** Une valeur typée, seule — la valeur déclenchante d'une attente en vol. */
    fun encodeValue(v: TypedValue): String = enc(v).toString()

    /** `null` quand elle est indéchiffrable. */
    fun decodeValue(raw: String): TypedValue? =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?.let { typedValue(it) }

    fun encode(logic: RuleLogic): String = json.encodeToString(
        JsonObject.serializer(), encodeToJson(logic)
    )

    fun encodeToJson(logic: RuleLogic): JsonObject = buildJsonObject {
        put("trigger", encodeTrigger(logic.trigger))
        // Un champ absent plutot qu'un `null` explicite : une seule ecriture
        // pour « pas de condition », donc un aller-retour qui boucle.
        logic.condition?.let { put("condition", encodeCondition(it)) }
        put("actions", buildJsonArray { logic.actions.forEach { add(encodeAction(it)) } })
    }

    private fun encodeTrigger(t: Trigger): JsonObject = when (t) {
        is Trigger.SignalChanged -> buildJsonObject {
            put("kind", "signalChanged"); put("signal", enc(t.signal))
        }
        is Trigger.SignalTransition -> buildJsonObject {
            put("kind", "signalTransition")
            put("signal", enc(t.signal))
            put("to", enc(t.to))
            // `eq` reste IMPLICITE a l'encodage : ecrire l'operateur par
            // defaut changerait le JSON de toutes les regles existantes sans
            // qu'aucune n'ait change de sens.
            if (t.op != Op.EQ) put("op", t.op.wire)
            t.from?.let { put("from", enc(it)) }
        }
        is Trigger.DeviceConnected -> buildJsonObject {
            put("kind", "deviceConnected")
            put("device", enc(t.device))
            // ABSENT quand il est nul, pas ecrit a zero : l'aller-retour d'une
            // regle d'avant ce champ doit rendre exactement l'octet d'avant,
            // sinon le scelle bouge sans qu'aucun comportement ait change.
            t.afterMs?.let { put("afterMs", it) }
        }
        is Trigger.DeviceDisconnected -> buildJsonObject {
            put("kind", "deviceDisconnected")
            put("device", enc(t.device))
            t.afterMs?.let { put("afterMs", it) }
        }
        is Trigger.SignalStale -> buildJsonObject {
            put("kind", "signalStale"); put("signal", enc(t.signal)); put("afterMs", t.afterMs)
        }
        is Trigger.Schedule -> buildJsonObject {
            put("kind", "schedule")
            put("minuteOfDay", t.minuteOfDay)
            if (t.days.isNotEmpty()) {
                // Ordonnes par le scelle, pas par l'ordre d'insertion : un Set
                // ne promet pas d'ordre, et un aller-retour doit boucler.
                put("days", buildJsonArray {
                    Trigger.Day.entries.filter { it in t.days }.forEach { add(JsonPrimitive(it.wire)) }
                })
            }
        }
    }

    private fun encodeCondition(c: Condition): JsonObject = when (c) {
        is Condition.Compare -> buildJsonObject {
            put("kind", "compare")
            put("left", enc(c.left))
            put("op", c.op.wire)
            put("right", encodeOperand(c.right))
        }
        is Condition.DeviceState -> buildJsonObject {
            put("kind", "deviceState"); put("device", enc(c.device)); put("state", c.state.wire)
        }
        is Condition.TimeOfDay -> buildJsonObject {
            put("kind", "timeOfDay"); put("from", c.fromMinute); put("to", c.toMinute)
        }
        is Condition.All -> buildJsonObject {
            put("kind", "all")
            put("children", buildJsonArray { c.children.forEach { add(encodeCondition(it)) } })
        }
        is Condition.Any -> buildJsonObject {
            put("kind", "any")
            put("children", buildJsonArray { c.children.forEach { add(encodeCondition(it)) } })
        }
        is Condition.Not -> buildJsonObject {
            put("kind", "not"); put("child", encodeCondition(c.child))
        }
    }

    private fun encodeOperand(o: Operand): JsonObject = when (o) {
        is Operand.Literal -> buildJsonObject {
            put("kind", "literal")
            put("type", o.value.typeName)
            putValue(o.value)
        }
        is Operand.Signal -> buildJsonObject {
            put("kind", "signal")
            put("projectId", o.ref.projectId)
            put("deviceId", o.ref.deviceId)
            put("address", o.ref.address)
            o.ref.cachedLabel?.let { put("label", it) }
        }
    }

    private fun encodeAction(a: Action): JsonObject = when (a) {
        is Action.Push -> buildJsonObject {
            put("kind", "push"); put("title", a.title); put("body", a.body)
        }
        is Action.Email -> buildJsonObject {
            put("kind", "email"); put("subject", a.subject); put("body", a.body)
            // Absent quand il n'y en a pas : une regle ecrite avant ce champ
            // se relit octet pour octet identique.
            a.to?.let { put("to", it) }
        }
        is Action.SetSignal -> buildJsonObject {
            put("kind", "setSignal"); put("target", enc(a.target)); put("value", enc(a.value))
        }
        is Action.Webhook -> buildJsonObject {
            put("kind", "webhook"); put("url", a.url); put("method", a.method)
            a.body?.let { put("body", it) }
        }
        is Action.StartRule -> buildJsonObject {
            put("kind", "startRule"); put("ruleId", a.ruleId)
        }
        is Action.Wait -> buildJsonObject {
            put("kind", "wait")
            when (val d = a.delay) {
                is Action.Delay.For -> put("seconds", d.seconds)
                is Action.Delay.Until -> put("untilMinuteOfDay", d.minuteOfDay)
            }
        }
    }

    private fun enc(r: SignalRef): JsonObject = buildJsonObject {
        put("projectId", r.projectId); put("deviceId", r.deviceId); put("address", r.address)
        r.cachedLabel?.let { put("label", it) }
    }

    private fun enc(r: DeviceRef): JsonObject = buildJsonObject {
        put("projectId", r.projectId); put("deviceId", r.deviceId)
        r.cachedLabel?.let { put("label", it) }
    }

    private fun enc(v: TypedValue): JsonObject = buildJsonObject {
        put("type", v.typeName); putValue(v)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putValue(v: TypedValue) {
        when (v) {
            is TypedValue.Int -> put("value", v.value)
            is TypedValue.Float -> put("value", v.value)
            is TypedValue.Text -> put("value", v.value)
        }
    }

    // ── Retirer / reposer le libelle ─────────────────────────────────────

    /**
     * La forme qui part en base : sans aucun `cachedLabel`.
     *
     * Un libellé périmé ne survit jamais en base. Il est reposé à la lecture
     * depuis la jointure vivante — voir `SignalContextReader`.
     */
    fun stripLabels(logic: RuleLogic): RuleLogic = RuleLogic(
        trigger = stripTrigger(logic.trigger),
        condition = logic.condition?.let { stripCondition(it) },
        actions = logic.actions.map { stripAction(it) }
    )

    private fun stripTrigger(t: Trigger): Trigger = when (t) {
        is Trigger.SignalChanged -> t.copy(signal = t.signal.stripped())
        is Trigger.SignalTransition -> t.copy(signal = t.signal.stripped())
        is Trigger.SignalStale -> t.copy(signal = t.signal.stripped())
        is Trigger.DeviceConnected -> t.copy(device = t.device.stripped())
        is Trigger.DeviceDisconnected -> t.copy(device = t.device.stripped())
        is Trigger.Schedule -> t
    }

    private fun stripCondition(c: Condition): Condition = when (c) {
        is Condition.Compare -> c.copy(
            left = c.left.stripped(),
            right = when (val r = c.right) {
                is Operand.Signal -> Operand.Signal(r.ref.stripped())
                is Operand.Literal -> r
            }
        )
        is Condition.DeviceState -> c.copy(device = c.device.stripped())
        is Condition.TimeOfDay -> c
        is Condition.All -> Condition.All(c.children.map { stripCondition(it) })
        is Condition.Any -> Condition.Any(c.children.map { stripCondition(it) })
        is Condition.Not -> Condition.Not(stripCondition(c.child))
    }

    private fun stripAction(a: Action): Action = when (a) {
        is Action.SetSignal -> a.copy(target = a.target.stripped())
        else -> a
    }

    // ── Accesseurs surs sur du JSON etranger ─────────────────────────────

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonElement.asObj(): JsonObject? = this as? JsonObject
    private fun JsonElement.asArray(): JsonArray? = this as? JsonArray
    private fun JsonElement.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement.asLong(): Long? = (this as? JsonPrimitive)?.longOrNull

    private inline fun <T, R> Res<T>.map(f: (T) -> R): Res<R> = when (this) {
        is Err -> this
        is Val -> Val(f(value))
    }

    private inline fun <T, R> Res<T>.flatMap(f: (T) -> Res<R>): Res<R> = when (this) {
        is Err -> this
        is Val -> f(value)
    }
}

/**
 * Une adresse, au sens « ca peut partir ».
 *
 * Volontairement grossier : un `@` avec quelque chose des deux cotes et un
 * point apres. Valider une adresse pour de bon demande de l'envoyer, et une
 * regex savante refuse surtout des adresses valides. Ce qu'on attrape ici,
 * c'est la faute de frappe et le champ rempli avec autre chose.
 */
private fun looksLikeEmail(value: String): Boolean {
    val at = value.indexOf('@')
    if (at <= 0 || at != value.lastIndexOf('@')) return false
    val domain = value.substring(at + 1)
    return domain.length >= 3 && '.' in domain.drop(1).dropLast(1) && ' ' !in value
}
