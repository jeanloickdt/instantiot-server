package com.jeanloickdt.automation.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deux règles innocentes chacune, coupables ensemble : A écrit ce que B
 * regarde, B écrit ce que A regarde. La chaîne est nommée, dans l'ordre.
 */
class RuleChainsTest {
    private val s1 = SignalRef("p", "d", 1)
    private val s2 = SignalRef("p", "d", 2)
    private val s3 = SignalRef("p", "d", 3)
    private val s9 = SignalRef("p", "d", 9)

    private fun rule(watch: SignalRef, vararg write: SignalRef) = RuleLogic(
        trigger = Trigger.SignalChanged(watch),
        actions = write.map { Action.SetSignal(it, TypedValue.Float(1.0)) }
    )

    @Test
    fun `two rules that relay each other are a chain`() {
        val a = rule(watch = s2, write = arrayOf(s1))
        val b = "B" to rule(watch = s1, write = arrayOf(s2))
        assertEquals(listOf("B"), RuleChains.chainsThrough(a, listOf(b)))
    }

    @Test
    fun `three rules in a ring — the path names the two others in order`() {
        val a = rule(watch = s3, write = arrayOf(s1))
        val b = "B" to rule(watch = s1, write = arrayOf(s2))
        val c = "C" to rule(watch = s2, write = arrayOf(s3))
        assertEquals(listOf("B", "C"), RuleChains.chainsThrough(a, listOf(c, b)))
    }

    @Test
    fun `a rule that only alerts closes nothing`() {
        val a = rule(watch = s2, write = arrayOf(s1))
        val alertOnly = "N" to RuleLogic(Trigger.SignalChanged(s1), actions = listOf(Action.Push("t", "b")))
        assertTrue(RuleChains.chainsThrough(a, listOf(alertOnly)).isEmpty())
    }

    @Test
    fun `an open chain is not a loop`() {
        val a = rule(watch = s9, write = arrayOf(s1))
        val b = "B" to rule(watch = s1, write = arrayOf(s2))
        val c = "C" to rule(watch = s2, write = arrayOf(s3))
        assertTrue(RuleChains.chainsThrough(a, listOf(b, c)).isEmpty())
    }

    @Test
    fun `a candidate without writes or without a watched signal cannot chain`() {
        val noWrite = RuleLogic(Trigger.SignalChanged(s1), actions = listOf(Action.Push("t", "b")))
        val b = "B" to rule(watch = s1, write = arrayOf(s1))
        assertTrue(RuleChains.chainsThrough(noWrite, listOf(b)).isEmpty())
    }
}
