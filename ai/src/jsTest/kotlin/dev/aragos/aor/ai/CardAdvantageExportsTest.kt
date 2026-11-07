package dev.aragos.aor.ai

import kotlin.js.JSON
import kotlin.math.abs
import kotlin.math.atanh
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardAdvantageExportsTest {
    @Test
    fun nonzeroNetworkMatchesTheIndependentOracleAcrossTheBoundary() {
        val result = JSON.parse<dynamic>(evaluateCardAdvantage(observation(), model(), 17))

        for (member in 0..2) {
            assertTrue(
                abs((result.memberValues[member][0] as Double) - 0.47323910035471933) < 1.0e-12
            )
            assertTrue(
                abs((result.memberValues[member][1] as Double) - 0.6155699600507891) < 1.0e-12
            )
        }
        assertTrue(abs((result.meanAdvantages[1] as Double) - 0.1423308596960698) < 1.0e-12)
        assertEquals("play held", result.command as String)
        assertTrue(result.overridden as Boolean)
    }

    @Test
    fun zeroHeadsHaveNoRecommendationPriorAndRetainFExactly() {
        val input = observation().replace("\"baselineIndex\":0", "\"baselineIndex\":1")
        val result = JSON.parse<dynamic>(evaluateCardAdvantage(input, model(output = 0.0), 17))

        assertEquals("play held", result.command as String)
        assertFalse(result.overridden as Boolean)
        assertEquals(0.0, result.meanAdvantages[0] as Double)
        assertEquals(0.0, result.meanAdvantages[1] as Double)
    }

    @Test
    fun highestMeanVetoDoesNotTryAnotherUnanimousProposal() {
        val input =
            """{
            "encoderVersion":"aor-card-observation-1","baselineIndex":0,
            "stateFeatures":{"cardWindow.normal":1},
            "actions":[{"command":"pass","features":{}},
                {"command":"play first","features":{"left":1}},
                {"command":"play second","features":{"right":1}}]
        }"""
        val low = valueMember(0.4, 0.54)
        val high = valueMember(0.8, 0.54)
        val result =
            JSON.parse<dynamic>(evaluateCardAdvantage(input, bundle(listOf(low, high, high)), 17))

        assertEquals(1, result.winnerIndex as Int)
        assertEquals(0, result.index as Int)
        assertEquals("pass", result.command as String)
    }

    @Test
    fun finalAndMissingWindowMetadataUseFWithoutScoring() {
        for (state in listOf("\"cardWindow.final\":1", "\"unknown\":1")) {
            val input = observation().replace("\"cardWindow.normal\":1,\"root\":1", state)
            val result = JSON.parse<dynamic>(evaluateCardAdvantage(input, model(), 17))

            assertFalse(result.supported as Boolean)
            assertEquals("pass", result.command as String)
            assertEquals(null, result.memberValues)
        }
    }

    @Test
    fun malformedVersionsAndModelShapesCannotCrossTheBoundary() {
        assertFailsWith<IllegalArgumentException> {
            evaluateCardAdvantage(
                observation(),
                model().replace("aor-card-advantage-1", "aor-card-policy-1"),
                17,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            evaluateCardAdvantage(
                observation(),
                model().replace("\"margin\":0.02", "\"margin\":0.03"),
                17,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            evaluateCardAdvantage(observation(), bundle(listOf(member())), 17)
        }
        assertFailsWith<IllegalArgumentException> {
            evaluateCardAdvantage(
                observation(),
                model().replace("\"schemaVersion\":1", "\"schemaVersion\":true"),
                17,
            )
        }
    }

    @Test
    fun fractionalBaselineIndicesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            evaluateCardAdvantage(
                observation().replace("\"baselineIndex\":0", "\"baselineIndex\":0.5"),
                model(),
                17,
            )
        }
    }

    @Test
    fun unknownZeroFeaturesAreNotSilentlyDiscarded() {
        assertFailsWith<IllegalStateException> {
            evaluateCardAdvantage(observation().replace("\"root\":1", "\"unknown\":0"), model(), 17)
        }
    }

    @Test
    fun allSignedProposalGoldensMatchTheJvmAndPythonOracle() {
        val expected =
            mapOf(
                0 to listOf(0, 3, 5, 8),
                1 to listOf(0, 2, 5, 9),
                2 to listOf(0, 3, 5, 6),
                17 to listOf(0, 5, 8, 9),
                -1 to listOf(0, 2, 3, 5),
                Int.MIN_VALUE to listOf(0, 1, 3, 5),
                Int.MAX_VALUE to listOf(0, 2, 5, 9),
                -979738860 to listOf(0, 2, 5, 9),
            )

        expected.forEach { (seed, indices) ->
            val result = JSON.parse<Array<Int>>(cardProposalIndices(proposalObservation(), seed))
            assertEquals(indices, result.toList())
        }
    }

    @Test
    fun frequencyControlSharesProposalAndSeedGoldens() {
        val expected =
            mapOf(
                0 to 0,
                1 to 0,
                2 to 3,
                17 to 9,
                -1 to 0,
                Int.MIN_VALUE to 3,
                Int.MAX_VALUE to 9,
                -979738860 to 2,
            )

        expected.forEach { (seed, index) ->
            val result =
                JSON.parse<dynamic>(
                    chooseCardFrequency(proposalObservation(), frequencyModel(), seed)
                )
            assertEquals(index, result.index as Int)
        }
    }

    @Test
    fun unavailableFrequencyFamilyFallsBackWithoutResampling() {
        val input = observation().replace("\"command\":\"pass\"", "\"command\":\"play first\"")
        val result =
            JSON.parse<dynamic>(
                chooseCardFrequency(input, frequencyModel(pass = 1.0, other = 0.0), 17)
            )

        assertEquals("PASS", result.requestedFamily as String)
        assertEquals(0, result.index as Int)
        assertFalse(result.overridden as Boolean)
    }

    @Test
    fun invalidFrequencyMassIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            chooseCardFrequency(observation(), frequencyModel(pass = 0.8, other = 0.8), 17)
        }
    }

    @Test
    fun unexpectedBundleMemberAndFrequencyFieldsAreRejected() {
        val extraBundle =
            model().replace("\"margin\":0.02", "\"margin\":0.02,\"baselineProbability\":0.97")
        val extraMember =
            model().replace("\"outputBias\":-0.3", "\"outputBias\":-0.3,\"valueBias\":0")
        val extraFrequency =
            frequencyModel()
                .replace("\"passProbability\":0.25", "\"passProbability\":0.25,\"unknown\":0")

        assertFailsWith<IllegalArgumentException> {
            evaluateCardAdvantage(observation(), extraBundle, 17)
        }
        assertFailsWith<IllegalArgumentException> {
            evaluateCardAdvantage(observation(), extraMember, 17)
        }
        assertFailsWith<IllegalArgumentException> {
            chooseCardFrequency(observation(), extraFrequency, 17)
        }
    }

    private fun observation(): String =
        """{
        "encoderVersion":"aor-card-observation-1","baselineIndex":0,
        "stateFeatures":{"cardWindow.normal":1,"root":1},
        "actions":[{"command":"pass","features":{"right":1}},
                   {"command":"play held","features":{"left":1}}]
    }"""

    private fun proposalObservation(): String {
        val commands =
            listOf(
                "pass",
                "play hand-a",
                "play hand-a protect 0",
                "play hand-b",
                "play hand-c",
                "play hand-c protect 5",
                "play hand-d",
                "play hand-d player p2",
                "play hand-d player p3",
                "play hand-e",
            )
        return """{"encoderVersion":"aor-card-observation-1","baselineIndex":5,
            "stateFeatures":{"cardWindow.normal":1},
            "actions":[${commands.joinToString { """{"command":"$it","features":{}}""" }}]}"""
    }

    private fun model(output: Double = 1.2): String = bundle(List(3) { member(output) })

    private fun bundle(members: List<String>): String =
        """{
        "schemaVersion":1,"encoderVersion":"aor-card-observation-1",
        "selectorVersion":"aor-card-advantage-1","proposalVersion":"aor-card-proposals-1",
        "hiddenSize":64,"margin":0.02,
        "stateFeatureNames":["cardWindow.normal","root"],
        "actionFeatureNames":["left","right"],"members":[${members.joinToString()}]
    }"""

    private fun member(output: Double = 1.2): String =
        """{
        "stateWeights":${vector(128, mapOf(1 to 0.3))},
        "stateBias":${vector(values = mapOf(0 to 0.2))},
        "actionWeights":${vector(128, mapOf(0 to 0.4, 1 to -0.2))},
        "actionBias":${vector(values = mapOf(0 to -0.1))},
        "outputWeights":${vector(values = mapOf(0 to output))},"outputBias":-0.3
    }"""

    private fun valueMember(left: Double, right: Double): String =
        """{
        "stateWeights":${vector(128)},"stateBias":${vector()},
        "actionWeights":${vector(128, mapOf(0 to atanh(ln(left / (1.0 - left)) / 2.0), 1 to atanh(ln(right / (1.0 - right)) / 2.0)))},
        "actionBias":${vector()},"outputWeights":${vector(values = mapOf(0 to 2.0))},"outputBias":0
    }"""

    private fun frequencyModel(pass: Double = 0.25, other: Double = 0.5): String =
        """{
        "schemaVersion":1,"encoderVersion":"aor-card-observation-1",
        "selectorVersion":"aor-card-frequency-1","proposalVersion":"aor-card-proposals-1",
        "passProbability":$pass,"otherProbability":$other
    }"""

    private fun vector(size: Int = 64, values: Map<Int, Double> = emptyMap()): String =
        List(size) { values[it] ?: 0.0 }.joinToString(prefix = "[", postfix = "]")
}
