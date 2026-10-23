package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.collections.immutable.persistentListOf

class CapitalSelectionTest {
    private val alice = PlayerId("p1")
    private val bob = PlayerId("p2")
    private val carol = PlayerId("p3")
    private val david = PlayerId("p4")

    @Test
    fun differentRankingsHaveDifferentIdentity() {
        val first = CapitalSelection(order = persistentListOf(alice, bob))
        val reversed = CapitalSelection(order = persistentListOf(bob, alice))

        assertThat(first).isNotEqualTo(reversed)
    }

    @Test
    fun rankingsCannotRepeatAPlayer() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                CapitalSelection(order = persistentListOf(alice, alice))
            }

        assertThat(failure).hasMessageThat().contains("cannot repeat")
    }

    @Test
    fun unequalBidsResolveTheOrderWithoutDice() {
        val selection = CapitalSelection.fromBids(mapOf(alice to 1, bob to 30, carol to 12))

        assertThat(selection.order.toList()).containsExactly(bob, carol, alice).inOrder()
        assertThat(selection.isResolved).isTrue()
    }

    @Test
    fun emptySelectionDoesNotHaveAResolvedOrder() {
        assertThat(CapitalSelection().isResolved).isFalse()
    }

    @Test
    fun equalBidsRetainRegistrationOrderUntilResolved() {
        val selection = CapitalSelection.fromBids(mapOf(alice to 12, bob to 12, carol to 0))

        assertThat(selection.order.toList()).containsExactly(alice, bob, carol).inOrder()
        assertThat(selection.ties).containsExactly(CapitalTie(listOf(alice, bob)))
        assertThat(selection.isResolved).isFalse()
    }

    @Test
    fun resolvingATiePreservesAllOtherRanks() {
        val selection =
            CapitalSelection.fromBids(mapOf(alice to 40, bob to 12, carol to 12, david to 0))

        val resolved = selection.resolveNextTie(mapOf(bob to DieRoll(2), carol to DieRoll(6)))

        assertThat(resolved.order.toList()).containsExactly(alice, carol, bob, david).inOrder()
        assertThat(resolved.isResolved).isTrue()
    }

    @Test
    fun remainingSubgroupsAreResolvedBeforeLowerRankedTies() {
        val selection =
            CapitalSelection.fromBids(mapOf(alice to 12, bob to 12, carol to 0, david to 0))

        val resolved = selection.resolveNextTie(mapOf(alice to DieRoll(3), bob to DieRoll(3)))

        assertThat(resolved.ties)
            .containsExactly(
                CapitalTie(listOf(alice, bob)),
                CapitalTie(listOf(carol, david)),
            )
            .inOrder()
    }

    @Test
    fun rollsMustNameExactlyTheNextTiedPlayers() {
        val selection = CapitalSelection.fromBids(mapOf(alice to 12, bob to 12, carol to 0))

        val failure =
            assertFailsWith<RuleViolation> {
                selection.resolveNextTie(mapOf(alice to DieRoll(1), carol to DieRoll(2)))
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_OUTCOME)
    }

    @Test
    fun resolvedSelectionsRejectFurtherRolls() {
        val selection = CapitalSelection.fromBids(mapOf(alice to 12, bob to 2, carol to 0))

        val failure =
            assertFailsWith<RuleViolation> {
                selection.resolveNextTie(mapOf(alice to DieRoll(1), bob to DieRoll(2)))
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_OUTCOME)
    }

    @Test
    fun tiesMustOccupyConsecutivePlacesInTheOrder() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                CapitalSelection(
                    order = persistentListOf(alice, bob, carol),
                    ties = listOf(CapitalTie(listOf(alice, carol))),
                )
            }

        assertThat(failure).hasMessageThat().contains("consecutive")
    }
}
