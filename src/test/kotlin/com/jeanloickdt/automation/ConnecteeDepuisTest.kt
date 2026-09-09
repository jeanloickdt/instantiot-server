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
 * « Connectée DEPUIS » — la confirmation, prise par l'autre bout.
 *
 * ## Pourquoi elle manquait
 *
 * La déconnexion avait son délai de confirmation, l'anti-rebond d'un Wi-Fi
 * faible sans lequel chaque hoquet est un push. La reconnexion n'en avait
 * pas, alors que le battement est le MÊME phénomène vu de l'autre côté : une
 * carte qui va et vient produit autant de « elle est revenue » que de « elle
 * est partie ».
 *
 * ## Ce que ces épreuves gravent
 *
 * Trois propriétés, et la troisième est celle qui compte :
 *
 *  1. sans délai, rien ne change — les règles écrites avant ce champ tirent
 *     à la reconnexion, exactement comme avant ;
 *  2. avec un délai, le balayage confirme ;
 *  3. une déconnexion pendant l'attente l'ANNULE. Sans cela, un aller-retour
 *     de trois secondes finirait par déclencher une règle qui annonce cinq
 *     minutes, ce qui serait faux.
 */
class ConnecteeDepuisTest {

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

    /** [afterMs] nul = le champ ABSENT de la trame, comme les regles d'avant. */
    private fun seed(afterMs: Long?) {
        val delai = afterMs?.let { ""","afterMs":$it""" } ?: ""
        val definition = """{"trigger":{"kind":"deviceConnected",""" +
            """"device":{"projectId":"$projectId","deviceId":"$deviceId"}$delai},""" +
            """"actions":[{"kind":"push","title":"Revenue","body":"ok"}]}"""
        transaction {
            exec(
                """INSERT INTO automation_rules
                   (id, owner_id, name, enabled, trigger_kind, trigger_signal_key, definition,
                    time_zone_id, schema_version, created_at, updated_at)
                   VALUES ('r1','$ownerId','revenue',true,'presence',NULL,
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

    private fun envois(): Int = transaction {
        exec("SELECT count(*) FROM pending_actions") { rs -> rs.next(); rs.getInt(1) }!!
    }

    /** Le comportement d'avant, inchange : c'est ce qui rend le champ sur. */
    @Test
    fun `sans delai la reconnexion tire tout de suite`() {
        seed(null)
        val moteur = engine()
        moteur.handle(RelayEvent.DeviceOnline(ownerId, deviceId, now))
        assertEquals(1, envois(), "une regle sans delai doit tirer a la reconnexion")
    }

    @Test
    fun `avec un delai la reconnexion seule ne tire pas`() {
        seed(5 * 60_000L)
        val moteur = engine()
        moteur.handle(RelayEvent.DeviceOnline(ownerId, deviceId, now))
        assertEquals(0, envois(), "la confirmation n'a pas encore eu lieu")
    }

    @Test
    fun `le balayage confirme une fois le delai passe`() {
        seed(5 * 60_000L)
        val moteur = engine()
        moteur.handle(RelayEvent.DeviceOnline(ownerId, deviceId, now))

        moteur.tick(now + 4 * 60_000L)
        assertEquals(0, envois(), "quatre minutes ne font pas cinq")

        moteur.tick(now + 5 * 60_000L)
        assertEquals(1, envois(), "le delai est atteint")
    }

    /**
     * LA PROPRIETE QUI COMPTE. Sans elle, une carte qui bat produirait la
     * meme avalanche de push que celle qu'on evite du cote deconnexion.
     */
    @Test
    fun `une deconnexion pendant l'attente l'annule`() {
        seed(5 * 60_000L)
        val moteur = engine()
        moteur.handle(RelayEvent.DeviceOnline(ownerId, deviceId, now))
        moteur.handle(RelayEvent.DeviceOffline(ownerId, deviceId, "test", now + 3_000L))

        moteur.tick(now + 10 * 60_000L)
        assertEquals(0, envois(), "la carte n'est pas restee : il n'y a rien a annoncer")
    }

    /**
     * Une carte bavarde republie `DeviceOnline` sans etre repartie. Repousser
     * l'echeance a chaque fois voudrait dire ne jamais confirmer.
     */
    @Test
    fun `une seconde annonce ne repousse pas l'echeance`() {
        seed(5 * 60_000L)
        val moteur = engine()
        moteur.handle(RelayEvent.DeviceOnline(ownerId, deviceId, now))
        moteur.handle(RelayEvent.DeviceOnline(ownerId, deviceId, now + 4 * 60_000L))

        moteur.tick(now + 5 * 60_000L)
        assertEquals(1, envois(), "l'attente date de la PREMIERE annonce")
    }

    /** Une seule fois : l'attente est videe par la confirmation. */
    @Test
    fun `la confirmation ne tire qu'une fois`() {
        seed(60_000L)
        val moteur = engine()
        moteur.handle(RelayEvent.DeviceOnline(ownerId, deviceId, now))

        moteur.tick(now + 60_000L)
        moteur.tick(now + 120_000L)
        moteur.tick(now + 180_000L)
        assertEquals(1, envois(), "trois balayages, un seul envoi")
    }
}
