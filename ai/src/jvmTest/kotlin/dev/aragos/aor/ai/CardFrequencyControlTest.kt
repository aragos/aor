package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardFrequencyControlTest {
    @Test
    fun signedSeedsMatchIndependentFamilyAndProposalGoldens() {
        val input =
            input(
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
                ),
                5,
            )
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
            assertThat(
                    CardFrequencyControl.choose(
                            input,
                            CardFrequencyModel(passProbability = 0.25, otherProbability = 0.5),
                            seed,
                        )
                        .index
                )
                .isEqualTo(index)
        }
    }

    @Test
    fun unavailablePassFallsBackInsteadOfRedistributingItsProbabilityToCards() {
        val observation = input(listOf("play first", "play second"), 0)

        val choice =
            CardFrequencyControl.choose(
                observation,
                CardFrequencyModel(passProbability = 1.0, otherProbability = 0.0),
                17,
            )

        assertThat(choice.index).isEqualTo(0)
        assertThat(choice.requestedFamily).isEqualTo(CardFrequencyFamily.PASS)
        assertThat(choice.overridden).isFalse()
    }

    @Test
    fun passAlreadyRecommendedByFIsNotANonbaselinePassOverride() {
        val observation = input(listOf("pass", "play first"), 0)

        val choice =
            CardFrequencyControl.choose(
                observation,
                CardFrequencyModel(passProbability = 1.0, otherProbability = 0.0),
                17,
            )

        assertThat(choice.index).isEqualTo(0)
        assertThat(choice.overridden).isFalse()
    }

    @Test
    fun otherCardFamilyCannotChooseAnotherTargetOfTheBaselineCard() {
        val observation = input(listOf("pass", "play first", "play first protect 0"), 1)

        val choice =
            CardFrequencyControl.choose(
                observation,
                CardFrequencyModel(passProbability = 0.0, otherProbability = 1.0),
                17,
            )

        assertThat(choice.index).isEqualTo(1)
        assertThat(choice.requestedFamily).isEqualTo(CardFrequencyFamily.OTHER)
    }

    @Test
    fun zeroRequestProbabilitiesExactlyRetainF() {
        val observation = input(listOf("pass", "play first", "play second"), 1)

        assertThat(
                CardFrequencyControl.choose(
                        observation,
                        CardFrequencyModel(passProbability = 0.0, otherProbability = 0.0),
                        17,
                    )
                    .index
            )
            .isEqualTo(1)
    }

    @Test
    fun finalWindowDoesNotApplyNormalWindowFrequencyControl() {
        val observation =
            input(listOf("pass", "play first"), 1)
                .copy(stateFeatures = mapOf("cardWindow.final" to 1.0))

        assertThat(
                CardFrequencyControl.choose(
                        observation,
                        CardFrequencyModel(passProbability = 1.0, otherProbability = 0.0),
                        17,
                    )
                    .index
            )
            .isEqualTo(1)
    }

    @Test
    fun invalidProbabilityMassIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CardFrequencyModel(passProbability = 0.6, otherProbability = 0.6)
        }
        assertFailsWith<IllegalArgumentException> {
            CardFrequencyModel(passProbability = -0.1, otherProbability = 0.1)
        }
        assertFailsWith<IllegalArgumentException> {
            CardFrequencyModel(passProbability = Double.NaN, otherProbability = 0.0)
        }
    }

    private fun input(commands: List<String>, baseline: Int): CardObservation =
        CardObservation(
            stateFeatures = mapOf("cardWindow.normal" to 1.0),
            actions = commands.map { CardAction(it, emptyMap()) },
            baselineIndex = baseline,
        )
}
