package dev.aragos.aor.training

import com.fasterxml.jackson.annotation.JsonIgnore
import kotlin.random.Random

internal enum class TokenPairedSplit {
    TRAIN,
    VALIDATION,
}

/** Global indices, never local shard positions, determine the prospective request. */
internal data class TokenPairedRequest(val globalIndex: Int) {
    init {
        require(globalIndex in 0 until ORIGINS) {
            "Token paired origins use global indices 0..639."
        }
    }

    val seat: Int
        get() = globalIndex % 4

    val epoch: Int
        get() = 1 + (globalIndex / 4) % 3

    val ordinal: Int
        get() = 1 + (globalIndex / 12) % 4

    val split: TokenPairedSplit
        get() = if (globalIndex < TRAIN) TokenPairedSplit.TRAIN else TokenPairedSplit.VALIDATION

    companion object {
        const val TRAIN = 512
        const val ORIGINS = 640
    }
}

/** One fixed range consumes only its global rows from the shared complete stream table. */
internal data class TokenPairedRange(val globalStart: Int, val count: Int) {
    init {
        require(
            globalStart in 0 until TokenPairedRequest.ORIGINS &&
                globalStart % 64 == 0 &&
                count == 64
        ) {
            "A token shard must be one of the ten fixed 64-world global ranges."
        }
    }

    @get:JsonIgnore
    val indices: IntRange
        get() = globalStart until globalStart + count
}

internal data class TokenPairedSeeds(
    val gameSeed: Int,
    val proposalSeed: Long,
    val future: List<Long>,
) {
    init {
        require(
            future.size == 2 &&
                (listOf(gameSeed.toLong(), proposalSeed) + future).distinct().size == 4
        ) {
            "Each origin requires distinct world/proposal/two paired future streams."
        }
    }
}

/** Uniform without replacement over every nonreference offered index; no reward/score argument. */
internal data class TokenPairedProposals(val indices: List<Int>, val inclusionProbability: Double) {
    companion object {
        fun sample(actionCount: Int, referenceIndex: Int, seed: Long): TokenPairedProposals {
            require(actionCount in 1..75 && referenceIndex in 0 until actionCount) {
                "A token root requires all current public offers and its exact reference."
            }
            val alternatives = (0 until actionCount).filter { it != referenceIndex }
            val count = minOf(8, alternatives.size)
            val sampled = alternatives.shuffled(Random(seed)).take(count).sorted()
            return TokenPairedProposals(
                listOf(referenceIndex) + sampled,
                if (alternatives.isEmpty()) 0.0 else count.toDouble() / alternatives.size,
            )
        }
    }
}
