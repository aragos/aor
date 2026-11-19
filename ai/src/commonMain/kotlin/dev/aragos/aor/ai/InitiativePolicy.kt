package dev.aragos.aor.ai

import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.OrderBoxes
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TokenPurchaseOfferView
import kotlin.math.pow

/**
 * Tunable initiative bids use printed order boxes and authoritative quotes. [maximumTokenLoss]
 * limits tokens sacrificed relative to the normal choice. [tokenPenalty] uses expected printed box
 * numbers per forgone token. [participation] is the chance of using a positive-scoring switch.
 * [attackBonus] tunes early-box attacks in calibrated expansion score units. [historyDecay] is the
 * weight of a revealed row relative to the next newer row. A value of one weights all rows equally.
 */
data class InitiativePolicyConfig(
    val maximumTokenLoss: Int,
    val tokenPenalty: Double,
    val participation: Double,
    val requireAffordableContest: Boolean,
    val attackBonus: Double,
    val historyDecay: Double = 1.0,
) {
    init {
        require(maximumTokenLoss in 0..8) { "Initiative may give up at most eight tokens." }
        require(tokenPenalty.isFinite() && tokenPenalty in 0.0..3.0) {
            "Initiative token penalty must be between zero and three printed boxes per token."
        }
        require(participation.isFinite() && participation in 0.0..1.0) {
            "Initiative participation must be between zero and one."
        }
        require(attackBonus.isFinite() && attackBonus in 0.0..8.0) {
            "Initiative expansion bonus must be between zero and eight calibrated score units."
        }
        require(historyDecay.isFinite() && historyDecay in 0.0..1.0) {
            "Initiative history decay must be between zero and one."
        }
    }

    companion object {
        const val VERSION = "public-initiative-1"
    }
}

/** Compare candidate bids against joint rows of strictly past, publicly revealed purchases. */
object InitiativePolicy {
    fun bid(
        view: GameView,
        player: PlayerId,
        normal: String,
        seed: Int,
        config: InitiativePolicyConfig,
    ): String {
        require(view.stage == Stage.TOKEN_PURCHASE) { "Initiative bids require a purchase window." }
        if (config.participation == 0.0 || view.pastTokenPurchases.isEmpty()) return normal
        val draw = TerritoryPolicy.uniform(seed xor 0x6EA14F52)
        if (draw >= config.participation) return normal
        val originalAmount = LocalTokenNeighborPolicy.amount(normal)
        val original = view.tokenPurchaseOffers.single { it.amount == originalAmount }
        if (original.tokensReceived == 0) return normal
        val boxes = OrderBoxes.forPlayers(view.players.size)
        val priority = view.capitalOrder.withIndex().associate { it.value to it.index }
        val priorityOfPlayer =
            requireNotNull(priority[player]) { "The bidder needs a capital choice." }
        val rivals = view.players.filter { it.id != player }
        val rows = view.pastTokenPurchases.takeLast(4)
        val predictions = rows.map { bids ->
            rivals.map { rival ->
                val bid =
                    if (rival.misery == MiseryStep.CHAOS) 0
                    else
                        requireNotNull(bids[rival.id]) { "A revealed row needs every rival's bid." }
                bid to
                    (requireNotNull(priority[rival.id]) { "Rivals need capital choice order." } >
                        priorityOfPlayer)
            }
        }
        val weights =
            DoubleArray(rows.size) { index -> config.historyDecay.pow(rows.lastIndex - index) }
        val totalWeight = weights.sum()
        fun expectedBox(amount: Int): Double =
            predictions.withIndex().sumOf { (index, row) ->
                weights[index] *
                    boxes[
                        row.count { (bid, tiePriority) ->
                            bid < amount || bid == amount && tiePriority
                        }]
            } / totalWeight
        val baselineBox = expectedBox(original.amount)
        val contest = view.expansionForecast?.contestedMarkets?.minOfOrNull { it.requiredTokens }
        val candidates =
            view.tokenPurchaseOffers.filter { quote ->
                quote.amount in 1 until original.amount &&
                    original.tokensReceived - quote.tokensReceived <= config.maximumTokenLoss &&
                    (!config.requireAffordableContest ||
                        contest != null && quote.poolAfter >= contest)
            }
        fun gain(quote: TokenPurchaseOfferView): Double =
            baselineBox -
                expectedBox(quote.amount) -
                config.tokenPenalty * (original.tokensReceived - quote.tokensReceived)
        val best =
            candidates
                .map { it to gain(it) }
                .filter { it.second > 0.0 }
                .maxWithOrNull(compareBy({ it.second }, { it.first.amount }))
                ?.first
        return best?.let { "buy-tokens ${it.amount}" } ?: normal
    }

    fun expansion(
        view: GameView,
        player: PlayerId,
        observation: TerritoryObservation,
        normal: IncumbentResidualChoice,
        config: InitiativePolicyConfig,
    ): IncumbentResidualChoice {
        if (config.attackBonus == 0.0 || (view.orderBoxes[player] ?: 6) > 3) return normal
        val scores =
            normal.scores.zip(observation.actions) { score, action ->
                if ("kind.attack" !in action.features) score
                else score + config.attackBonus * (action.features["winProbability"] ?: 0.0)
            }
        if (scores == normal.scores) return normal
        return IncumbentResidualPolicy.selectScores(observation, normal.residualDifferences, scores)
    }
}
