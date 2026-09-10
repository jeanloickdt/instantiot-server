package com.jeanloickdt.automation

import com.jeanloickdt.automation.v2.AutomationEngine
import com.jeanloickdt.automation.v2.AutomationRuns
import com.jeanloickdt.automation.v2.InventoryResolver
import com.jeanloickdt.automation.v2.RuleCache
import com.jeanloickdt.automation.v2.SignalValueCache
import com.jeanloickdt.deviceRepository
import com.jeanloickdt.event.EventSinks
import com.jeanloickdt.event.RelayEvent
import com.jeanloickdt.projectRepository
import com.jeanloickdt.signalRepository
import com.jeanloickdt.userRepository
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Le moteur n'a plus le droit de faire taire une règle.
 *
 * ## Ce que ces épreuves gravent
 *
 * Le cooldown et le fusible étaient des garde-fous contre la configuration de
 * l'utilisateur, et un garde-fou contre l'utilisateur est un aveu de
 * conception : ses règles sont son programme. S'il en écrit une qui déraille,
 * il brûle son quota — c'est son affaire.
 *
 * Le fusible était le pire des deux. Il faisait taire la règle au moment
 * précis où un capteur s'affole, c'est-à-dire au moment où on veut le plus
 * être prévenu, et il le faisait en douce.
 *
 * Ces deux mécanismes n'étaient **couverts par aucune épreuve** quand on les
 * a retirés. Leur absence, elle, en a : c'est le seul moyen qu'aucune bonne
 * intention ne les réintroduise dans six mois.
 *
 * ## Ce qui les remplace
 *
 * Rien, ici. La répétition se choisit au DÉCLENCHEUR — « à chaque trame »
 * tire en permanence, un front tire une fois par franchissement. Les seules
 * bornes qui restent sont aux expéditeurs, et elles nous protègent du
 * signalement pour spam, pas quelqu'un de lui-même.
 */
class SansCooldownNiFusibleTest {

    private var now = 1_000_000L
    private lateinit var ownerId: String
    private lateinit var deviceId: String
    private lateinit var projectId: String

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.connectAndClean()
        ownerId = userRepository.create("serre", BCrypt.hashpw("secret123", BCrypt.gensalt()), "user", true)
        projectId = projectRepository.create(ownerId, "serre").id
        deviceId = deviceRepository.create(
            name = "board", projectId = projectId, ownerId = ownerId, tokenHash = "h-serre",
            deviceType = com.jeanloickdt.device.domain.DeviceType.ESP32,
            connectivity = com.jeanloickdt.device.domain.DeviceConnectivity.WIFI
        ).id
        signalRepository.create(ownerId, deviceId, 0, "Temp", "float", nowMs = 0L)
    }

    /**
     * « À chaque trame », sans condition — le déclencheur le plus bavard qui
     * soit, et celui sur lequel le cooldown mordait le plus fort.
     *
     * `cooldown_ms` ne figure plus dans l'insertion. Elle a quitté le
     * modèle avec le v1, puis la base le 9 septembre 2026 : une insertion
     * qui la nomme encore ne passe plus sur un schéma neuf.
     */
    private fun seedBavarde() {
        val definition = """{"trigger":{"kind":"signalChanged",""" +
            """"signal":{"projectId":"$projectId","deviceId":"$deviceId","address":0}},""" +
            """"actions":[{"kind":"push","title":"Chaud","body":"{{value}}"}]}"""
        transaction {
            exec(
                """INSERT INTO automation_rules
                   (id, owner_id, name, enabled, trigger_kind, trigger_signal_key, definition,
                    time_zone_id, schema_version, created_at, updated_at)
                   VALUES ('r1','$ownerId','bavarde',true,'value','$deviceId:0',
                   '$definition','America/Toronto','v2',0,0)"""
            )
        }
    }

    private fun engine() = AutomationEngine(
        sinks = EventSinks(),
        cache = RuleCache(InventoryResolver(signalRepository, deviceRepository)).apply { reload() },
        values = SignalValueCache(signalRepository),
        actions = ExposedPendingActionRepository(),
        runs = AutomationRuns(),
        devices = deviceRepository,
        clock = { now }
    )

    /** [n] trames, une par seconde. */
    private fun AutomationEngine.trames(n: Int) {
        repeat(n) { handle(RelayEvent.SignalValue(ownerId, "$deviceId:0", null, 30.0 + it, now + it * 1_000L)) }
    }

    private fun actions(): Int = transaction {
        exec("SELECT count(*) FROM pending_actions") { rs -> rs.next(); rs.getInt(1) }!!
    }

    /**
     * La sourdine ne peut plus s'ecrire : il n'y a plus OU.
     *
     * L'epreuve lisait `muted_until` et verifiait qu'elle restait nulle. Elle
     * ne passait que tant que la colonne survivait en base, et elle est partie
     * le 9 septembre 2026 — la lecture echouait alors sur un schema neuf, pour
     * une raison qui n'avait rien a voir avec ce qu'elle eprouvait.
     *
     * Le temoin est desormais le MODELE : le moteur ne peut ecrire que ce que
     * la table declare. Une colonne absente du modele est une sourdine
     * inexprimable, ce qui est plus fort que « nulle a cet instant ».
     */
    private fun colonnesDeSourdine(): List<String> =
        com.jeanloickdt.automation.data.AutomationStateTable.columns
            .map { it.name }
            .filter { it == "muted_until" || it == "mute_reason" }

    @Test
    fun `six trames en six secondes font six envois`() {
        // Avec le cooldown de cinq minutes que la ligne porte encore, la
        // deuxieme aurait ete supprimee et les quatre suivantes avec elle.
        // La colonne est la, et elle ne fait plus rien.
        seedBavarde()
        engine().trames(6)
        assertEquals(6, actions(), "une trame, un envoi — le cooldown ne decide plus a la place de la regle")
    }

    @Test
    fun `au-dela de l ancien seuil du fusible, la regle continue`() {
        // L'ancien fusible : cinq tirs en dix minutes, puis sourdine d'une
        // heure. Douze tirs en douze secondes le franchissaient largement.
        seedBavarde()
        engine().trames(12)
        assertEquals(12, actions(), "rien ne fait plus taire une regle d'autorite")
        assertEquals(
            emptyList(), colonnesDeSourdine(),
            "la sourdine ne doit plus avoir d'endroit ou s'ecrire"
        )
    }

    @Test
    fun `aucune notification de sourdine n est jamais ecrite`() {
        // Le fusible s'annoncait par un push « <regle> is muted ». Plus rien
        // ne doit produire ce message : il n'y a plus de sourdine a annoncer.
        seedBavarde()
        engine().trames(12)
        val sourdines = transaction {
            exec("SELECT count(*) FROM pending_actions WHERE payload LIKE '%is muted%'") { rs ->
                rs.next(); rs.getInt(1)
            }!!
        }
        assertEquals(0, sourdines)
    }
}
