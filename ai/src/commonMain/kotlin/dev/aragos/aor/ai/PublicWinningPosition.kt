package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceCategory
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId

/**
 * The projected final score is absent for an eliminated seat. Values use the named public units.
 */
data class PublicSeatPosition(
    val player: PlayerId,
    val active: Boolean,
    val features: List<Double>,
)

/** Public, seat-symmetric inputs for comparing threats during expansion. */
object PublicWinningPosition {
    const val VERSION = "public-winning-position-1"
    val columns =
        listOf(
            "scorePoints",
            "cashDollars",
            "advancePoints",
            "miseryPoints",
            "incomeDollars",
            "markets",
            "handCards",
            "stockTokens",
            "expansionPoolTokens",
            "orderBox",
            "shipLevel",
            "scienceAdvances",
            "commerceAdvances",
            "militaryCards",
            "outstandingCommodityPayoutDollars",
            "largestOutstandingPayoutDollars",
        )

    fun seats(view: GameView): List<PublicSeatPosition> {
        val scores = view.scores.associateBy { it.player }
        val categories = view.catalog?.advances.orEmpty().associate { it.id to it.category }
        val outside = view.commodityCards.associate { it.commodity to (it.unplayed ?: 0) }
        val payouts =
            view.commodityPayouts
                .flatMap { quote ->
                    val copies = outside[quote.commodity] ?: 0
                    if (copies <= 0) emptyList()
                    else
                        quote.owners.map { owner ->
                            owner.player to owner.payout.toDouble() * copies
                        }
                }
                .groupBy({ it.first }, { it.second })
        return view.players.map { seat ->
            val score =
                checkNotNull(scores[seat.id]) {
                    "A public position requires the visible score for every registered seat."
                }
            val commodityPayouts = payouts[seat.id].orEmpty()
            PublicSeatPosition(
                player = seat.id,
                active = score.total != null,
                features =
                    listOf(
                        (score.total ?: 0).toDouble(),
                        seat.cash.toDouble(),
                        score.advancePoints.toDouble(),
                        (score.misery ?: 1000).toDouble(),
                        (seat.income?.ordinary ?: 0).toDouble(),
                        seat.boardMarkers.toDouble(),
                        seat.handCount.toDouble(),
                        seat.stockTokens.toDouble(),
                        (if (seat.poolAvailable) seat.poolTokens else 0).toDouble(),
                        (view.orderBoxes[seat.id] ?: 0).toDouble(),
                        seat.ships.level.toDouble(),
                        seat.advances
                            .count { categories[it] == AdvanceCategory.SCIENCE }
                            .toDouble(),
                        seat.advances
                            .count { categories[it] == AdvanceCategory.COMMERCE }
                            .toDouble(),
                        view.effects.military.count { it.player == seat.id }.toDouble(),
                        commodityPayouts.sum(),
                        commodityPayouts.maxOrNull() ?: 0.0,
                    ),
            )
        }
    }
}
