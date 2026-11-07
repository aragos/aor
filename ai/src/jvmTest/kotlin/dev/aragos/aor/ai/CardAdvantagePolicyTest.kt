package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardAdvantagePolicyTest {
    private val observation =
        CardObservation(
            stateFeatures = mapOf("cardWindow.normal" to 1.0, "root" to 1.0),
            actions =
                listOf(
                    CardAction("pass", mapOf("right" to 1.0)),
                    CardAction("play held", mapOf("left" to 1.0)),
                ),
            baselineIndex = 0,
        )

    @Test
    fun nonzeroActionValuesMatchTheIndependentFloat64Oracle() {
        val evaluation = CardAdvantagePolicy.evaluate(observation, model(), 17)

        evaluation.memberValues.forEach { values ->
            assertThat(values[0]).isWithin(1.0e-12).of(0.3866194497989705)
            assertThat(values[1]).isWithin(1.0e-12).of(0.4654413563564786)
        }
        assertThat(evaluation.meanAdvantages[1]).isWithin(1.0e-12).of(0.07882190655750809)
        assertThat(CardAdvantagePolicy.select(observation, evaluation).command)
            .isEqualTo("play held")
    }

    @Test
    fun zeroHeadsPreserveExactFWithoutAStochasticPrior() {
        val input = observation.copy(baselineIndex = 1)
        val network = member().copy(outputWeights = List(64) { 0.0 }, outputBias = 0.0)

        val choice =
            CardAdvantagePolicy.choose(input, model().copy(members = List(3) { network }), 17)

        assertThat(choice.command).isEqualTo("play held")
        assertThat(choice.overridden).isFalse()
    }

    @Test
    fun exactMarginDoesNotOverride() {
        val evaluation = CardAdvantageEvaluation(listOf(0, 1), 0, List(3) { listOf(0.0, 0.02) })

        assertThat(CardAdvantagePolicy.select(observation, evaluation).index).isEqualTo(0)
    }

    @Test
    fun strictlyAboveMarginAndUnanimouslyPositiveOverrides() {
        val evaluation =
            CardAdvantageEvaluation(listOf(0, 1), 0, List(3) { listOf(0.0, 0.02000001) })

        assertThat(CardAdvantagePolicy.select(observation, evaluation).index).isEqualTo(1)
    }

    @Test
    fun zeroAdvantageInOneMemberVetoesPositiveMean() {
        val evaluation =
            CardAdvantageEvaluation(
                listOf(0, 1),
                0,
                listOf(listOf(0.5, 0.5), listOf(0.5, 0.8), listOf(0.5, 0.8)),
            )

        assertThat(CardAdvantagePolicy.select(observation, evaluation).index).isEqualTo(0)
    }

    @Test
    fun highestMeanIsVetoedWithoutTryingALowerRankedUnanimousAlternative() {
        val input =
            observation.copy(actions = observation.actions + CardAction("play other", emptyMap()))
        val evaluation =
            CardAdvantageEvaluation(
                listOf(0, 1, 2),
                0,
                listOf(listOf(0.5, 0.4, 0.54), listOf(0.5, 0.8, 0.54), listOf(0.5, 0.8, 0.54)),
            )

        val choice = CardAdvantagePolicy.select(input, evaluation)

        assertThat(choice.winnerIndex).isEqualTo(1)
        assertThat(choice.index).isEqualTo(0)
    }

    @Test
    fun exactMeanTiesUseCanonicalCommandOrderRatherThanFOrMemberOrder() {
        val input =
            observation.copy(
                actions = observation.actions + CardAction("play other", emptyMap()),
                baselineIndex = 2,
            )
        val evaluation =
            CardAdvantageEvaluation(listOf(0, 1, 2), 2, List(3) { listOf(0.6, 0.6, 0.5) })

        assertThat(CardAdvantagePolicy.select(input, evaluation).command).isEqualTo("pass")
    }

    @Test
    fun unsupportedFinalWindowReturnsFWithoutEvaluatingUnknownFeatures() {
        val input =
            observation.copy(stateFeatures = mapOf("cardWindow.final" to 1.0, "unknown" to 1.0))

        assertThat(CardAdvantagePolicy.choose(input, model(), 17).index).isEqualTo(0)
    }

    @Test
    fun missingOrContradictoryWindowMetadataDoesNotActivateTheSelector() {
        val missing = observation.copy(stateFeatures = mapOf("root" to 1.0))
        val contradictory =
            observation.copy(
                stateFeatures = observation.stateFeatures + ("cardWindow.final" to 1.0)
            )

        assertThat(CardAdvantagePolicy.choose(missing, model(), 17).index).isEqualTo(0)
        assertThat(CardAdvantagePolicy.choose(contradictory, model(), 17).index).isEqualTo(0)
    }

    @Test
    fun stateFeatureIterationOrderCannotChangeNumericalResults() {
        val reordered =
            observation.copy(stateFeatures = mapOf("root" to 1.0, "cardWindow.normal" to 1.0))

        assertThat(CardAdvantagePolicy.evaluate(reordered, model(), 17))
            .isEqualTo(CardAdvantagePolicy.evaluate(observation, model(), 17))
    }

    @Test
    fun unknownFeatureNamesAreRejectedIncludingZeroAndUnproposedActions() {
        val input =
            observation.copy(
                actions =
                    observation.actions +
                        CardAction("play held protect 0", mapOf("unknown" to 0.0)),
                baselineIndex = 1,
            )

        assertFailsWith<IllegalArgumentException> {
            CardAdvantagePolicy.evaluate(input, model(), 17)
        }
    }

    @Test
    fun eachMemberSubtractsItsOwnBaselineValue() {
        val evaluation =
            CardAdvantageEvaluation(
                listOf(0, 1),
                0,
                listOf(listOf(0.1, 0.2), listOf(0.4, 0.5), listOf(0.7, 0.8)),
            )

        assertThat(evaluation.meanAdvantages[0]).isEqualTo(0.0)
        assertThat(evaluation.meanAdvantages[1]).isWithin(1.0e-12).of(0.1)
    }

    @Test
    fun identicalActionFeaturesCanReversePreferenceWhenTheVisibleContextChanges() {
        val network =
            member()
                .copy(
                    stateWeights = List(128) { if (it == 1) 1.0 else 0.0 },
                    stateBias = List(64) { 0.0 },
                    actionWeights = List(128) { if (it == 0) 0.6 else 0.0 },
                    actionBias = List(64) { 0.0 },
                    outputWeights = List(64) { if (it == 0) 1.0 else 0.0 },
                    outputBias = 0.0,
                )
        val contextualModel = model().copy(members = List(3) { network })
        val opposite =
            observation.copy(stateFeatures = observation.stateFeatures + ("root" to -1.0))

        assertThat(CardAdvantagePolicy.choose(observation, contextualModel, 17).command)
            .isEqualTo("play held")
        assertThat(CardAdvantagePolicy.choose(opposite, contextualModel, 17).command)
            .isEqualTo("pass")
        assertThat(CardAdvantagePolicy.evaluate(observation, contextualModel, 17).meanAdvantages[1])
            .isWithin(1.0e-12)
            .of(0.10085138583319275)
        assertThat(CardAdvantagePolicy.evaluate(opposite, contextualModel, 17).meanAdvantages[1])
            .isWithin(1.0e-12)
            .of(-0.10085138583319275)
    }

    @Test
    fun zeroStateEmbeddingCannotInventAnActionOnlyPreference() {
        val network = member().copy(stateWeights = List(128) { 0.0 }, stateBias = List(64) { 0.0 })

        val evaluation =
            CardAdvantagePolicy.evaluate(
                observation,
                model().copy(members = List(3) { network }),
                17,
            )

        assertThat(evaluation.memberAdvantages)
            .containsExactly(
                listOf(0.0, 0.0),
                listOf(0.0, 0.0),
                listOf(0.0, 0.0),
            )
        assertThat(CardAdvantagePolicy.select(observation, evaluation).overridden).isFalse()
    }

    @Test
    fun extremeFiniteOutputsRemainBoundedWithoutAResidualClip() {
        val positive = member().copy(outputWeights = List(64) { 0.0 }, outputBias = 1000.0)
        val negative = positive.copy(outputBias = -1000.0)

        val evaluation =
            CardAdvantagePolicy.evaluate(
                observation,
                model().copy(members = listOf(positive, negative, positive)),
                17,
            )

        assertThat(evaluation.memberValues)
            .containsExactly(listOf(1.0, 1.0), listOf(0.0, 0.0), listOf(1.0, 1.0))
            .inOrder()
    }

    @Test
    fun versionAndDimensionBoundariesCannotReinterpretOtherPolicies() {
        assertFailsWith<IllegalArgumentException> {
            model().copy(selectorVersion = CardPolicy.ACTOR_VERSION)
        }
        assertFailsWith<IllegalArgumentException> { model().copy(members = listOf(member())) }
        assertFailsWith<IllegalArgumentException> { model().copy(margin = 0.03) }
        assertFailsWith<IllegalArgumentException> {
            model().copy(actionFeatureNames = listOf("left"))
        }
        assertFailsWith<IllegalArgumentException> { member().copy(outputBias = Double.NaN) }
    }

    @Test
    fun additiveHeadVersionOneCannotBeReinterpretedAsAnInteractionModel() {
        assertFailsWith<IllegalArgumentException> {
            model().copy(selectorVersion = "aor-card-advantage-1")
        }
    }

    @Test
    fun evaluationCannotUseAReferenceActionFromAnotherObservation() {
        val evaluation = CardAdvantageEvaluation(listOf(0, 1), 1, List(3) { listOf(0.4, 0.5) })

        assertFailsWith<IllegalArgumentException> {
            CardAdvantagePolicy.select(observation, evaluation)
        }
    }

    private fun model(): CardAdvantageModel =
        CardAdvantageModel(
            stateFeatureNames = listOf("cardWindow.normal", "root"),
            actionFeatureNames = listOf("left", "right"),
            members = List(3) { member() },
        )

    private fun member(): CardAdvantageNetwork =
        CardAdvantageNetwork(
            stateWeights = List(128) { if (it == 1) 0.3 else 0.0 },
            stateBias = List(64) { if (it == 0) 0.2 else 0.0 },
            actionWeights =
                List(128) {
                    when (it) {
                        0 -> 0.4
                        1 -> -0.2
                        else -> 0.0
                    }
                },
            actionBias = List(64) { if (it == 0) -0.1 else 0.0 },
            outputWeights = List(64) { if (it == 0) 1.2 else 0.0 },
            outputBias = -0.3,
        )
}
