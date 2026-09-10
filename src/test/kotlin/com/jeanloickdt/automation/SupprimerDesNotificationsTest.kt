package com.jeanloickdt.automation

import com.jeanloickdt.database.TestDatabase
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Retirer des tirs du fil.
 *
 * ## Ce que ces épreuves gardent
 *
 * Deux propriétés qu'on ne peut pas déduire du code en le lisant.
 *
 * **Un tir système se supprime.** Sa clé porte un `ruleId` NUL, et en SQL une
 * égalité avec NULL est fausse pour tout — y compris pour NULL. Une
 * implémentation naïve laisserait donc les messages système impossibles à
 * effacer, et le bouton mentirait sans rien dire.
 *
 * **Un tir part avec TOUS ses canaux.** Le push et le courriel d'une même
 * alerte sont une seule ligne à l'écran ; n'en retirer qu'un laisserait un
 * demi-tir, qui ne veut rien dire pour qui regarde.
 */
class SupprimerDesNotificationsTest {

    private val repo = ExposedNotificationRepository()
    private val actions = ExposedPendingActionRepository()
    private val now = 1_000_000L

    @BeforeTest
    fun setup() { TestDatabase.connectAndClean() }

    private fun enfile(key: String, ruleId: String?, at: Long, type: String = "PUSH") =
        actions.enqueue(key, "u1", ruleId, type, "{}", occurredAt = at, nowMs = at)

    private fun restants(): List<String> = transaction {
        exec("SELECT idempotency_key FROM pending_actions ORDER BY idempotency_key") { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }!!
    }

    @Test
    fun `un tir systeme se supprime — sa cle porte un ruleId nul`() {
        enfile("sys", null, now)
        enfile("regle", "r1", now)

        assertEquals(1, repo.delete("u1", listOf(null to now)))
        assertEquals(listOf("regle"), restants(), "le tir systeme doit partir, la regle rester")
    }

    @Test
    fun `un tir part avec tous ses canaux`() {
        enfile("push", "r1", now, "PUSH")
        enfile("email", "r1", now, "EMAIL")
        enfile("autre", "r1", now + 1000, "PUSH")

        assertEquals(2, repo.delete("u1", listOf("r1" to now)))
        assertEquals(listOf("autre"), restants(), "les deux canaux du meme tir partent ensemble")
    }

    @Test
    fun `on ne supprime jamais le tir de quelqu un d autre`() {
        // Meme en connaissant sa cle. C'est le meme reflexe que partout : un
        // identifiant ne se croit pas sans son proprietaire.
        transaction {
            exec(
                """INSERT INTO pending_actions
                   (idempotency_key, owner_id, rule_id, type, payload, status,
                    attempts, next_attempt_at, occurred_at, created_at)
                   VALUES ('voisin','u2','r9','PUSH','{}','PENDING',0,$now,$now,$now)"""
            )
        }
        assertEquals(0, repo.delete("u1", listOf("r9" to now)))
        assertEquals(listOf("voisin"), restants())
    }

    @Test
    fun `un lot supprime plusieurs tirs d un coup`() {
        // Une transaction, tout ou rien : vingt appels dont certains
        // echouent obligeraient a decider quoi montrer d'une suppression a
        // moitie faite.
        enfile("a", "r1", now)
        enfile("b", "r2", now + 1)
        enfile("c", null, now + 2)
        enfile("garde", "r3", now + 3)

        assertEquals(3, repo.delete("u1", listOf("r1" to now, "r2" to (now + 1), null to (now + 2))))
        assertEquals(listOf("garde"), restants())
    }
}
