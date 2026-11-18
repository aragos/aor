package dev.aragos.aor.training

import com.fasterxml.jackson.annotation.JsonIgnore
import kotlin.random.Random

/** Legacy requests remain fixed; the new anchored family declares its finite layout explicitly. */
internal data class TokenPairedLayout(
    val originCount: Int = 640,
    val trainCount: Int = 512,
    val anchored: Boolean = false,
) {
    init {
        require(
            if (anchored) originCount in 2..4096 && trainCount in 1 until originCount
            else originCount == 640 && trainCount == 512
        ) {
            "A token layout requires its fixed legacy counts or a bounded explicit anchored split."
        }
    }
}

internal enum class TokenPairedSplit {
    TRAIN,
    VALIDATION,
}

/** Global indices, never local shard positions, determine the prospective request. */
internal data class TokenPairedRequest(
    val globalIndex: Int,
    @get:JsonIgnore private val layout: TokenPairedLayout = TokenPairedLayout(),
) {
    init {
        require(globalIndex in 0 until layout.originCount) {
            "A token request must belong to its declared complete origin layout."
        }
    }

    val seat: Int
        get() = globalIndex % 4

    val epoch: Int
        get() = 1 + (globalIndex / 4) % 3

    val ordinal: Int
        get() = 1 + (globalIndex / 12) % 4

    val split: TokenPairedSplit
        get() =
            if (globalIndex < layout.trainCount) TokenPairedSplit.TRAIN
            else TokenPairedSplit.VALIDATION

    companion object {
        const val TRAIN = 512
        const val ORIGINS = 640
    }
}

/** One fixed range consumes only its global rows from the shared complete stream table. */
internal data class TokenPairedRange(
    val globalStart: Int,
    val count: Int,
    @get:JsonIgnore private val layout: TokenPairedLayout = TokenPairedLayout(),
) {
    init {
        require(
            if (layout.anchored) {
                globalStart >= 0 && count in 1..64 && globalStart + count <= layout.originCount
            } else {
                globalStart in 0 until TokenPairedRequest.ORIGINS &&
                    globalStart % 64 == 0 &&
                    count == 64
            }
        ) {
            "A token shard must match its fixed legacy range or declared bounded anchored layout."
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
