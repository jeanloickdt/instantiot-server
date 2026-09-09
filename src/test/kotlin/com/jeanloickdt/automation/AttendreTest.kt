package com.jeanloickdt.automation

import com.jeanloickdt.automation.v2.Action
import com.jeanloickdt.automation.v2.AutomationEngine
import com.jeanloickdt.automation.v2.AutomationRuns
import com.jeanloickdt.automation.v2.ExposedContinuationRepository
import com.jeanloickdt.automation.v2.InventoryResolver
import com.jeanloickdt.automation.v2.RuleCache
import com.jeanloickdt.automation.v2.SignalValueCache
import com.jeanloickdt.automation.v2.dueAtOf
import com.jeanloickdt.deviceRepository
import com.jeanloickdt.event.EventSinks
import com.jeanloickdt.event.RelayEvent
import com.jeanloickdt.projectRepository
import com.jeanloickdt.signalRepository
import com.jeanloickdt.userRepository
import java.time.ZoneId
import java.time.ZonedDateTime
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TORONTO = ZoneId.of("America/Toronto")

/**
 * « Allume, attends trente secondes, éteins. »
 *
 * ## Ce que ces épreuves gravent
 *
 * Une attente n'est JAMAIS un `delay()`. L'échéance et les actions restantes
 * sont des lignes, et la reprise est une lecture — c'est la seule forme qui
 * survit à un redéploiement de 21 h sans laisser le chauffage allumé toute la
 * nuit. On le prouve en n'endormant jamais rien : le temps est une horloge
 * qu'on avance à la main, et la reprise un appel explicite.
 *
 * ## Et la machinerie sert trois choses
 *
 * `SignalStale` et `DeviceDisconnected(afterMs)` butent sur le même mur :
 * « rien ne s'est passé pendant N » n'est pas un événement, et aucun canal ne
 * le publiera jamais. Construite une fois, elle débloque les trois.
 */
class AttendreTest {

    private var now = ZonedDateTime.of(2026, 6, 10, 20, 0, 0, 0, TORONTO)
        .toInstant().toEpochMilli()

    private lateinit var ownerId: String
    private lateinit var deviceId: String
    private lateinit var projectId: String
    private val continuations = ExposedContinuationRepository()

    @BeforeTest
    fun setup() {
        com.jeanloickdt.database.TestDatabase.connectAndClean()
        ownerId = userRepository.create("serre", BCrypt.hashpw("secret123", BCrypt.gensalt()), "user", true)
        projectId = projectRepository.create(ownerId, "serre").id
        deviceId = deviceRepository.create(
            name = "board", projectId = projectId, ownerId = ownerId, tokenHash = "h",
            deviceType = com.jeanloickdt.device.domain.DeviceType.ESP32,
            connectivity = com.jeanloickdt.device.domain.DeviceConnectivity.WIFI
        ).id
        signalRepository.create(ownerId, deviceId, 0, "Temp", "float", nowMs = 0L)
    }

    /** « pousse, ATTENDS, pousse » — le cas le plus simple qui coupe en deux. */
    private fun seedSequence(waitJson: String, ruleId: String = "r1") {
        val definition = """{"trigger":{"kind":"signalChanged",""" +
            """"signal":{"projectId":"$projectId","deviceId":"$deviceId","address":0}},""" +
            """"actions":[""" +
            """{"kind":"push","title":"Debut","body":"b"},""" +
            waitJson + "," +
            """{"kind":"push","title":"Fin","body":"b"}]}"""
        transaction {
            exec(
                """INSERT INTO automation_rules
                   (id, owner_id, name, enabled, trigger_kind, trigger_signal_key, definition,
                    time_zone_id, schema_version, created_at, updated_at)
                   VALUES ('$ruleId','$ownerId','sequence',true,'value','$deviceId:0',
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
        continuations = continuations,
        clock = { now }
    )

    private fun trame(e: AutomationEngine, v: Double = 30.0) {
        e.handle(RelayEvent.SignalValue(ownerId, "$deviceId:0", null, v, now))
    }

    private fun titres(): List<String> = transaction {
        exec("SELECT payload FROM pending_actions ORDER BY id") { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }!!
    }.map { if ("Debut" in it) "Debut" else if ("Fin" in it) "Fin" else "?" }

    /** Le réveil, à la main — comme la boucle de fond, sans dormir. */
    private fun reveiller(e: AutomationEngine): Int {
        val dues = continuations.due(now, limit = 100)
        for (c in dues) { e.resume(c); continuations.delete(c.id) }
        return dues.size
    }

    // ── Le découpage ─────────────────────────────────────────────────────

    @Test
    fun `ce qui precede l attente part tout de suite, le reste attend`() {
        seedSequence("""{"kind":"wait","seconds":30}""")
        val e = engine()
        trame(e)

        assertEquals(listOf("Debut"), titres(), "seul le premier segment est parti")
        assertEquals(1, continuations.countFor(ownerId), "et une attente est en vol")
    }

    @Test
    fun `rien ne repart avant l echeance`() {
        seedSequence("""{"kind":"wait","seconds":30}""")
        val e = engine()
        trame(e)

        now += 29_000
        assertEquals(0, reveiller(e), "vingt-neuf secondes, ce n'est pas trente")
        assertEquals(listOf("Debut"), titres())
    }

    @Test
    fun `a l echeance, la suite part dans l ordre`() {
        seedSequence("""{"kind":"wait","seconds":30}""")
        val e = engine()
        trame(e)

        now += 30_000
        assertEquals(1, reveiller(e))
        assertEquals(listOf("Debut", "Fin"), titres(), "la suite arrive APRES, jamais avant")
        assertEquals(0, continuations.countFor(ownerId), "et la ligne est consommee")
    }

    @Test
    fun `une reprise ne se rejoue pas`() {
        // La cle d'idempotence derive de l'heure du FAIT, pas du reveil. Un
        // reveil rejoue apres un redemarrage frappe l'index unique au lieu
        // d'envoyer une seconde fois.
        seedSequence("""{"kind":"wait","seconds":30}""")
        val e = engine()
        trame(e)
        now += 30_000

        val c = continuations.due(now, limit = 10).single()
        e.resume(c)
        e.resume(c)

        assertEquals(listOf("Debut", "Fin"), titres(), "deux reprises, un seul envoi")
    }

    @Test
    fun `deux attentes font trois segments`() {
        val definition = """{"trigger":{"kind":"signalChanged",""" +
            """"signal":{"projectId":"$projectId","deviceId":"$deviceId","address":0}},""" +
            """"actions":[""" +
            """{"kind":"push","title":"Debut","body":"b"},""" +
            """{"kind":"wait","seconds":10},""" +
            """{"kind":"push","title":"Fin","body":"1"},""" +
            """{"kind":"wait","seconds":10},""" +
            """{"kind":"push","title":"Fin","body":"2"}]}"""
        transaction {
            exec(
                """INSERT INTO automation_rules
                   (id, owner_id, name, enabled, trigger_kind, trigger_signal_key, definition,
                    time_zone_id, schema_version, created_at, updated_at)
                   VALUES ('r1','$ownerId','trois',true,'value','$deviceId:0',
                   '$definition','America/Toronto','v2',0,0)"""
            )
        }
        val e = engine()
        trame(e)
        assertEquals(1, titres().size)

        now += 10_000; reveiller(e)
        assertEquals(2, titres().size, "le deuxieme segment")

        now += 10_000; reveiller(e)
        assertEquals(3, titres().size, "le troisieme")
        assertEquals(0, continuations.countFor(ownerId))
    }

    // ── Ce qui l'arrête ──────────────────────────────────────────────────

    @Test
    fun `eteindre une regle arrete ce qui est deja en vol`() {
        // Sinon « eteins-la » ne veut plus rien dire : la moitie de la
        // sequence partirait quand meme, une heure plus tard.
        seedSequence("""{"kind":"wait","seconds":30}""")
        val e = engine()
        trame(e)

        transaction { exec("UPDATE automation_rules SET enabled=false WHERE id='r1'") }
        val e2 = engine()   // recharge le cache
        now += 30_000
        reveiller(e2)

        assertEquals(listOf("Debut"), titres(), "la suite ne doit pas partir")
    }

    @Test
    fun `deux declenchements font deux sequences independantes`() {
        // Aucune politique de chevauchement : le chevauchement est une
        // propriete du DECLENCHEUR, pas des actions. Inventer un arbitrage
        // reviendrait a decider a la place de celui qui a choisi son
        // declencheur.
        seedSequence("""{"kind":"wait","seconds":30}""")
        val e = engine()
        trame(e, 30.0)
        now += 5_000
        trame(e, 31.0)

        assertEquals(2, continuations.countFor(ownerId), "deux attentes, pas une qui ecrase l'autre")

        now += 30_000
        reveiller(e)
        assertEquals(4, titres().size, "deux sequences completes")
    }

    // ── L'arithmétique de « jusqu'à » ────────────────────────────────────

    @Test
    fun `jusqu a une heure deja passee veut dire demain`() {
        // « Jusqu'a 23 h » ecrit a 23 h 30 ne peut pas vouloir dire « il y a
        // trente minutes ». La seule lecture defendable est la prochaine
        // occurrence.
        val a2330 = ZonedDateTime.of(2026, 6, 10, 23, 30, 0, 0, TORONTO).toInstant().toEpochMilli()
        val du = dueAtOf(Action.Delay.Until(23 * 60), a2330, TORONTO)
        val local = java.time.Instant.ofEpochMilli(du).atZone(TORONTO)

        assertEquals(11, local.dayOfMonth, "demain")
        assertEquals(23, local.hour)
    }

    @Test
    fun `jusqu a une heure a venir veut dire aujourd hui`() {
        val a2000 = ZonedDateTime.of(2026, 6, 10, 20, 0, 0, 0, TORONTO).toInstant().toEpochMilli()
        val du = dueAtOf(Action.Delay.Until(23 * 60), a2000, TORONTO)
        val local = java.time.Instant.ofEpochMilli(du).atZone(TORONTO)

        assertEquals(10, local.dayOfMonth)
        assertEquals(23, local.hour)
    }

    @Test
    fun `l heure est celle de la REGLE, pas celle du serveur`() {
        val minuit = ZonedDateTime.of(2026, 6, 10, 0, 0, 0, 0, ZoneId.of("UTC"))
            .toInstant().toEpochMilli()
        val paris = dueAtOf(Action.Delay.Until(7 * 60), minuit, ZoneId.of("Europe/Paris"))
        val toronto = dueAtOf(Action.Delay.Until(7 * 60), minuit, TORONTO)

        assertEquals(6 * 3600_000L, toronto - paris, "meme heure murale, six heures d'ecart en juin")
    }

    @Test
    fun `une attente traverse le passage a l heure d ete sans disparaitre`() {
        // Le 8 mars 2026 a Toronto, 2 h n'existe pas. Une attente « jusqu'a
        // 2 h 30 » doit tomber ce jour-la, decalee, jamais sautee.
        val avant = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, TORONTO).toInstant().toEpochMilli()
        val du = dueAtOf(Action.Delay.Until(2 * 60 + 30), avant, TORONTO)
        val local = java.time.Instant.ofEpochMilli(du).atZone(TORONTO)

        assertEquals(8, local.dayOfMonth, "le jour du changement, pas le lendemain")
        assertEquals(3, local.hour, "2 h 30 tombe a 3 h 30")
    }

    // ── La borne de ressource ────────────────────────────────────────────

    @Test
    fun `au-dela de la borne, on refuse la sequence entiere`() {
        // On REFUSE plutot que de tronquer : « allume » sans « eteins » laisse
        // le chauffage allume, et personne ne saurait pourquoi.
        val etroit = ExposedContinuationRepository(maxInFlight = 2)
        repeat(2) {
            assertTrue(
                etroit.schedule(ownerId, "r1", now + 1000, listOf(Action.Push("t", "b")), 1, now, null, now)
            )
        }
        assertTrue(
            !etroit.schedule(ownerId, "r1", now + 1000, listOf(Action.Push("t", "b")), 1, now, null, now),
            "la troisieme est refusee"
        )
    }

    @Test
    fun `la borne est PAR COMPTE`() {
        val autre = userRepository.create("voisin", BCrypt.hashpw("secret123", BCrypt.gensalt()), "user", true)
        val etroit = ExposedContinuationRepository(maxInFlight = 1)

        assertTrue(etroit.schedule(ownerId, "r1", now, listOf(Action.Push("t", "b")), 1, now, null, now))
        assertTrue(!etroit.schedule(ownerId, "r1", now, listOf(Action.Push("t", "b")), 1, now, null, now))
        assertTrue(
            etroit.schedule(autre, "r2", now, listOf(Action.Push("t", "b")), 1, now, null, now),
            "le voisin n'y est pour rien"
        )
    }
}
