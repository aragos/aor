package dev.aragos.aor.ai

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IncumbentResidualPolicyTest {
    private fun observation(encoder: TerritoryEncoder = TerritoryEncoder.EXPANSION_2) =
        TerritoryObservation(
            encoder.version,
            mapOf("state" to 1.0),
            listOf(
                TerritoryAction("a", mapOf("action" to -1.0)),
                TerritoryAction("b", mapOf("action" to 1.0)),
                TerritoryAction("c", mapOf("action" to 0.5)),
            ),
            1,
        )

    private fun model(encoder: TerritoryEncoder = TerritoryEncoder.EXPANSION_2) =
        TerritoryPolicyModel(
            schemaVersion = if (encoder == TerritoryEncoder.EXPANSION_2) 2 else 3,
            encoderVersion = encoder.version,
            stateFeatureNames = listOf("state"),
            actionFeatureNames = listOf("action"),
            stateWeights = List(64) { if (it == 0) 1.0 else 0.0 },
            stateBias = List(64) { 0.0 },
            actionWeights = List(64) { if (it == 0) 1.0 else 0.0 },
            actionBias = List(64) { 0.0 },
            actorWeights = List(64) { 0.0 },
            valueWeights = List(64) { if (it == 0) .25 else 0.0 },
            valueBias = -.125,
        )

    @Test
    fun unchangedExpansionReturnsExactReferenceWithoutSampling() {
        val original = model()
        val result =
            IncumbentResidualPolicy.choose(
                observation(),
                original,
                original,
                IncumbentResidualSelector(32.0),
            )
        assertEquals("b", result.command)
        assertEquals(listOf(0.0, 0.0, 0.0), result.residualDifferences)
        assertEquals(listOf(0.0, 1.0, 0.0), result.scores)
    }

    @Test
    fun unchangedTokenReturnsExactReference() {
        val original = model(TerritoryEncoder.TOKEN_2)
        assertEquals(
            "b",
            IncumbentResidualPolicy.choose(
                    observation(TerritoryEncoder.TOKEN_2),
                    original,
                    original,
                    IncumbentResidualSelector(1.0 / 32),
                )
                .command,
        )
    }

    @Test
    fun exactMaximumTiePrefersIncumbent() {
        assertEquals(
            1,
            IncumbentResidualPolicy.select(
                    observation(),
                    listOf(1.0, 0.0, 1.0),
                    IncumbentResidualSelector(1.0),
                )
                .index,
        )
    }

    @Test
    fun nonincumbentTieUsesLowestOriginalIndex() {
        assertEquals(
            0,
            IncumbentResidualPolicy.select(
                    observation(),
                    listOf(2.0, 0.0, 2.0),
                    IncumbentResidualSelector(1.0),
                )
                .index,
        )
    }

    @Test
    fun floatingPointScoreTieKeepsReferenceEvenWhenScaledGapExceedsMargin() {
        val reference = 18014398509481984.0
        val differences = listOf(reference + 4.0, reference, reference)
        assertTrue(.3 * (differences[0] - differences[1]) > 1.0)
        val choice =
            IncumbentResidualPolicy.select(
                observation(),
                differences,
                IncumbentResidualSelector(.3),
            )
        assertEquals(choice.scores[0], choice.scores[1])
        assertEquals(1, choice.index)
    }

    @Test
    fun multiplierPrecedesFixedMargin() {
        assertEquals(
            listOf(2.0, 1.0, 0.0),
            IncumbentResidualPolicy.select(
                    observation(),
                    listOf(1.0, 0.0, 0.0),
                    IncumbentResidualSelector(2.0),
                )
                .scores,
        )
    }

    @Test
    fun rawResidualDoesNotInheritLegacyClipOrPrior() {
        val original = model()
        val current = original.copy(actorWeights = List(64) { if (it == 0) 200.0 else 0.0 })
        val differences = IncumbentResidualPolicy.differences(observation(), current, original)
        val legacy = TerritoryPolicy.evaluate(observation(), current)
        assertTrue(differences[1] > 100.0)
        assertEquals(20.0, legacy.logits[1] - ln(.97), 1e-12)
    }

    @Test
    fun strongestGapIsSignedRelativeToIncumbent() {
        assertEquals(-1.0, IncumbentResidualPolicy.strongestGap(listOf(9.0, 10.0, -100.0), 1))
    }

    @Test
    fun singletonHasNoGapAndKeepsItsOnlyCommand() {
        val singleton =
            observation().copy(actions = listOf(observation().actions[1]), baselineIndex = 0)
        assertEquals(null, IncumbentResidualPolicy.strongestGap(listOf(7.0), 0))
        assertEquals(
            "b",
            IncumbentResidualPolicy.select(singleton, listOf(7.0), IncumbentResidualSelector(1.0))
                .command,
        )
    }

    @Test
    fun changedValueArraysAreRejected() {
        val original = model()
        assertFailsWith<IllegalArgumentException> {
            IncumbentResidualPolicy.differences(
                observation(),
                original.copy(valueBias = .5),
                original,
            )
        }
    }

    @Test
    fun nonfiniteDifferencesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            IncumbentResidualPolicy.select(
                observation(),
                listOf(0.0, Double.NaN, 0.0),
                IncumbentResidualSelector(1.0),
            )
        }
    }

    @Test
    fun legacyTerritoryContainerIsRejected() {
        val original =
            model().copy(schemaVersion = 1, encoderVersion = TerritoryEncoder.EXPANSION_1.version)
        assertFailsWith<IllegalArgumentException> {
            IncumbentResidualPolicy.differences(
                observation().copy(encoderVersion = TerritoryEncoder.EXPANSION_1.version),
                original,
                original,
            )
        }
    }

    @Test
    fun malformedSelectorCannotExecute() {
        listOf(0.0, Double.NaN, 33.0).forEach { scale ->
            assertFailsWith<IllegalArgumentException> { IncumbentResidualSelector(scale) }
        }
        assertFailsWith<IllegalArgumentException> { IncumbentResidualSelector(1.0, margin = 0.0) }
    }
}
