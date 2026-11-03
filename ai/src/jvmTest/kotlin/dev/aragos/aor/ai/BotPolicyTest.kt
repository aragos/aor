package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BotPolicyTest {
    @Test
    fun hardPlansACombinationInsteadOfSpendingTheBudgetOnTheLargestSingleOffer() {
        val large = candidate("buy-advance C", 6, 10.0)
        val firstSmall = candidate("buy-advance A", 5, 9.0)
        val secondSmall = candidate("buy-advance E", 5, 9.0)
        val observation = observation(listOf(large, firstSmall, secondSmall), cash = 10)

        val normal = BotPolicy.choose(observation, BotDifficulty.NORMAL, 0)
        val hard = BotPolicy.choose(observation, BotDifficulty.HARD, 0)

        assertThat(normal).isEqualTo(large.command)
        assertThat(hard).isEqualTo(firstSmall.command)
    }

    @Test
    fun planningCannotCountTwoAlternativesForOneProvinceAsSeparateGains() {
        val alternatives =
            listOf(
                BotCandidate(
                    "place 1 rome",
                    mapOf("points" to 9.0),
                    tokenCost = 1,
                    group = "rome",
                    family = "expansion",
                ),
                BotCandidate(
                    "place 2 rome",
                    mapOf("points" to 10.0),
                    tokenCost = 2,
                    group = "rome",
                    family = "expansion",
                ),
                BotCandidate(
                    "place 3 florence",
                    mapOf("points" to 12.0),
                    tokenCost = 3,
                    group = "florence",
                    family = "expansion",
                ),
            )
        val observation = observation(alternatives).copy(decision = "expand", availableTokens = 3)

        val selected = BotPolicy.choose(observation, BotDifficulty.HARD, 0)

        assertThat(selected).isEqualTo("place 3 florence")
    }

    @Test
    fun easyVariationIsReproducibleAndAvoidsCatastrophicChoices() {
        val observation =
            observation(
                listOf(
                    candidate("pass", 0, 1.0),
                    candidate("buy-advance A", 2, 3.0),
                    BotCandidate("stabilize misery", mapOf("chaos" to 1.0)),
                )
            )

        val choices = (0..20).map { BotPolicy.choose(observation, BotDifficulty.EASY, it) }

        assertThat(choices).doesNotContain("stabilize misery")
        assertThat(choices.toSet()).hasSize(2)
        assertThat(BotPolicy.choose(observation, BotDifficulty.EASY, 17))
            .isEqualTo(BotPolicy.choose(observation, BotDifficulty.EASY, 17))
    }

    @Test
    fun malformedModelCannotIntroduceNonFinitePolicyScores() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                BotModel(weights = mapOf("cash" to Double.NaN))
            }

        assertThat(failure).hasMessageThat().contains("finite")
    }

    private fun candidate(command: String, cost: Int, value: Double): BotCandidate =
        BotCandidate(command, mapOf("points" to value), cost, group = command, family = "advance")

    private fun observation(candidates: List<BotCandidate>, cash: Int = 20): BotObservation =
        BotObservation(
            playerId = "p1",
            decision = "purchase-advances",
            context = mapOf("stabilization" to 0.0),
            availableCash = cash,
            availableTokens = 0,
            candidates = candidates,
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )
}
