package dev.aragos.aor.training

import dev.aragos.aor.ai.LocalTokenNeighborPolicy
import kotlin.math.abs
import kotlin.random.Random

/** Focused comparisons plus an unbiased uniform remainder over every other original offer. */
internal data class TokenAnchoredProposals(
    val indices: List<Int>,
    val mandatoryIndices: List<Int>,
    /** One inclusion probability for every original offered index, not only selected arms. */
    val inclusionProbabilities: List<Double>,
) {
    companion object {
        const val VERSION = "token-anchor-focused-uniform-proposals-1"

        fun sample(
            commands: List<String>,
            rawReference: Int,
            anchorReference: Int,
            currentProbabilities: List<Double>,
            sampledIndex: Int,
            budget: Int,
            seed: Long,
        ): TokenAnchoredProposals {
            require(
                commands.size in 1..75 &&
                    commands.distinct().sorted() == commands &&
                    listOf(rawReference, anchorReference, sampledIndex).all {
                        it in commands.indices
                    } &&
                    currentProbabilities.size == commands.size &&
                    currentProbabilities.all { it.isFinite() && it > 0.0 } &&
                    abs(currentProbabilities.sum() - 1.0) <= 1e-12 &&
                    budget in 9..16
            ) {
                "Anchored proposals require complete canonical offers, exact indices and a finite9..16 budget."
            }
            val amounts = commands.map(LocalTokenNeighborPolicy::amount)
            val maximum = currentProbabilities.max()
            val current =
                if (currentProbabilities[anchorReference] == maximum) anchorReference
                else currentProbabilities.indexOfFirst { it == maximum }
            val center = amounts[current].toLong()
            val mandatory =
                (listOf(rawReference, anchorReference, sampledIndex) +
                        amounts.indices.filter { amounts[it].toLong() in center - 2..center + 2 })
                    .distinct()
                    .sorted()
            val remaining = commands.indices.filter { it !in mandatory }
            val count = minOf(budget - mandatory.size, remaining.size)
            require(count > 0 || remaining.isEmpty()) {
                "A focused proposal budget must retain unbiased coverage outside mandatory actions."
            }
            val uniform = remaining.shuffled(Random(seed)).take(count)
            val selected = (mandatory + uniform).sorted()
            val inclusion = if (remaining.isEmpty()) 1.0 else count.toDouble() / remaining.size
            return TokenAnchoredProposals(
                listOf(anchorReference) + selected.filter { it != anchorReference },
                mandatory,
                commands.indices.map { if (it in mandatory) 1.0 else inclusion },
            )
        }
    }
}
