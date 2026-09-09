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
 * Le front d'un seuil — ce que « devient » seul ne savait pas faire.
 *
 * ## Le trou que ces épreuves ferment
 *
 * Sur une sonde analogique, la température va de 29,8 à 30,2 sans jamais
 * valoir 30,000. « Devient 30 » ne partait donc JAMAIS, et il ne restait que
 * « à chaque trame », qui part toujours. Entre les deux, rien — c'est-à-dire
 * exactement l'automatisation que tout le monde écrit en premier.
 *
 * ## Ce qui reste un front
 *
 * L'opérateur ne remplace pas le front, il en change le prédicat. Une sonde
 * qui reste à 31 degrés pendant une heure envoie une trame par seconde : sans
 * front, ce serait trois mille six cents notifications pour un seul
 * dépassement.
 */
class SignalTransitionEdgeTest {

    private var now = 1_000_000L
    private lateinit var ownerId: String
    private lateinit var deviceId: String
    private lateinit var projectId: String

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.connectAndClean()
        ownerId = userRepository.create("sonde", BCrypt.hashpw("secret123", BCrypt.gensalt()), "user", true)
        projectId = projectRepository.create(ownerId, "serre").id
        deviceId = deviceRepository.create(
            name = "board", projectId = projectId, ownerId = ownerId, tokenHash = "h-sonde",
            deviceType = com.jeanloickdt.device.domain.DeviceType.ESP32,
            connectivity = com.jeanloickdt.device.domain.DeviceConnectivity.WIFI
        ).id
        signalRepository.create(ownerId, deviceId, 0, "Temp", "float", nowMs = 0L)
    }

    /**
     * Une règle de transition, posée directement en base.
     *
     * En SQL plutôt que par la route : ce qu'on éprouve ici est le moteur, et
     * passer par HTTP mêlerait un refus d'écriture à un défaut de détection
     * sans qu'on puisse dire lequel a parlé.
     */
    private fun seedRule(op: String?, to: Double) {
        val opField = if (op == null) "" else ""","op":"$op""""
        val definition = """{"trigger":{"kind":"signalTransition",""" +
            """"signal":{"projectId":"$projectId","deviceId":"$deviceId","address":0},""" +
            """"to":{"type":"float","value":$to}$opField},""" +
            """"actions":[{"kind":"push","title":"Chaud","body":"Seuil franchi"}]}"""
        transaction {
            exec(
                """INSERT INTO automation_rules
                   (id, owner_id, name, enabled, trigger_kind, trigger_signal_key, definition,
                    time_zone_id, schema_version, created_at, updated_at)
                   VALUES ('r1','$ownerId','seuil',true,'transition','$deviceId:0',
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

    /** La marche de la sonde, trame par trame — chacune à sa seconde. */
    private fun AutomationEngine.walk(vararg values: Double) {
        for ((i, v) in values.withIndex()) {
            handle(RelayEvent.SignalValue(ownerId, "$deviceId:0", null, v, now + i * 1_000L))
        }
    }

    private fun fires(): Int = transaction {
        exec("SELECT count(*) FROM pending_actions") { rs -> rs.next(); rs.getInt(1) }!!
    }

    @Test
    fun `l egalite ne voit jamais passer une sonde analogique`() {
        // LE defaut d'origine, ecrit noir sur blanc. Sans cette epreuve, rien
        // ne dit pourquoi les operateurs existent.
        seedRule(op = null, to = 30.0)
        engine().walk(29.6, 29.8, 30.2, 30.7, 31.4)
        assertEquals(0, fires(), "la temperature n'a jamais VALU 30,000 — et c'est bien le probleme")
    }

    @Test
    fun `un seuil part une fois en franchissant, pas a chaque trame au-dessus`() {
        seedRule(op = "gte", to = 30.0)
        engine().walk(29.6, 29.8, 30.2, 30.7, 31.4)
        assertEquals(1, fires(), "un franchissement, une alerte — pas une par trame chaude")
    }

    @Test
    fun `il se rearme en redescendant`() {
        // Sinon la premiere alerte serait la derniere de la vie du compte.
        seedRule(op = "gte", to = 30.0)
        engine().walk(29.0, 30.5, 31.0, 28.0, 30.9)
        assertEquals(2, fires(), "deux montees franches valent deux alertes")
    }

    @Test
    fun `le sens inverse existe aussi`() {
        seedRule(op = "lt", to = 5.0)
        engine().walk(8.0, 6.0, 4.2, 3.1, 7.0, 2.0)
        assertEquals(2, fires(), "deux descentes sous 5 valent deux alertes")
    }
}
