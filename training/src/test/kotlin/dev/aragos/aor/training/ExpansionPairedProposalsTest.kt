package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.TerritoryAction
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionPairedProposalsTest {
    @Test
    fun schedulesSeatEpochAndOrdinalIndependentlyAcrossEveryFortyEightOrigins() {
        val cells = (0 until 48).map(::ExpansionPairedRequest)

        assertThat(cells.map { Triple(it.seat, it.epoch, it.ordinal) }.distinct()).hasSize(48)
        assertThat(cells.map { it.ordinal }.toSet()).containsExactly(0, 2, 4, 6)
    }

    @Test
    fun splitsPreserveTheOriginalWholeOriginBoundaries() {
        val requests = (0 until 1024).map(::ExpansionPairedRequest)

        assertThat(requests.groupingBy { it.split }.eachCount())
            .containsExactly(
                ExpansionPairedSplit.TRAIN,
                768,
                ExpansionPairedSplit.VALIDATION,
                128,
                ExpansionPairedSplit.TEST,
                128,
            )
        assertThat(requests[768].split).isEqualTo(ExpansionPairedSplit.VALIDATION)
        assertThat(requests[896].split).isEqualTo(ExpansionPairedSplit.TEST)
    }

    @Test
    fun repeatedOriginAndFutureStreamsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            ExpansionPairedSeeds(1, 2, listOf(3, 1))
        }
    }

    @Test
    fun incumbentPassAndCardPurchaseComeBeforeSampledDestinations() {
        val actions = actions("place 2 paris", "place 1 paris", "buy-expansion-card", "pass")

        val result = proposals(actions, "place 2 paris")

        assertThat(result.take(3).map { it.command })
            .containsExactly("place 2 paris", "pass", "buy-expansion-card")
            .inOrder()
        assertThat(result.map { actions[it.actionIndex].command })
            .containsExactlyElementsIn(result.map { it.command })
            .inOrder()
    }

    @Test
    fun incumbentPassIsNotDuplicated() {
        val actions = actions("place 2 paris", "buy-expansion-card", "pass")

        val result = proposals(actions, "pass")

        assertThat(result.map { it.command }).containsExactlyElementsIn(actions.map { it.command })
        assertThat(result.first().reason).isEqualTo(ExpansionProposalReason.INCUMBENT)
    }

    @Test
    fun proposalSeedIsDeterministicAndNeverReadsScoresOrOutcomes() {
        val original = actions("place 2 paris", "place 1 paris", "place 2 venice", "pass")
        val changed = original.map { it.copy(features = mapOf("unused" to 15.0)) }

        assertThat(proposals(original, "pass")).isEqualTo(proposals(changed, "pass"))
    }

    @Test
    fun everyRemainingDestinationGetsASampleBeforeItsSecondCommitment() {
        val actions =
            actions("pass", "place 1 paris", "place 2 paris", "place 1 venice", "place 2 venice")

        val result = proposals(actions, "pass")

        assertThat(result.subList(1, 3).map { it.command.substringAfterLast(' ') }.toSet())
            .containsExactly("paris", "venice")
        assertThat(result.takeLast(2).map { it.reason }.toSet())
            .containsExactly(ExpansionProposalReason.REMAINING_COMMAND_SAMPLE)
    }

    @Test
    fun broadOffersRemainCappedAtEightOfferedCommands() {
        val actions =
            actions(
                "pass",
                "buy-expansion-card",
                *(1..12).map { "place $it paris" }.toTypedArray(),
            )

        val result = proposals(actions, "place 1 paris")

        assertThat(result).hasSize(8)
        assertThat(result.map { it.command }.distinct()).hasSize(8)
        assertThat(actions.map { it.command }).containsAtLeastElementsIn(result.map { it.command })
    }

    @Test
    fun nestedOrUnrelatedCommandsCannotEnterOrdinaryExpansionProposals() {
        val actions = actions("pass", "windmill")

        assertFailsWith<IllegalArgumentException> { proposals(actions, "pass") }
    }

    private fun actions(vararg commands: String): List<TerritoryAction> =
        commands.sorted().map { TerritoryAction(it, emptyMap()) }

    private fun proposals(actions: List<TerritoryAction>, baseline: String) =
        ExpansionPairedProposals.from(actions, actions.indexOfFirst { it.command == baseline }, 17L)
}
