package dev.aragos.aor.ai

import dev.aragos.aor.BoardCommand
import dev.aragos.aor.EconomyTables
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import kotlin.math.exp

/**
 * [marginScale] is in final-score dollars. [bonus] is in the calibrated residual score units.
 * Commodity weights discount projected final payments for uncertainty and remaining decisions.
 */
data class FinalExpansionConfig(
    val marginScale: Double,
    val bonus: Double,
    val ownedCommodityWeight: Double,
    val publicCommodityWeight: Double,
) {
    init {
        require(marginScale.isFinite() && marginScale in 5.0..200.0) {
            "Final expansion needs a score margin scale between five and 200 dollars."
        }
        require(bonus.isFinite() && bonus in 0.0..16.0) {
            "Final expansion adjustment must be between zero and 16 score units."
        }
        require(
            ownedCommodityWeight.isFinite() &&
                ownedCommodityWeight in 0.0..1.0 &&
                publicCommodityWeight.isFinite() &&
                publicCommodityWeight in 0.0..1.0
        ) {
            "Final expansion commodity weights must be between zero and one."
        }
    }

    companion object {
        const val VERSION = "last-turn-score-margin-1"
    }
}

/**
 * Score the near-term effect on the strongest projected rival before the final income and cards.
 */
object FinalExpansionPolicy {
    fun choose(
        view: GameView,
        player: PlayerId,
        observation: TerritoryObservation,
        normal: IncumbentResidualChoice,
        config: FinalExpansionConfig,
    ): IncumbentResidualChoice {
        if (view.stage != Stage.EXPANSION || !view.endingTriggered || config.bonus == 0.0)
            return normal
        val players = view.players.associateBy { it.id }
        val totals =
            view.scores
                .mapNotNull { score ->
                    val total = score.total ?: return@mapNotNull null
                    score.player to
                        total.toDouble() + (players[score.player]?.income?.ordinary ?: 0)
                }
                .toMap()
        val own = totals[player] ?: return normal
        val rivals = totals.filterKeys { it != player }
        val strongest = rivals.values.maxOrNull() ?: return normal
        val table = EconomyTables.incomeFor(view.players.size)
        val ownMarkers =
            requireNotNull(players[player]) { "The expanding seat must be registered." }
                .boardMarkers
        val markers = view.board.associate { it.id to it.marker?.owner }
        val offers = view.expansionOffers.associateBy { it.destination }
        val economic by lazy(LazyThreadSafetyMode.NONE) { BotEconomicProjection(view, player) }
        fun margin(ours: Double, theirs: Double): Double =
            1.0 / (1.0 + exp((theirs - ours) / config.marginScale))
        val before = margin(own, strongest)
        val adjusted =
            normal.scores.zip(observation.actions) { score, action ->
                val command = TextCodec.parseCommand(action.command)
                val destination =
                    when (command) {
                        is BoardCommand.Place -> command.province
                        is BoardCommand.Attack -> command.province
                        else -> return@zip score
                    }
                val offer = offers[destination] ?: return@zip score
                if (!offer.establishesMarker || offer.blockers.isNotEmpty()) return@zip score
                if (command is BoardCommand.Place && command.tokens != offer.requiredTokens)
                    return@zip score
                val probability =
                    if (command is BoardCommand.Attack) {
                        offer.competitionWinProbability ?: return@zip score
                    } else 1.0
                if (probability <= 0.0) return@zip score
                val effects =
                    if (config.ownedCommodityWeight + config.publicCommodityWeight > 0.0) {
                        economic.expansion(destination, offer, offer.requiredTokens)
                    } else emptyMap()
                val knownPayment =
                    (effects[BotEconomicFeature.HELD_COMMODITY.key] ?: 0.0) / probability
                val possiblePayment =
                    (effects[BotEconomicFeature.PUBLIC_COMMODITY.key] ?: 0.0) / probability
                val possibleDenial =
                    (effects[BotEconomicFeature.COMMODITY_DENIAL.key] ?: 0.0) / probability
                val incomeGain =
                    offer.incomeAfter?.let { after -> after - (offer.incomeBefore ?: after) }
                        ?: (table[ownMarkers + 1] - table[ownMarkers])
                val projectedOwn =
                    own +
                        incomeGain +
                        config.ownedCommodityWeight * knownPayment +
                        config.publicCommodityWeight * possiblePayment
                val defender = if (command is BoardCommand.Attack) markers[destination] else null
                val projectedRival = rivals.maxOf { (rival, total) ->
                    if (rival != defender) total
                    else {
                        val count =
                            requireNotNull(players[rival]) {
                                    "The defending seat must be registered."
                                }
                                .boardMarkers
                        total -
                            (table[count] - table[count - 1]) -
                            config.publicCommodityWeight * possibleDenial
                    }
                }
                score + config.bonus * probability * (margin(projectedOwn, projectedRival) - before)
            }
        if (adjusted == normal.scores) return normal
        return IncumbentResidualPolicy.selectScores(
            observation,
            normal.residualDifferences,
            adjusted,
        )
    }
}
