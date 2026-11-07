package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JointEconomyPolicyTest {
    @Test
    fun fixedPoolContainsTheIncumbentSixAxesAndEightCornersInOrder() {
        val candidates = JointEconomyCandidates.generate(baseline)

        assertThat(candidates.map { it.id })
            .containsExactly(
                "incumbent",
                "liquidity-low",
                "liquidity-high",
                "investment-low",
                "investment-high",
                "expansion-low",
                "expansion-high",
                "corner-lll",
                "corner-llh",
                "corner-lhl",
                "corner-lhh",
                "corner-hll",
                "corner-hlh",
                "corner-hhl",
                "corner-hhh",
            )
            .inOrder()
        assertThat(candidates.map { it.multipliers }.distinct()).hasSize(15)
        assertThat(candidates.first().policy.model).isEqualTo(baseline)
        assertThat(JointEconomyCandidates.generate(baseline).map { it.policy.model })
            .isEqualTo(candidates.map { it.policy.model })
    }

    @Test
    fun everyCandidateChangesOnlyItsDeclaredAxesByExactMultiplication() {
        val candidates = JointEconomyCandidates.generate(baseline)

        candidates.forEach { candidate ->
            JointEconomyAxis.entries.forEachIndexed { index, axis ->
                axis.keys.forEach { key ->
                    assertThat(candidate.policy.model.weights[key])
                        .isEqualTo(
                            requireNotNull(baseline.weights[key]) { "Fixture axis is present." } *
                                candidate.multipliers[index]
                        )
                }
            }
            assertThat(
                    candidate.policy.model.weights.filterKeys {
                        it !in JointEconomyPolicy.allowedKeys
                    }
                )
                .isEqualTo(baseline.weights.filterKeys { it !in JointEconomyPolicy.allowedKeys })
        }
    }

    @Test
    fun unrelatedChangesIncludingTheTokenGateAreRejected() {
        listOf("cash", "planning.tokenValue.enabled", "planning.targetTieBreak").forEach { key ->
            val changed = baseline.copy(weights = baseline.weights + (key to 2.0))

            assertFailsWith<IllegalArgumentException> { JointEconomyPolicy(baseline, changed) }
        }
    }

    @Test
    fun removingAnUnrelatedExplicitWeightIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            JointEconomyPolicy(baseline, baseline.copy(weights = baseline.weights - "cash"))
        }
    }

    @Test
    fun generationRejectsMissingAxisCoefficientsRatherThanInventingAZero() {
        val missing =
            baseline.copy(weights = baseline.weights - JointEconomyAxis.LIQUIDITY.keys.first())

        assertFailsWith<IllegalArgumentException> { JointEconomyCandidates.generate(missing) }
    }

    companion object {
        internal val baseline: BotModel =
            BotModels.default.copy(
                weights =
                    BotModels.default.weights +
                        JointEconomyPolicy.allowedKeys.associateWith { key ->
                            if (key.endsWith("costFraction") || key.endsWith("endgameIncome")) -0.25
                            else 1.0
                        } +
                        ("planning.tokenValue.enabled" to 1.0)
            )
    }
}
