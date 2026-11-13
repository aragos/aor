package dev.aragos.aor.training

import com.fasterxml.jackson.annotation.JsonIgnore
import kotlin.random.Random

/** Each cohort observes one head while its controlled seat executes the complete current bundle. */
internal enum class CpeHead(val decisions: Set<String>) {
    CARD(setOf("play-cards")),
    PURCHASE(setOf("purchase-shipping", "purchase-advances")),
    EXPANSION(setOf("expand")),
}

internal data class CpePairedRequest(val head: CpeHead, val globalIndex: Int) {
    init {
        require(globalIndex in 0 until 640) { "Each C/P/E cohort uses global indices 0..639." }
    }

    val seat: Int
        get() = globalIndex % 4

    val epoch: Int
        get() = 1 + (globalIndex / 4) % 3

    val split: TokenPairedSplit
        get() = if (globalIndex < 512) TokenPairedSplit.TRAIN else TokenPairedSplit.VALIDATION
}

/** Separate streams prevent reservoir length from changing proposal or future randomness. */
internal data class CpePairedSeeds(
    val gameSeed: Int,
    val reservoirSeed: Long,
    val proposalSeed: Long,
    val future: List<Long>,
) {
    init {
        require(future.size == 2 && all.size == 5 && all.distinct().size == 5) {
            "C/P/E roots require five distinct world/reservoir/proposal/future streams."
        }
    }

    @get:JsonIgnore
    val all: List<Long>
        get() = listOf(gameSeed.toLong(), reservoirSeed, proposalSeed) + future
}

/**
 * Uniform one-item reservoir over branching windows, with only public eligibility as input.
 * Completed-path sampling is a world curriculum, not an unbiased runtime visitation estimator.
 */
internal class CpePairedReservoir(private val request: CpePairedRequest, seed: Long) {
    private val random = Random(seed)
    var observedWindows: Int = 0
        private set

    var branchingWindows: Int = 0
        private set

    var selectedOrdinal: Int? = null
        private set

    fun observe(epoch: Int, decision: String, actions: Int): Boolean {
        if (epoch != request.epoch || decision !in request.head.decisions) return false
        require(actions > 0) { "An actor window requires at least one offered action." }
        observedWindows++
        if (actions == 1) return false
        branchingWindows++
        if (random.nextInt(branchingWindows) != 0) return false
        selectedOrdinal = branchingWindows
        return true
    }
}

/** The sampled behavior command is the delta reference, independently of the encoder prior. */
internal data class CpePairedProposals(val indices: List<Int>, val inclusionProbability: Double) {
    companion object {
        fun sample(actionCount: Int, sampledIndex: Int, seed: Long): CpePairedProposals {
            require(actionCount in 2..1024 && sampledIndex in 0 until actionCount) {
                "A C/P/E root requires all branching offers and its actual sampled reference."
            }
            val alternatives = (0 until actionCount).filter { it != sampledIndex }
            val count = minOf(8, alternatives.size)
            return CpePairedProposals(
                listOf(sampledIndex) + alternatives.shuffled(Random(seed)).take(count).sorted(),
                count.toDouble() / alternatives.size,
            )
        }
    }
}
