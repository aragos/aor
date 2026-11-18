package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class TokenAnchoredProposalsTest {
    private val commands = (-36..36).map { "buy-tokens $it" }.sorted()

    @Test
    fun monetaryNeighborsAreMandatoryEvenWhenCanonicalIndicesAreDistant() {
        val result = sample(current = 21, raw = 15, anchor = 21, sampled = 9)

        assertThat(result.mandatoryIndices.map(commands::get))
            .containsAtLeastElementsIn(listOf(9, 15, 19, 20, 21, 22, 23).map { "buy-tokens $it" })
        assertThat(result.indices.first()).isEqualTo(commands.indexOf("buy-tokens 21"))
        assertThat(result.indices).hasSize(12)
    }

    @Test
    fun unrelatedRawAnchorAndSampledActionsLeaveFourUnbiasedAlternatives() {
        val result = sample(current = 30, raw = -10, anchor = 21, sampled = 0)
        val remainder = commands.indices.filter { it !in result.mandatoryIndices }

        assertThat(result.mandatoryIndices).hasSize(8)
        assertThat(result.indices.count { it !in result.mandatoryIndices }).isEqualTo(4)
        assertThat(result.mandatoryIndices.map { result.inclusionProbabilities[it] })
            .containsExactlyElementsIn(List(8) { 1.0 })
        assertThat(remainder.map { result.inclusionProbabilities[it] })
            .containsExactlyElementsIn(List(65) { 4.0 / 65 })
    }

    @Test
    fun smallSparseOfferSetIsACensusWithoutInventingMissingNeighbors() {
        val sparse = listOf(-100, -36, 0, 19, 21, 23, 36, 100).map { "buy-tokens $it" }.sorted()
        val center = sparse.indexOf("buy-tokens 21")
        val result =
            TokenAnchoredProposals.sample(
                sparse,
                center,
                center,
                List(8) { 1.0 / 8 },
                center,
                budget = 12,
                seed = 17,
            )

        assertThat(result.indices.toSet()).isEqualTo(sparse.indices.toSet())
        assertThat(result.inclusionProbabilities).containsExactlyElementsIn(List(8) { 1.0 })
    }

    @Test
    fun nineArmBudgetStillRetainsFullCoverageBeyondEightMandatoryActions() {
        val result = sample(current = 30, raw = -10, anchor = 21, sampled = 0, budget = 9)

        assertThat(result.indices).hasSize(9)
        assertThat(result.inclusionProbabilities.all { it > 0.0 }).isTrue()
        assertThat(result.inclusionProbabilities.sum()).isWithin(1e-12).of(9.0)
    }

    private fun sample(
        current: Int,
        raw: Int,
        anchor: Int,
        sampled: Int,
        budget: Int = 12,
    ): TokenAnchoredProposals {
        val maximum = commands.indexOf("buy-tokens $current")
        return TokenAnchoredProposals.sample(
            commands,
            commands.indexOf("buy-tokens $raw"),
            commands.indexOf("buy-tokens $anchor"),
            commands.indices.map { if (it == maximum) .4 else .6 / (commands.size - 1) },
            commands.indexOf("buy-tokens $sampled"),
            budget,
            seed = 17,
        )
    }
}
