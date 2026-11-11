package dev.aragos.aor.ai

import dev.aragos.aor.BoardState
import dev.aragos.aor.Commodity
import dev.aragos.aor.ExpansionPosition
import dev.aragos.aor.GameView
import dev.aragos.aor.ProvincePieces

/**
 * Research-only result-conditioned utility, relative to one unchanged root. Existing coefficients
 * retain their units, but this is a new policy objective, not the nominal attack score. White
 * pieces earn strategic presence, never invented routes. No bonus card or victory interruption is
 * valued.
 */
internal class BotExpansionOutcomeValue(
    view: GameView,
    private val root: ExpansionPosition,
    private val model: BotModel,
) {
    private val inputs = BotExpansionValueInputs.from(view, root.actor)
    private val context = BotFeatureContext.from(view)
    private val portfolio = inputs.portfolio()
    private val initialControl = control(root.context.board, satellites = 1.0)
    private val initialPresence = control(root.context.board, satellites = 1.5)
    private val initialIncome = inputs.income(root.context.board)?.income ?: 0

    /** [lostCommitments] counts only newly reserved tokens on attacks resolved as losses. */
    fun features(position: ExpansionPosition, lostCommitments: Int): Map<String, Double> {
        require(lostCommitments >= 0) { "Resolved lost commitments cannot be negative." }
        val board = position.context.board
        val after = inputs.withBoard(board)
        val additions =
            Commodity.entries.associateWith {
                after.count(root.actor, it) - inputs.count(root.actor, it)
            }
        val gains = portfolio.gains(additions)
        val rawCommodity =
            Commodity.entries.sumOf { commodity ->
                val beforeCount = inputs.count(root.actor, commodity).toDouble()
                val afterCount = after.count(root.actor, commodity).toDouble()
                (inputs.bases[commodity] ?: 0) *
                    (afterCount * afterCount - beforeCount * beforeCount) *
                    0.25
            }
        val denial =
            inputs.owners.keys
                .filter { it != root.actor }
                .sumOf { owner ->
                    Commodity.entries.sumOf { commodity ->
                        (inputs.payout(owner, commodity) - after.payout(owner, commodity)) *
                            (inputs.unplayedCards[commodity] ?: 0)
                    }
                }
        return context.expand(
            linkedMapOf(
                "tokens" to (position.poolTokens - root.poolTokens).toDouble(),
                "progress" to control(board, satellites = 1.0) - initialControl,
                "reach" to control(board, satellites = 1.5) - initialPresence,
                "commodity" to rawCommodity,
                "income" to ((after.income(board)?.income ?: 0) - initialIncome).toDouble(),
                "risk" to lostCommitments.toDouble(),
                BotEconomicFeature.HELD_COMMODITY.key to gains.held,
                BotEconomicFeature.PUBLIC_COMMODITY.key to gains.public,
                BotEconomicFeature.COMMODITY_DENIAL.key to denial,
            )
        )
    }

    fun value(position: ExpansionPosition, lostCommitments: Int): Double =
        features(position, lostCommitments).entries.sumOf { (feature, value) ->
            if (BotModels.reservedFeature(feature)) 0.0 else value * (model.weights[feature] ?: 0.0)
        }

    private fun control(board: BoardState, satellites: Double): Double =
        root.context.configuration.provinces.sumOf { province ->
            val fraction =
                when (val pieces = board.pieces[province.id]) {
                    is ProvincePieces.Marker -> if (pieces.owner == root.actor) 1.0 else 0.0
                    is ProvincePieces.Tokens ->
                        ((pieces.holdings[root.actor]?.total ?: 0).toDouble() /
                                province.marketNumber)
                            .coerceAtMost(1.0)
                    null -> 0.0
                }
            fraction * if (province.isSatellite) satellites else 1.0
        }
}
