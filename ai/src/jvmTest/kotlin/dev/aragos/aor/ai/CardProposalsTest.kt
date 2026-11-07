package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardProposalsTest {
    private val commands =
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

    @Test
    fun signedSeedsMatchTheIndependentIntegerOracle() {
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
            assertThat(CardProposals.indices(observation(commands, 5), seed))
                .containsExactlyElementsIn(indices)
                .inOrder()
        }
    }

    @Test
    fun baselinePhysicalCardCannotReappearAsAnotherTargetOrProtectionAlternative() {
        val input = observation(listOf("pass", "play held", "play held protect 0", "play other"), 2)

        val result = CardProposals.indices(input, 17)

        assertThat(result).containsExactly(0, 2, 3).inOrder()
    }

    @Test
    fun missingPassIsNeverInvented() {
        val input = observation(listOf("play first", "play second"), 1)

        val result = CardProposals.indices(input, 0)

        assertThat(result).containsExactly(0, 1).inOrder()
        assertThat(result.map { input.actions[it].command }).doesNotContain("pass")
    }

    @Test
    fun passBaselineIsIncludedOnceAndDoesNotConsumeOneOfTheTwoCardGroups() {
        val input = observation(listOf("pass", "play first", "play second"), 0)

        val result = CardProposals.indices(input, 0)

        assertThat(result).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun identicalCardDefinitionFeaturesDoNotMergeDistinctPhysicalCards() {
        val input =
            observation(listOf("pass", "play first", "play second"), 0).let { original ->
                original.copy(
                    actions =
                        original.actions.map { it.copy(features = mapOf("card.wool-1" to 1.0)) }
                )
            }

        val result = CardProposals.indices(input, 0)

        assertThat(result).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun manyTargetsDoNotGiveOnePhysicalCardMoreGroupProbability() {
        val offered =
            listOf("pass", "play baseline", "play single-a", "play single-b") +
                (0 until 64).map { "play many protect $it" }
        val input = observation(offered.sorted(), offered.sorted().indexOf("play baseline"))
        val counts =
            (0 until 4096)
                .flatMap { seed ->
                    CardProposals.indices(input, seed)
                        .map { input.actions[it].command }
                        .filter { it != "pass" && it != "play baseline" }
                        .map { it.split(' ')[1] }
                }
                .groupingBy { it }
                .eachCount()

        assertThat(counts.keys).containsExactly("single-a", "single-b", "many")
        counts.values.forEach { count -> assertThat(count).isIn(2550..2910) }
    }

    @Test
    fun featuresDoNotInfluenceProposalSampling() {
        val input = observation(commands, 5)
        val changed =
            input.copy(
                stateFeatures = mapOf("unrelated-public-feature" to 16.0),
                actions =
                    input.actions.map { it.copy(features = mapOf("unrelated-quote" to -16.0)) },
            )

        assertThat(CardProposals.indices(changed, 17))
            .containsExactlyElementsIn(CardProposals.indices(input, 17))
            .inOrder()
    }

    @Test
    fun nestedDecisionCommandsAreRejectedByTheProposalBoundary() {
        val input = observation(listOf("buy-advance A", "pass"), 1)

        assertFailsWith<IllegalArgumentException> { CardProposals.indices(input, 0) }
    }

    @Test
    fun allLegalCommandsAreConsideredButOnlyFourCanBeProposed() {
        val offered =
            listOf("pass") +
                (0 until 1023).map {
                    "play hand-${it.toString().padStart(4, '0')}"
                }

        val result = CardProposals.indices(observation(offered, 512), 17)

        assertThat(result).hasSize(4)
        assertThat(result).containsAtLeast(0, 512)
        assertThat(result.all { it in offered.indices }).isTrue()
    }

    private fun observation(commands: List<String>, baseline: Int): CardObservation =
        CardObservation(
            stateFeatures = mapOf("cardWindow.normal" to 1.0),
            actions = commands.map { CardAction(it, emptyMap()) },
            baselineIndex = baseline,
        )
}
