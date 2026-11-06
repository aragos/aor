package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.PlayerId
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BotTargetTieBreakTest {
    private val targets = (1..3).map { target("p$it") }
    private val enabled = model(1.0)

    @Test
    fun zeroGatePreservesEveryDifficultyAndSeed() {
        val projected = observation(targets)
        val legacy = projected.copy(candidates = targets.map { it.copy(playerTarget = null) })

        for (difficulty in BotDifficulty.entries) {
            for (seed in 0..63) {
                assertThat(BotPolicy.choose(projected, difficulty, seed, model(0.0)))
                    .isEqualTo(BotPolicy.choose(legacy, difficulty, seed, model(null)))
            }
        }
    }

    @Test
    fun missingGateRetainsTheLexicalTarget() {
        val choices = (0..63).map { choose(observation(targets), it, model(null)) }

        assertThat(choices.toSet()).containsExactly("play event player p1")
    }

    @Test
    fun onlyAnExactUnitGateEnablesVariation() {
        val choices =
            listOf(-1.0, 0.5, 2.0).flatMap { gate ->
                (0..63).map { choose(observation(targets), it, model(gate)) }
            }

        assertThat(choices.toSet()).containsExactly("play event player p1")
    }

    @Test
    fun otherDifficultiesRetainTheirExistingChoicesWhenEnabled() {
        for (difficulty in listOf(BotDifficulty.EASY, BotDifficulty.NORMAL)) {
            for (seed in 0..63) {
                assertThat(BotPolicy.choose(observation(targets), difficulty, seed, enabled))
                    .isEqualTo(
                        BotPolicy.choose(observation(targets), difficulty, seed, model(null))
                    )
            }
        }
    }

    @Test
    fun equalScoredOtherCardsCannotChangeTheSelectedCard() {
        val first = (1..3).map { target("p$it", reference = "first") }
        val second = (1..3).map { target("p$it", reference = "second") }

        val choices = (0..63).map { choose(observation(second + first), it) }

        assertThat(choices.toSet()).containsExactlyElementsIn(first.map { it.command })
    }

    @Test
    fun aTiedPassRetainsTheOriginalCardTiming() {
        val pass = BotCandidate("pass", mapOf("points" to 1.0), family = "pass")

        val choices = (0..63).map { choose(observation(targets + pass), it) }

        assertThat(choices.toSet()).containsExactly("pass")
    }

    @Test
    fun evenASmallScoreDifferenceProtectsTheBetterTarget() {
        val better = target("p2", score = 1.000000000001)
        val candidates = listOf(target("p1"), better, target("p3"))

        val choices = (0..63).map { choose(observation(candidates), it) }

        assertThat(choices.toSet()).containsExactly(better.command)
    }

    @Test
    fun aSelectedSelfTargetRetainsItsOriginalChoice() {
        val selfFirst = observation(listOf(target("p1"), target("p2")), actor = "p1")

        val choices = (0..63).map { choose(selfFirst, it) }

        assertThat(choices.toSet()).containsExactly("play event player p1")
    }

    @Test
    fun aSelfTargetCannotJoinTheOpponentTie() {
        val actorSecond = observation(targets, actor = "p2")

        val choices = (0..63).map { choose(actorSecond, it) }

        assertThat(choices.toSet()).containsExactly("play event player p1", "play event player p3")
    }

    @Test
    fun missingSelectedTargetMetadataFallsBackToItsOriginalChoice() {
        val candidates = listOf(targets.first().copy(playerTarget = null)) + targets.drop(1)

        val choices = (0..63).map { choose(observation(candidates), it) }

        assertThat(choices.toSet()).containsExactly("play event player p1")
    }

    @Test
    fun aSingleOpponentTargetRetainsItsOriginalChoice() {
        val choices = (0..63).map { choose(observation(listOf(target("p3"))), it) }

        assertThat(choices.toSet()).containsExactly("play event player p3")
    }

    @Test
    fun candidateOrderDoesNotChangeSeededTargetSelection() {
        val forward = observation(targets)
        val reversed = observation(targets.reversed())

        for (seed in 0..63) {
            assertThat(choose(forward, seed)).isEqualTo(choose(reversed, seed))
        }
    }

    @Test
    fun signedSeedsHaveStableJvmAndJsGoldenChoices() {
        val seeds = listOf(0, 1, 2, 17, -1, Int.MIN_VALUE, Int.MAX_VALUE, -979738860)

        val choices = seeds.map { choose(observation(targets), it) }

        assertThat(choices)
            .containsExactly(
                "play event player p1",
                "play event player p1",
                "play event player p2",
                "play event player p3",
                "play event player p1",
                "play event player p2",
                "play event player p3",
                "play event player p2",
            )
            .inOrder()
    }

    @Test
    fun twoOpponentTiesHaveBroadlyBalancedSeedCoverage() {
        assertBalancedCoverage(2)
    }

    @Test
    fun threeOpponentTiesHaveBroadlyBalancedSeedCoverage() {
        assertBalancedCoverage(3)
    }

    @Test
    fun fiveOpponentTiesHaveBroadlyBalancedSeedCoverage() {
        assertBalancedCoverage(5)
    }

    @Test
    fun targetMetadataMustMatchItsCanonicalCardCommand() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                target("p1").copy(playerTarget = PlayerId("p2"))
            }

        assertThat(failure).hasMessageThat().contains("canonical card command")
    }

    @Test
    fun targetMetadataCannotBelongToAnotherCardGroup() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                target("p1").copy(group = "card.different")
            }

        assertThat(failure).hasMessageThat().contains("canonical card command")
    }

    private fun assertBalancedCoverage(opponents: Int) {
        val candidates = (1..opponents).map { target("p$it") }
        val observation = observation(candidates, actor = "p${opponents + 1}")
        val counts = (0 until 6000).groupingBy { choose(observation, it) }.eachCount()
        val expected = 6000 / opponents

        assertThat(counts.keys).containsExactlyElementsIn(candidates.map { it.command })
        for (count in counts.values) {
            assertThat(count).isAtLeast(expected * 9 / 10)
            assertThat(count).isAtMost(expected * 11 / 10)
        }
    }

    private fun choose(
        observation: BotObservation,
        seed: Int,
        model: BotModel = enabled,
    ): String = BotPolicy.choose(observation, BotDifficulty.HARD, seed, model)

    private fun model(gate: Double?): BotModel =
        BotModel(
            weights =
                mapOf("points" to 1.0) +
                    if (gate == null) emptyMap() else mapOf(BotTargetTieBreak.GATE to gate)
        )

    private fun target(
        player: String,
        reference: String = "event",
        score: Double = 1.0,
    ): BotCandidate =
        BotCandidate(
            command = "play $reference player $player",
            features = mapOf("points" to score),
            group = "card.$reference",
            family = "play",
            playerTarget = PlayerId(player),
        )

    private fun observation(
        candidates: List<BotCandidate>,
        actor: String = "p4",
    ): BotObservation =
        BotObservation(
            playerId = actor,
            decision = "play-cards",
            context = emptyMap(),
            availableCash = 30,
            availableTokens = 10,
            candidates = candidates,
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )
}
