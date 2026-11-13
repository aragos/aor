package dev.aragos.aor.ai

import kotlin.js.JSON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExpansionExportsTest {
    @Test
    fun serializedSchemaTwoMatchesTheNonzeroCommonOracle() {
        val result =
            JSON.parse<dynamic>(evaluateExpansionPolicy(projection(), model(), "left", .999999))

        assertEquals(.740327573777746, purchaseNumber(result.logits[0]), 1e-12)
        assertEquals(-3.3137038836953616, purchaseNumber(result.logits[1]), 1e-12)
        assertEquals(.9829436874186683, purchaseNumber(result.probabilities[0]), 1e-12)
        assertEquals(.5174163824291889, purchaseNumber(result.value), 1e-12)
        assertEquals("right", purchaseString(result.command))
    }

    @Test
    fun decoderRejectsExtraProjectionAuthorityFields() {
        val raw = JSON.parse<dynamic>(projection())
        raw.authority = js("({deck: ['not-an-input']})")

        assertFailsWith<IllegalArgumentException> { readExpansionProjection(JSON.stringify(raw)) }
    }

    @Test
    fun decoderRejectsEmbeddedBaselineIndices() {
        val raw = JSON.parse<dynamic>(projection())
        raw.baselineIndex = 0

        assertFailsWith<IllegalArgumentException> { readExpansionProjection(JSON.stringify(raw)) }
    }

    @Test
    fun decoderRejectsActionOutcomes() {
        val raw = JSON.parse<dynamic>(projection())
        raw.actions[0].reward = .9

        assertFailsWith<IllegalArgumentException> { readExpansionProjection(JSON.stringify(raw)) }
    }

    @Test
    fun decoderRejectsUndeclaredModelFields() {
        val raw = JSON.parse<dynamic>(model())
        raw.trainingRows = js("[]")

        assertFailsWith<IllegalArgumentException> { readExpansionModel(raw) }
    }

    @Test
    fun decoderRejectsNonExpansionSchemaPairs() {
        val raw = JSON.parse<dynamic>(model())
        raw.encoderVersion = TerritoryEncoder.TOKEN_2.version

        assertFailsWith<IllegalArgumentException> { readExpansionModel(raw) }
    }

    @Test
    fun decoderRejectsUnknownPublicFeatureNamesAtInference() {
        val raw = JSON.parse<dynamic>(projection())
        raw.stateFeatures.unknown = 1

        assertFailsWith<IllegalStateException> {
            evaluateExpansionPolicy(JSON.stringify(raw), model(), "left", 0.0)
        }
    }

    @Test
    fun completeMaximumOfferSetIsNotTruncated() {
        val raw = JSON.parse<dynamic>(projection())
        raw.actions =
            Array<Any?>(TerritoryPhase.EXPANSION.maxActions) { index ->
                val action = js("({})")
                action.command = "action ${index.toString().padStart(4, '0')}"
                action.features = js("({})")
                action
            }
        val parsed = readExpansionProjection(JSON.stringify(raw))

        assertEquals(TerritoryPhase.EXPANSION.maxActions, parsed.actions.size)
        assertEquals("action 1023", parsed.actions.last().command)
    }

    @Test
    fun offeredReferenceIsRequiredBeforeSampling() {
        assertFailsWith<IllegalArgumentException> {
            evaluateExpansionPolicy(projection(), model(), "absent", 0.0)
        }
    }

    private fun projection() =
        """{"encoderVersion":"aor-expansion-observation-2","stateFeatures":{"root":1},
        "actions":[{"command":"left","features":{"left":1}},
        {"command":"right","features":{"right":1}}]}"""

    private fun vector(values: List<Double>) = values.joinToString(prefix = "[", postfix = "]")

    private fun model(): String {
        val stateWeights = List(192) { if (it == 2) .3 else 0.0 }
        val stateBias = List(64) { if (it == 0) .2 else 0.0 }
        val actionWeights =
            List(192) {
                when (it) {
                    0 -> .1
                    1 -> .4
                    2 -> -.2
                    else -> 0.0
                }
            }
        val actionBias = List(64) { if (it == 0) -.1 else 0.0 }
        val actorWeights = List(64) { if (it == 0) 1.2 else 0.0 }
        val valueWeights = List(64) { if (it == 0) .8 else 0.0 }
        return """{"schemaVersion":2,"encoderVersion":"aor-expansion-observation-2",
        "hiddenSize":64,"baselineProbability":0.97,
        "stateFeatureNames":["after-left","after-right","root"],
        "actionFeatureNames":["cost","left","right"],
        "stateWeights":${vector(stateWeights)},"stateBias":${vector(stateBias)},
        "actionWeights":${vector(actionWeights)},"actionBias":${vector(actionBias)},
        "actorWeights":${vector(actorWeights)},"valueWeights":${vector(valueWeights)},
        "valueBias":-0.3}"""
    }
}
