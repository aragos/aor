package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionRulesTest {
    @Test
    fun attackAcceptsExactlyTheRequiredTokens() {
        AttackRequirement(tokens = 5).validateCommitment(existingTokens = 0, addedTokens = 5)
    }

    @Test
    fun existingTokensCountTowardTheAttackRequirement() {
        AttackRequirement(tokens = 5).validateCommitment(existingTokens = 2, addedTokens = 3)
    }

    @Test
    fun existingTokensCanSatisfyTheEntireAttackRequirement() {
        AttackRequirement(tokens = 5).validateCommitment(existingTokens = 5, addedTokens = 0)
    }

    @Test
    fun attacksCannotCommitExcessTokens() {
        invalidCommitment {
            AttackRequirement(tokens = 5).validateCommitment(existingTokens = 0, addedTokens = 6)
        }
    }

    @Test
    fun existingTokensCannotBeIgnoredWhenCheckingForExcessTokens() {
        invalidCommitment {
            AttackRequirement(tokens = 5).validateCommitment(existingTokens = 2, addedTokens = 5)
        }
    }

    @Test
    fun attacksCannotCommitTooFewTokens() {
        invalidCommitment {
            AttackRequirement(tokens = 5).validateCommitment(existingTokens = 2, addedTokens = 2)
        }
    }

    @Test
    fun existingTokensCannotBeNegative() {
        invalidCommitment {
            AttackRequirement(tokens = 5).validateCommitment(existingTokens = -1, addedTokens = 6)
        }
    }

    @Test
    fun addedTokensCannotBeNegative() {
        invalidCommitment {
            AttackRequirement(tokens = 5).validateCommitment(existingTokens = 6, addedTokens = -1)
        }
    }

    @Test
    fun excessiveCommitmentsAreRejectedWhenTheirSumExceedsAnInteger() {
        invalidCommitment {
            AttackRequirement(tokens = Int.MAX_VALUE)
                .validateCommitment(existingTokens = Int.MAX_VALUE, addedTokens = Int.MAX_VALUE)
        }
    }

    @Test
    fun attackRequirementsMustBePositive() {
        for (tokens in listOf(0, -1)) {
            val failure = assertFailsWith<IllegalArgumentException> { AttackRequirement(tokens) }

            assertThat(failure).hasMessageThat().isNotEmpty()
        }
    }

    @Test
    fun newExpansionMarkersCountUnderEitherRebuildingPolicy() {
        for (policy in RebuiltMarkerBonus.entries) {
            assertThat(policy.counts(MarkerPlacementOrigin.EXPANSION)).isTrue()
        }
    }

    @Test
    fun eventRebuildsDoNotCountWhenTheOptionIsExcluded() {
        assertThat(RebuiltMarkerBonus.EXCLUDE.counts(MarkerPlacementOrigin.EVENT_REBUILD)).isFalse()
    }

    @Test
    fun eventRebuildsCountWhenTheOptionIsIncluded() {
        assertThat(RebuiltMarkerBonus.INCLUDE.counts(MarkerPlacementOrigin.EVENT_REBUILD)).isTrue()
    }

    @Test
    fun voluntaryRebuildsNeverCountTowardTheExpansionBonus() {
        for (policy in RebuiltMarkerBonus.entries) {
            assertThat(policy.counts(MarkerPlacementOrigin.VOLUNTARY_REBUILD)).isFalse()
        }
    }

    private fun invalidCommitment(action: () -> Unit) {
        val failure = assertFailsWith<RuleViolation>(block = action)

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_ATTACK_TOKEN_COUNT)
    }
}
