package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Identical fixtures run on JVM and JS to protect incumbent parity across both controllers. */
class BotTokenCorrectionTest {
    private val incumbent =
        BotTokenValue.withWeights(
            BotModel(weights = mapOf("points" to 1.0)),
            BotTokenValue.featureNames.map {
                if (it == BotTokenPlanFeature.HELD_COMMODITY.key) 1.0 else 0.0
            },
        )

    @Test
    fun missingCorrectionRetainsTheEnabledIncumbentHead() {
        assertEquals("buy-tokens 24", choose(incumbent))
    }

    @Test
    fun zeroCoefficientsRetainTheEnabledIncumbentHead() {
        assertEquals("buy-tokens 24", choose(corrected()))
    }

    @Test
    fun disabledCorrectionRetainsTheEnabledIncumbentHead() {
        val fitted = corrected("v1.cost" to -1000.0)
        val model = fitted.copy(weights = fitted.weights + (BotTokenCorrection.GATE to 0.0))

        assertEquals("buy-tokens 24", choose(model))
    }

    @Test
    fun absentGateRetainsTheEnabledIncumbentHeadDespiteCorrectionWeights() {
        val fitted = corrected("v1.cost" to -1000.0)

        assertEquals(
            "buy-tokens 24",
            choose(fitted.copy(weights = fitted.weights - BotTokenCorrection.GATE)),
        )
    }

    @Test
    fun correctionProposalsAreAnchoredAtTheActualIncumbentAction() {
        val model = corrected("v2.plan.publicCommodity" to 1.0)

        // Twelve is half the strong incumbent's 24; it is absent from ordinary 16's proposals.
        assertEquals("buy-tokens 12", choose(model))
    }

    @Test
    fun correctionMarginSubtractsActualIncumbentFeatures() {
        val observation = observation(correctionBenefit = 0.515)
        val model = corrected("v2.plan.publicCommodity" to 1.0)

        // The apparent absolute value is .515, but improvement over incumbent 24 is only .015.
        assertEquals("buy-tokens 24", choose(model, observation))
    }

    @Test
    fun negativeAlternativePredictionsFallBackToTheStrongIncumbent() {
        assertEquals("buy-tokens 24", choose(corrected("v2.plan.heldCommodity" to 1.0)))
    }

    @Test
    fun correctionsOnlySelectOfferedCommands() {
        val observation =
            observation().let {
                it.copy(
                    candidates =
                        it.candidates.filter { candidate ->
                            candidate.command in
                                setOf("buy-tokens 16", "buy-tokens 24", "buy-tokens 5")
                        }
                )
            }
        val selected = choose(corrected("v1.cost" to -1.0), observation)

        assertEquals("buy-tokens 5", selected)
        assertTrue(observation.candidates.any { it.command == selected })
    }

    @Test
    fun fittingPreservesAllIncumbentWeightsAndIndependentGates() {
        val base =
            incumbent.copy(
                weights =
                    incumbent.weights +
                        mapOf(
                            BotTokenValue.COMPLETE_AMOUNTS_GATE to 1.0,
                            BotTokenValue.PEACEFUL_MARGIN_GATE to 1.0,
                        )
            )

        val fitted =
            BotTokenCorrection.withWeights(base, List(BotTokenValue.featureNames.size) { 0.25 })

        assertEquals(base.weights, fitted.weights.filterKeys { !BotTokenCorrection.reserved(it) })
        assertEquals(1.0, fitted.weights[BotTokenCorrection.GATE])
    }

    @Test
    fun incompleteFeaturesRetainTheIncumbentPolicyFallback() {
        val observation =
            observation().let {
                it.copy(
                    candidates =
                        it.candidates.map { candidate ->
                            candidate.copy(
                                features = candidate.features - BotTokenValue.featureNames.last()
                            )
                        }
                )
            }

        assertEquals(
            choose(incumbent, observation),
            choose(corrected("v1.cost" to -1000.0), observation),
        )
    }

    @Test
    fun correctionDoesNotChangeOtherDifficulties() {
        for (difficulty in listOf(BotDifficulty.EASY, BotDifficulty.NORMAL)) {
            assertEquals(
                BotPolicy.choose(observation(), difficulty, 3, incumbent),
                BotPolicy.choose(observation(), difficulty, 3, corrected("v1.cost" to -1000.0)),
            )
        }
    }

    @Test
    fun reservedCorrectionFeaturesCannotChangeAnotherDecisionsBeam() {
        val feature = BotTokenCorrection.coefficientNames.first()
        val observation =
            observation()
                .copy(
                    decision = "expand",
                    availableTokens = 1,
                    candidates =
                        listOf(
                            BotCandidate(
                                "place 1 a",
                                mapOf("points" to 1.0, feature to 1.0),
                                tokenCost = 1,
                                group = "a",
                                family = "expansion",
                            ),
                            BotCandidate(
                                "place 1 b",
                                mapOf("points" to 10.0),
                                tokenCost = 1,
                                group = "b",
                                family = "expansion",
                            ),
                        ),
                )
        val model = corrected("v1.signedAmount" to 1000.0)

        assertEquals("place 1 b", choose(model, observation))
        assertEquals(1.0, BotPolicy.score(observation.candidates.first(), model))
    }

    @Test
    fun coefficientOwnerRejectsIncompleteAndNonfiniteVectors() {
        assertFailsWith<IllegalArgumentException> {
            BotTokenCorrection.withWeights(incumbent, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            BotTokenCorrection.withWeights(
                incumbent,
                List(BotTokenValue.featureNames.size) { Double.NaN },
            )
        }
    }

    private fun corrected(vararg weights: Pair<String, Double>): BotModel {
        val values = weights.toMap()
        return BotTokenCorrection.withWeights(
            incumbent,
            BotTokenValue.featureNames.map {
                values[it.removePrefix("planning.tokenValue.")] ?: 0.0
            },
        )
    }

    private fun choose(model: BotModel, observation: BotObservation = observation()): String =
        BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

    private fun observation(correctionBenefit: Double = 1.0): BotObservation =
        BotObservation(
            playerId = "p1",
            decision = "buy-tokens",
            context = mapOf("stock" to 36.0),
            availableCash = 100,
            availableTokens = 0,
            candidates =
                (-36..36).map { amount ->
                    BotCandidate(
                        command = "buy-tokens $amount",
                        features =
                            BotTokenValue.featureNames.associateWith { 0.0 } +
                                mapOf(
                                    "planning.tokenValue.v1.signedAmount" to amount / 32.0,
                                    "planning.tokenValue.v1.cost" to abs(amount) / 64.0,
                                    BotTokenPlanFeature.HELD_COMMODITY.key to
                                        if (amount == 24) 1.0 else 0.0,
                                    BotTokenPlanFeature.PUBLIC_COMMODITY.key to
                                        when (amount) {
                                            24 -> 0.5
                                            12 -> correctionBenefit
                                            else -> 0.0
                                        },
                                    "points" to if (amount == 16) 1.0 else 0.0,
                                ),
                        cashCost = abs(amount),
                    )
                },
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )
}
