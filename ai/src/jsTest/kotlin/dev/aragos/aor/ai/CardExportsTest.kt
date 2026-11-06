package dev.aragos.aor.ai

import kotlin.js.JSON
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CardExportsTest {
    @Test
    fun nonzeroNetworkMatchesTheIndependentFloat64GoldenAcrossTheJsonBoundary() {
        val result = JSON.parse<dynamic>(evaluateCardPolicy(observation(), model(), 0.999999))

        assertTrue(abs((result.logits[0] as Double) - 0.740327573777746) < 1.0e-12)
        assertTrue(abs((result.logits[1] as Double) + 3.3137038836953616) < 1.0e-12)
        assertTrue(abs((result.probabilities[0] as Double) - 0.9829436874186683) < 1.0e-12)
        assertTrue(abs((result.probabilities[1] as Double) - 0.01705631258133161) < 1.0e-12)
        assertTrue(abs((result.value as Double) - 0.5174163824291889) < 1.0e-12)
        assertEquals("right", result.command as String)
        assertEquals(1, result.index as Int)
    }

    @Test
    fun zeroResidualPreservesExactPriorAndCumulativeBoundary() {
        val result =
            JSON.parse<dynamic>(evaluateCardPolicy(observation(), model(actor = 0.0), 0.97))

        assertEquals(0.97, result.probabilities[0] as Double)
        assertEquals(0.03, result.probabilities[1] as Double)
        assertEquals("right", result.command as String)
    }

    @Test
    fun unknownFeatureNamesAreRejectedEvenWhenTheirValueIsZero() {
        val input = observation().replace("\"root\":1", "\"unseen\":0")

        assertFailsWith<IllegalStateException> { evaluateCardPolicy(input, model(), 0.0) }
    }

    @Test
    fun fractionalBaselineIndicesAreNotSilentlyTruncated() {
        val input = observation().replace("\"baselineIndex\":0", "\"baselineIndex\":0.5")

        assertFailsWith<IllegalArgumentException> { evaluateCardPolicy(input, model(), 0.0) }
    }

    @Test
    fun booleansCannotImpersonateModelVersionNumbers() {
        val invalid = model().replace("\"schemaVersion\":1", "\"schemaVersion\":true")

        assertFailsWith<IllegalArgumentException> {
            evaluateCardPolicy(observation(), invalid, 0.0)
        }
    }

    @Test
    fun signedSeedMixerMatchesJvmAndTheIndependentNumericalOracle() {
        val expected =
            mapOf(
                0 to 0.0,
                1 to 0.3175988623406738,
                2 to 0.19123476883396506,
                17 to 0.8469332996755838,
                -1 to 0.5075902475509793,
                Int.MIN_VALUE to 0.4267028346657753,
                Int.MAX_VALUE to 0.975769916549325,
                -979738860 to 0.558833003975451,
            )

        expected.forEach { (seed, uniform) -> assertEquals(uniform, CardPolicy.uniform(seed)) }
    }

    @Test
    fun purchaseEncoderCannotBeAcceptedByTheCardBoundary() {
        val invalid = model().replace("aor-card-observation-1", "aor-purchase-observation-1")
        assertFailsWith<IllegalArgumentException> {
            evaluateCardPolicy(observation(), invalid, 0.0)
        }
    }

    @Test
    fun cardSamplerUsesTheDistinctFrozenSaltInJavascript() {
        assertEquals(1013904242, CardPolicy.actorSeed(0))
        assertEquals(17 xor 0x3c6ef372, CardPolicy.actorSeed(17))
    }

    private fun observation(): String =
        """{
        "encoderVersion":"aor-card-observation-1",
        "stateFeatures":{"root":1},"baselineIndex":0,
        "actions":[{"command":"left","features":{"left":1}},
                   {"command":"right","features":{"right":1}}]
    }"""

    private fun vector(size: Int = 64, values: Map<Int, Double> = emptyMap()): String =
        List(size) { values[it] ?: 0.0 }.joinToString(prefix = "[", postfix = "]")

    private fun model(actor: Double = 1.2): String =
        """{
        "schemaVersion":1,"encoderVersion":"aor-card-observation-1",
        "hiddenSize":64,"baselineProbability":0.97,
        "stateFeatureNames":["after-left","after-right","root"],
        "actionFeatureNames":["cost","left","right"],
        "stateWeights":${vector(192, mapOf(2 to 0.3))},
        "stateBias":${vector(values = mapOf(0 to 0.2))},
        "actionWeights":${vector(192, mapOf(0 to 0.1, 1 to 0.4, 2 to -0.2))},
        "actionBias":${vector(values = mapOf(0 to -0.1))},
        "actorWeights":${vector(values = mapOf(0 to actor))},
        "valueWeights":${vector(values = mapOf(0 to 0.8))},"valueBias":-0.3
    }"""
}
