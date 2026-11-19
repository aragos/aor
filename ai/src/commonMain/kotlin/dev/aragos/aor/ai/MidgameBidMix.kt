package dev.aragos.aor.ai

import dev.aragos.aor.Command
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage

/**
 * Signed bids and sampling weights around 18. [requireRepeatedEvidence] requires support in two
 * thirds of recent public rounds. [focusScoreLeader] tests cheaper bids against the highest scoring
 * rival in the current public view.
 */
data class MidgameBidMix(
    val bids: List<Int>,
    val weights: List<Int>,
    val respectPublicOrder: Boolean = false,
    val requireRepeatedEvidence: Boolean = false,
    val focusScoreLeader: Boolean = false,
) {
    init {
        require(bids.size in 2..7 && bids.distinct().size == bids.size && 18 in bids) {
            "A midgame mix needs two through seven distinct amounts including the regular 18."
        }
        require(
            bids.all { it in 14..22 } && weights.size == bids.size && weights.all { it in 1..10000 }
        ) {
            "Each mixed amount needs a bounded legal bid and a positive sampling weight."
        }
        require(respectPublicOrder || !(requireRepeatedEvidence || focusScoreLeader)) {
            "Evidence thresholds and score-leader selection require public order forecasts."
        }
    }

    companion object {
        const val VERSION = "public-midgame-bid-mix-1"
    }
}

/** Vary one commonly offered midgame amount without reading any pending commitment. */
object MidgameBidMixPolicy {
    private const val REGULAR_BID = 18

    fun choose(
        view: GameView,
        actor: PlayerId,
        selected: String,
        seed: Int,
        config: MidgameBidMix,
    ): String {
        if (
            view.stage != Stage.TOKEN_PURCHASE ||
                (view.turnNumber ?: 0) !in 3..7 ||
                selected != "buy-tokens $REGULAR_BID"
        ) {
            return selected
        }
        val quotes = view.tokenPurchaseOffers.associateBy { it.amount }
        val ordinary = quotes[REGULAR_BID] ?: return selected
        val weights =
            config.bids.zip(config.weights).filter { (bid, _) ->
                val quote = quotes[bid] ?: return@filter false
                val addedCost = quote.cost - ordinary.cost
                val addedTokens = quote.tokensReceived - ordinary.tokensReceived
                if (addedCost > 0 && addedTokens != addedCost) return@filter false
                !config.respectPublicOrder || historicalOrderSupports(view, actor, bid, config)
            }
        if (weights.size < 2) return selected
        val target = ResidualPolicyMath.uniform(seed xor 0x2153F61D) * weights.sumOf { it.second }
        var cumulative = 0
        val sampled = weights.first { (_, weight) ->
            cumulative += weight
            target < cumulative
        }
        return Command.BuyTokens(sampled.first).toText()
    }

    private fun historicalOrderSupports(
        view: GameView,
        actor: PlayerId,
        bid: Int,
        config: MidgameBidMix,
    ): Boolean {
        if (bid == REGULAR_BID) return true
        val order = view.capitalOrder
        val ownPriority = order.indexOf(actor)
        val rows = view.pastTokenPurchases.takeLast(3)
        if (rows.isEmpty()) return false
        val focus =
            if (config.focusScoreLeader && bid < REGULAR_BID) {
                view.scores
                    .asSequence()
                    .mapNotNull { score ->
                        score.total?.takeIf { score.player != actor }?.let { score.player to it }
                    }
                    .maxByOrNull { it.second }
                    ?.first ?: return false
            } else null
        fun earlierRivals(row: Map<PlayerId, Int>, amount: Int): Int =
            order.withIndex().count { (position, rival) ->
                val past = row[rival] ?: return@count false
                rival != actor &&
                    (focus == null || focus == rival) &&
                    (past < amount || past == amount && position > ownPriority)
            }
        val support = rows.count { row ->
            val change = earlierRivals(row, bid) - earlierRivals(row, REGULAR_BID)
            if (bid < REGULAR_BID) change < 0 else change == 0
        }
        return when {
            config.requireRepeatedEvidence -> support * 3 >= rows.size * 2
            bid < REGULAR_BID -> support > 0
            else -> support * 2 >= rows.size
        }
    }
}
