package com.jeanloickdt.automation.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Une règle a une taille. Au-delà : refusée à la lecture, avant toute
 * validation, avant toute ligne en base.
 */
class RuleSizeTest {
    private fun rule(actions: String) = """
        {"trigger":{"kind":"signalChanged","signal":{"projectId":"p","deviceId":"d","address":0}},
         "actions":[$actions]}
    """.trimIndent()

    private fun push(body: String = "b") = """{"kind":"push","title":"t","body":"$body"}"""

    @Test
    fun `twenty actions pass, the twenty-first is refused`() {
        assertIs<RuleCodec.Outcome.Ok>(RuleCodec.decode(rule(List(20) { push() }.joinToString(","))))
        val r = RuleCodec.decode(rule(List(21) { push() }.joinToString(",")))
        assertEquals(RuleCodec.E_TOO_LARGE, assertIs<RuleCodec.Outcome.Invalid>(r).code)
    }

    @Test
    fun `a body beyond two thousand characters is refused`() {
        assertIs<RuleCodec.Outcome.Ok>(RuleCodec.decode(rule(push("x".repeat(2_000)))))
        val r = RuleCodec.decode(rule(push("x".repeat(2_001))))
        assertEquals(RuleCodec.E_TOO_LARGE, assertIs<RuleCodec.Outcome.Invalid>(r).code)
    }

    @Test
    fun `a definition beyond sixteen kilobytes is refused before being parsed`() {
        val huge = rule(push("x".repeat(20_000)))
        val r = RuleCodec.decode(huge)
        assertEquals(RuleCodec.E_TOO_LARGE, assertIs<RuleCodec.Outcome.Invalid>(r).code)
    }
}
