package dev.aragos.aor.ai

import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.OrderBoxes
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TokenPurchase

/** Versioned public-history extension of TOKEN2, in the same role and canonical action order. */
internal class TokenOrderHistoryFeatures(view: GameView, seat: PlayerId) {
    private val players = view.players.sortedBy { RawActorState.role(view, seat, it.id) }
    private val past = view.pastTokenPurchases
    private val boxes = OrderBoxes.forPlayers(players.size)
    private val rivalRows = past.map { bids ->
        players.drop(1).map { rival ->
            if (rival.misery == MiseryStep.CHAOS) 0 else bid(bids, rival.id)
        }
    }

    init {
        require(view.stage == Stage.TOKEN_PURCHASE && players.first().id == seat) {
            "Bid history features require an acting seat at the token purchase window."
        }
    }

    fun state() = buildMap {
        if (past.isNotEmpty()) {
            put("bidHistory.sampleShare", past.size.toDouble() / TokenPurchase.PAST_REVEAL_LIMIT)
            players.forEachIndexed { role, player ->
                val amounts = past.map { bid(it, player.id).toDouble() }
                put("bidHistory.role.$role.last", scaledBid(amounts.last()))
                put("bidHistory.role.$role.mean", scaledBid(amounts.average()))
                put("bidHistory.role.$role.span", scaledBid(amounts.max() - amounts.min()))
            }
        }
    }
        .filterValues { it != 0.0 }

    fun action(amount: Int): Map<String, Double> {
        if (past.isEmpty()) return emptyMap()
        return buildMap {
            val count = rivalRows.size.toDouble()
            var bestBox = 0
            var worstBox = 0
            val before = IntArray(players.size - 1)
            val equal = IntArray(players.size - 1)
            for (row in rivalRows) {
                var lower = 0
                var tied = 0
                row.forEachIndexed { index, rival ->
                    when {
                        amount > rival -> lower++
                        amount < rival -> before[index]++
                        else -> {
                            equal[index]++
                            tied++
                        }
                    }
                }
                bestBox += boxes[lower]
                worstBox += boxes[lower + tied]
            }
            before.indices.forEach { index ->
                val role = index + 1
                put("bidOrder.role.$role.before", before[index] / count)
                put("bidOrder.role.$role.tie", equal[index] / count)
            }
            // A tie ranks somewhere in this interval, depending on the players' insertion choices.
            put("bidOrder.bestBox", bestBox / (6.0 * count))
            put("bidOrder.worstBox", worstBox / (6.0 * count))
        }
            .filterValues { it != 0.0 }
    }

    private fun bid(row: Map<PlayerId, Int>, player: PlayerId): Int =
        requireNotNull(row[player]) {
            "Every public reveal row requires each registered player's bid."
        }

    private fun scaledBid(amount: Double): Double = (amount / 200.0).coerceIn(-16.0, 16.0)

    companion object {
        val stateNames =
            listOf("bidHistory.sampleShare") +
                (0..5).flatMap { role ->
                    listOf("last", "mean", "span").map { "bidHistory.role.$role.$it" }
                }
        val actionNames =
            listOf("bidOrder.bestBox", "bidOrder.worstBox") +
                (1..5).flatMap { role ->
                    listOf("before", "tie").map { "bidOrder.role.$role.$it" }
                }
    }
}
