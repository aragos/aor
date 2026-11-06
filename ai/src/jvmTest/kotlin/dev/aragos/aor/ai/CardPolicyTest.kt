package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardPolicyTest {
    private val observation =
        CardObservation(
            stateFeatures = mapOf("root" to 1.0),
            actions =
                listOf(
                    CardAction("left", mapOf("left" to 1.0)),
                    CardAction("right", mapOf("right" to 1.0)),
                ),
            baselineIndex = 0,
        )

    @Test
    fun zeroResidualExactlyPreservesTheQualifiedRecommendationPrior() {
        val result = CardPolicy.evaluate(observation, model().copy(actorWeights = zeros()))

        assertThat(result.probabilities).containsExactly(0.97, 0.03).inOrder()
    }

    @Test
    fun everyAlternativeRetainsItsShareWhenTheRecommendedIndexMoves() {
        val input =
            observation.copy(
                actions = observation.actions + CardAction("third", emptyMap()),
                baselineIndex = 1,
            )

        val result = CardPolicy.evaluate(input, model().copy(actorWeights = zeros()))

        assertThat(result.probabilities).containsExactly(0.015, 0.97, 0.015).inOrder()
    }

    @Test
    fun nonzeroForwardPassMatchesTheIndependentNumericalOracle() {
        val result = CardPolicy.evaluate(observation, model())

        assertThat(result.logits[0]).isWithin(1.0e-12).of(0.740327573777746)
        assertThat(result.logits[1]).isWithin(1.0e-12).of(-3.3137038836953616)
        assertThat(result.probabilities[0]).isWithin(1.0e-12).of(0.9829436874186683)
        assertThat(result.probabilities[1]).isWithin(1.0e-12).of(0.01705631258133161)
        assertThat(result.value).isWithin(1.0e-12).of(0.5174163824291889)
    }

    @Test
    fun suppliedUniformSamplesTheSameDistributionThatItReports() {
        val choice = CardPolicy.choose(observation, model(), 0.999999)

        assertThat(choice.index).isEqualTo(1)
        assertThat(choice.command).isEqualTo("right")
        assertThat(kotlin.math.exp(choice.logProbability)).isWithin(1.0e-12).of(0.01705631258133161)
    }

    @Test
    fun cumulativeProbabilityBoundariesUseTheNextAction() {
        val choice = CardPolicy.choose(observation, model().copy(actorWeights = zeros()), 0.97)

        assertThat(choice.command).isEqualTo("right")
    }

    @Test
    fun oneOfferedActionHasUnitProbabilityAndZeroLogProbability() {
        val input = observation.copy(actions = observation.actions.take(1))

        val choice = CardPolicy.choose(input, model(), 0.999999)

        assertThat(choice.index).isEqualTo(0)
        assertThat(choice.logProbability).isEqualTo(0.0)
    }

    @Test
    fun extremeFiniteResidualsStillGiveEveryLegalActionPositiveSupport() {
        val result =
            CardPolicy.evaluate(
                observation,
                model()
                    .copy(
                        actorWeights = List(64) { 1000.0 },
                        actionWeights =
                            List(192) { index -> if (index % 3 == 1) 1000.0 else -1000.0 },
                    ),
            )

        assertThat(result.probabilities.all { it > 0.0 && it.isFinite() }).isTrue()
        assertThat(result.probabilities.sum()).isWithin(1.0e-12).of(1.0)
    }

    @Test
    fun unknownFeaturesAreRejectedInsteadOfSilentlyDiscarded() {
        val input = observation.copy(stateFeatures = mapOf("not-in-frozen-dictionary" to 0.0))

        assertFailsWith<IllegalStateException> { CardPolicy.evaluate(input, model()) }
    }

    @Test
    fun dictionariesMustBeSortedAndMatricesMustHaveTheDeclaredShape() {
        assertFailsWith<IllegalArgumentException> {
            model().copy(stateFeatureNames = listOf("z", "a", "root"))
        }
        assertFailsWith<IllegalArgumentException> { model().copy(actionWeights = listOf(0.0)) }
    }

    @Test
    fun invalidUniformsCannotSelectAnAction() {
        for (uniform in listOf(-0.1, 1.0, Double.NaN)) {
            assertFailsWith<IllegalArgumentException> {
                CardPolicy.choose(observation, model(), uniform)
            }
        }
    }

    @Test
    fun signedSeedUniformsMatchTheIndependentIntegerOracle() {
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

        expected.forEach { (seed, value) ->
            assertThat(CardPolicy.uniform(seed)).isEqualTo(value)
        }
    }

    @Test
    fun all1024CardAlternativesSurviveWhilePurchaseKeepsIts128Bound() {
        val actions =
            (0 until 1024).map {
                CardAction("play hand-${it.toString().padStart(4, '0')}", emptyMap())
            }
        val input = observation.copy(actions = actions, baselineIndex = 511)
        val evaluation = CardPolicy.evaluate(input, model().copy(actorWeights = zeros()))
        assertThat(evaluation.probabilities).hasSize(1024)
        assertThat(evaluation.probabilities[511]).isEqualTo(0.97)
        assertThat(evaluation.probabilities[0]).isEqualTo(0.03 / 1023)
        assertFailsWith<IllegalArgumentException> {
            PurchaseObservation(
                stateFeatures = emptyMap(),
                actions = actions.take(129).map { PurchaseAction(it.command, it.features) },
                baselineIndex = 0,
            )
        }
    }

    @Test
    fun cardModelCannotMasqueradeAsThePurchaseEncoder() {
        assertFailsWith<IllegalArgumentException> {
            model().copy(encoderVersion = PurchaseObservationEncoder.ENCODER_VERSION)
        }
    }

    @Test
    fun cardSamplerHasItsOwnFrozenStreamSalt() {
        assertThat(CardPolicy.actorSeed(0)).isEqualTo(1013904242)
        assertThat(CardPolicy.actorSeed(17)).isEqualTo(17 xor 0x3c6ef372)
        assertThat(CardPolicy.actorSeed(17)).isNotEqualTo(PurchasePolicy.actorSeed(17))
    }

    @Test
    fun multiProvinceCommandsRetainTheBotCandidateTransportBound() {
        assertThat(CardAction("play held provinces " + "p".repeat(1500), emptyMap()).command)
            .hasLength(1520)
        assertFailsWith<IllegalArgumentException> { CardAction("play held\rpass", emptyMap()) }
    }

    private fun zeros(): List<Double> = List(64) { 0.0 }

    /** Sorted-column form of the independent Python/PyTorch one-active-unit oracle. */
    private fun model(): CardPolicyModel =
        CardPolicyModel(
            stateFeatureNames = listOf("after-left", "after-right", "root"),
            actionFeatureNames = listOf("cost", "left", "right"),
            stateWeights = List(192) { if (it == 2) 0.3 else 0.0 },
            stateBias = List(64) { if (it == 0) 0.2 else 0.0 },
            actionWeights =
                List(192) {
                    when (it) {
                        0 -> 0.1
                        1 -> 0.4
                        2 -> -0.2
                        else -> 0.0
                    }
                },
            actionBias = List(64) { if (it == 0) -0.1 else 0.0 },
            actorWeights = List(64) { if (it == 0) 1.2 else 0.0 },
            valueWeights = List(64) { if (it == 0) 0.8 else 0.0 },
            valueBias = -0.3,
        )
}
