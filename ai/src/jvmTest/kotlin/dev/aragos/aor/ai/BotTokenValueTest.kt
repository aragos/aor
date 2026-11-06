package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.math.abs
import kotlin.test.Test

class BotTokenValueTest {
    private val incumbent = BotModel(weights = mapOf("points" to 1.0))

    @Test
    fun proposalsIncludeSignedAndResourceAlternativesInStableOrder() {
        val observation = observation()
        val baseline = observation.candidates.single { it.command == "buy-tokens 16" }

        val alternatives = BotTokenValue.alternatives(observation, baseline)

        assertThat(alternatives.map(BotCandidate::command))
            .containsExactly(
                "buy-tokens 16",
                "buy-tokens 0",
                "buy-tokens -4",
                "buy-tokens 8",
                "buy-tokens 24",
                "buy-tokens 36",
            )
            .inOrder()
    }

    @Test
    fun proposalsDeduplicateAndStayWithinTheLegalOffers() {
        val observation = observation(cash = 3, preferred = 3)
        val baseline = observation.candidates.single { it.command == "buy-tokens 3" }

        val alternatives = BotTokenValue.alternatives(observation, baseline)

        assertThat(alternatives.map(BotCandidate::command))
            .containsExactly("buy-tokens 3", "buy-tokens 0", "buy-tokens -3", "buy-tokens 1")
            .inOrder()
    }

    @Test
    fun learnedPositiveAdvantageCanOverrideTheIncumbent() {
        val model = fitted("cost" to -1.0)

        val choice = BotPolicy.choose(observation(), BotDifficulty.HARD, 0, model)

        assertThat(choice).isEqualTo("buy-tokens 0")
    }

    @Test
    fun aSignedOrderBenefitCanSelectANegativeBid() {
        val model = fitted("signedAmount" to -1.0, "cost" to -1.0)

        val choice = BotPolicy.choose(observation(), BotDifficulty.HARD, 0, model)

        assertThat(choice).isEqualTo("buy-tokens -4")
    }

    @Test
    fun advantageMustExceedTheFixedMargin() {
        val model = fitted("cost" to -0.08)

        val choice = BotPolicy.choose(observation(), BotDifficulty.HARD, 0, model)

        assertThat(choice).isEqualTo("buy-tokens 16")
    }

    @Test
    fun zeroGatePreservesTheIncumbentEvenWithLargeHeadWeights() {
        val fitted = fitted("cost" to -1000.0)
        val model = fitted.copy(weights = fitted.weights + (BotTokenValue.GATE to 0.0))

        val choice = BotPolicy.choose(observation(), BotDifficulty.HARD, 0, model)

        assertThat(choice).isEqualTo("buy-tokens 16")
    }

    @Test
    fun missingGatePreservesTheIncumbent() {
        val fitted = fitted("cost" to -1000.0)
        val model = fitted.copy(weights = fitted.weights - BotTokenValue.GATE)

        val choice = BotPolicy.choose(observation(), BotDifficulty.HARD, 0, model)

        assertThat(choice).isEqualTo("buy-tokens 16")
    }

    @Test
    fun missingObservationFeaturesPreserveTheIncumbent() {
        val original = observation()
        val incomplete =
            original.copy(
                candidates =
                    original.candidates.map { it.copy(features = it.features - key("cost")) }
            )

        val choice = BotPolicy.choose(incomplete, BotDifficulty.HARD, 0, fitted("cost" to -1.0))

        assertThat(choice).isEqualTo("buy-tokens 16")
    }

    @Test
    fun theHeadDoesNotChangeNormalDifficulty() {
        val choice =
            BotPolicy.choose(observation(), BotDifficulty.NORMAL, 0, fitted("cost" to -1000.0))

        assertThat(choice).isEqualTo("buy-tokens 16")
    }

    @Test
    fun ordinaryScoresExcludeReservedCoefficients() {
        val observation = observation()

        val scores = observation.candidates.map { BotPolicy.score(it, fitted("cost" to -1000.0)) }

        assertThat(scores)
            .containsExactlyElementsIn(
                observation.candidates.map { BotPolicy.score(it, incumbent) }
            )
            .inOrder()
    }

    @Test
    fun reservedCoefficientsCannotLeakIntoAnotherDecisionsBeam() {
        val observation =
            observation()
                .copy(
                    decision = "expand",
                    availableTokens = 1,
                    candidates =
                        listOf(
                            BotCandidate(
                                "place 1 a",
                                mapOf("points" to 1.0, key("cost") to 1.0),
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
        val model =
            incumbent.copy(
                weights =
                    incumbent.weights + mapOf(key("cost") to 1000.0, BotTokenValue.GATE to 0.0)
            )

        val choice = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

        assertThat(choice).isEqualTo("place 1 b")
    }

    @Test
    fun pairedFeaturesSubtractTheIncumbentWithoutAnIntercept() {
        val observation = observation()
        val baseline = observation.candidates.single { it.command == "buy-tokens 16" }
        val alternative = observation.candidates.single { it.command == "buy-tokens 0" }

        val delta = BotTokenValue.difference(observation, alternative, baseline)

        assertThat(delta[BotTokenValue.featureNames.indexOf(key("cost"))]).isEqualTo(-0.25)
        assertThat(BotTokenValue.featureNames).hasSize(39)
        assertThat(
                BotTokenValue.featureNames.take(32).all { it.startsWith("planning.tokenValue.v1.") }
            )
            .isTrue()
        assertThat(BotTokenValue.featureNames.drop(32))
            .containsExactlyElementsIn(BotTokenPlanFeature.entries.map { it.key })
            .inOrder()
    }

    private fun fitted(vararg weights: Pair<String, Double>): BotModel {
        val values = weights.toMap()
        return BotTokenValue.withWeights(
            incumbent,
            BotTokenValue.featureNames.map { values[it.substringAfter(".v1.")] ?: 0.0 },
        )
    }

    private fun key(name: String): String = "planning.tokenValue.v1.$name"

    private fun observation(cash: Int = 100, preferred: Int = 16): BotObservation {
        val limit = minOf(cash, 36)
        return BotObservation(
            playerId = "p1",
            decision = "buy-tokens",
            context = mapOf("stock" to 36.0),
            availableCash = cash,
            availableTokens = 0,
            candidates =
                (-limit..limit).map { amount ->
                    BotCandidate(
                        command = "buy-tokens $amount",
                        features =
                            BotTokenValue.featureNames.associateWith { 0.0 } +
                                mapOf(
                                    key("signedAmount") to amount / 32.0,
                                    key("cost") to abs(amount) / 64.0,
                                    key("received") to amount.coerceAtLeast(0) / 32.0,
                                    "points" to if (amount == preferred) 1.0 else 0.0,
                                ),
                        cashCost = abs(amount),
                    )
                },
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )
    }
}
