package dev.aragos.aor.ai

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TokenAnchoredPolicyTest {
    private val distribution = TokenAnchorDistribution(epsilon = 0.2, temperature = 1.0)

    @Test
    fun zeroOutputReturnsExactSmoothedAnchor() {
        val result = evaluate(listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 0.0))

        assertEquals(listOf(0.2 / 3, 0.8 + 0.2 / 3, 0.2 / 3), result.probabilities)
        assertEquals(result.initialProbabilities, result.probabilities)
    }

    @Test
    fun equalNonzeroOutputsPreserveExactAnchor() {
        val result = evaluate(listOf(0.0, 1.0), listOf(27.0, 27.0))

        assertEquals(listOf(0.1, 0.9), result.probabilities)
    }

    @Test
    fun learnedResidualCanPromoteAnActionWithZeroOriginalAnchorMass() {
        val result = evaluate(listOf(0.0, 1.0), listOf(4.0, 0.0))

        assertTrue(result.probabilities[0] > result.probabilities[1])
        assertTrue(result.probabilities.all { it > 0.0 })
    }

    @Test
    fun residualHasNoLegacyTwentyUnitClip() {
        val result = evaluate(listOf(0.5, 0.5), listOf(21.0, 22.0))

        assertTrue(result.probabilities[1] > 0.7)
    }

    @Test
    fun temperatureDividesOnlyLearnedResidual() {
        val result =
            TokenAnchoredPolicy.probabilities(
                listOf(0.0, 1.0),
                listOf(2.0, 0.0),
                distribution.copy(temperature = 2.0),
                anchorIndex = 1,
            )

        assertEquals(ln(0.1) + 1.0, result.logits[0])
        assertEquals(ln(0.9), result.logits[1])
    }

    @Test
    fun singletonRemainsCertain() {
        val result = evaluate(listOf(1.0), listOf(-31.0))

        assertEquals(listOf(1.0), result.probabilities)
    }

    @Test
    fun numericalUnderflowRejectsActorInsteadOfDroppingAction() {
        assertFailsWith<IllegalArgumentException> {
            evaluate(listOf(0.0, 1.0), listOf(-1000.0, 0.0))
        }
    }

    @Test
    fun invalidAnchorMassIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            evaluate(listOf(0.1, 1.0), listOf(0.0, 0.0))
        }
    }

    @Test
    fun sharedActorSeedSelectsTheRecordedDistribution() {
        val observation = observation()
        val result = evaluate(listOf(0.0, 1.0), listOf(4.0, 0.0))
        val seed = 8123
        val uniform = TerritoryPolicy.uniform(TerritoryPolicy.actorSeed(TerritoryPhase.TOKEN, seed))

        assertEquals(
            TokenAnchoredPolicy.select(observation, result, uniform),
            TokenAnchoredPolicy.choose(observation, result, seed),
        )
    }

    @Test
    fun selectionKeepsLexicalCommandOrderAndReportsExactLogProbability() {
        val observation = observation()
        val result = evaluate(listOf(0.0, 1.0), listOf(0.0, 0.0))

        val choice = TokenAnchoredPolicy.select(observation, result, 0.05)

        assertEquals("buy-tokens 10", choice.command)
        assertEquals(ln(result.probabilities[0]), choice.logProbability)
        assertEquals(1, observation.baselineIndex)
    }

    private fun evaluate(anchor: List<Double>, residual: List<Double>): TokenAnchoredEvaluation =
        TokenAnchoredPolicy.probabilities(
            anchor,
            residual,
            distribution,
            anchorIndex = anchor.indexOfFirst { it == anchor.max() },
        )

    private fun observation() =
        TerritoryObservation(
            TerritoryEncoder.TOKEN_2.version,
            emptyMap(),
            listOf(
                TerritoryAction("buy-tokens 10", emptyMap()),
                TerritoryAction("buy-tokens 2", emptyMap()),
            ),
            baselineIndex = 1,
        )
}
