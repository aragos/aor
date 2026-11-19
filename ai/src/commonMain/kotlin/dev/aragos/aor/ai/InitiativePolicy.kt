package dev.aragos.aor.ai

import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.OrderBoxes
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TokenPurchaseOfferView
import kotlin.math.pow

/**
 * Absolute bids for the first turn. An optional second mix applies to players in the later half of
 * capital selection, when they can place themselves relative to more rivals on tied bids.
 * [retainOrdinaryForLaterCapital] keeps the regular opening behavior for that group.
 * [participation] is the first-turn probability of choosing this mix instead of a regular bid.
 */
data class OpeningBidMix(
    val bids: List<Int>,
    val laterCapitalBids: List<Int> = emptyList(),
    val retainOrdinaryForLaterCapital: Boolean = false,
    val participation: Double = 1.0,
) {
    init {
        require(bids.isNotEmpty()) { "An opening mix requires at least one default bid." }
        require(
            listOf(bids, laterCapitalBids).all {
                it == it.distinct() && it.all { bid -> bid in 0..36 }
            }
        ) {
            "An opening mix requires distinct bids between zero and 36 in each group."
        }
        require(!retainOrdinaryForLaterCapital || laterCapitalBids.isEmpty()) {
            "A later-capital mix cannot also request the regular opening behavior."
        }
        require(participation.isFinite() && participation in 0.0..1.0) {
            "Opening mix participation must be a probability between zero and one."
        }
    }
}

/**
 * Tunable initiative bids use printed order boxes and authoritative quotes. [maximumTokenLoss]
 * limits tokens sacrificed relative to the normal choice. [tokenPenalty] uses expected printed box
 * numbers per forgone token. [participation] is the chance of using a positive-scoring switch.
 * [attackBonus] tunes early-box attacks in calibrated expansion score units. [historyDecay] is the
 * weight of a revealed row relative to the next newer row. A value of one weights all rows equally.
 * [bidScoreTolerance] bounds the estimated score loss when mixing similar bids. [openingSpread]
 * limits the amount sacrificed for an opening mix before any bids have been revealed.
 * [maximumExtraTokens] also permits buying this many more tokens, for no more extra dollars.
 * [planWeight] scores each dollar of projected peaceful expansion opportunity in box units.
 * [extraCashPenalty] charges those same score units per dollar above the original bid.
 * [counterAnticipation] is the weight of a forecast when a rival repeatedly tracks our usual bid.
 * [respectRivalCash] clips historical predictions to each rival's currently published cash.
 * [openingMix] selects from absolute first-turn bids when at least one can be bought.
 */
data class InitiativePolicyConfig(
    val maximumTokenLoss: Int,
    val tokenPenalty: Double,
    val participation: Double,
    val requireAffordableContest: Boolean,
    val attackBonus: Double,
    val historyDecay: Double = 1.0,
    val bidScoreTolerance: Double = 0.0,
    val openingSpread: Int = 0,
    val maximumExtraTokens: Int = 0,
    val planWeight: Double = 0.0,
    val extraCashPenalty: Double = 0.15,
    val counterAnticipation: Double = 0.0,
    val respectRivalCash: Boolean = false,
    val openingMix: OpeningBidMix? = null,
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
        require(bidScoreTolerance.isFinite() && bidScoreTolerance in 0.0..1.0) {
            "Initiative bid score tolerance must be between zero and one."
        }
        require(openingSpread in 0..maximumTokenLoss) {
            "The opening mix must respect the maximum token sacrifice."
        }
        require(maximumExtraTokens in 0..8) { "The bid may buy at most eight extra tokens." }
        require(planWeight.isFinite() && planWeight in 0.0..1.0) {
            "The expansion plan weight must be between zero and one."
        }
        require(extraCashPenalty.isFinite() && extraCashPenalty in 0.0..1.0) {
            "The extra token cash penalty must be between zero and one."
        }
        require(counterAnticipation.isFinite() && counterAnticipation in 0.0..1.0) {
            "The counter anticipation weight must be between zero and one."
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
        if (config.participation == 0.0) return normal
        val hasHistory = view.pastTokenPurchases.isNotEmpty()
        if (!hasHistory && config.openingSpread == 0 && config.openingMix == null) return normal
        val draw = TerritoryPolicy.uniform(seed xor 0x6EA14F52)
        if (draw >= config.participation) return normal
        if (!hasHistory && view.turnNumber == 1 && config.openingMix != null) {
            val mix = config.openingMix
            val capitalPosition = view.capitalOrder.indexOf(player)
            val later = capitalPosition >= (view.capitalOrder.size + 1) / 2
            val chooseMix =
                mix.participation == 1.0 ||
                    TerritoryPolicy.uniform(seed xor 0x2A68C5D9) < mix.participation
            if (chooseMix && (!later || !mix.retainOrdinaryForLaterCapital)) {
                val amounts =
                    if (later && mix.laterCapitalBids.isNotEmpty()) mix.laterCapitalBids
                    else mix.bids
                val legal = view.tokenPurchaseOffers.filter { it.amount in amounts }
                if (legal.isNotEmpty()) return bidCommand(sampleBid(legal, seed))
            }
        }
        val originalAmount = LocalTokenNeighborPolicy.amount(normal)
        val original = view.tokenPurchaseOffers.single { it.amount == originalAmount }
        if (original.tokensReceived == 0) return normal
        val contest = view.expansionForecast?.contestedMarkets?.minOfOrNull { it.requiredTokens }
        val smaller =
            view.tokenPurchaseOffers.filter { quote ->
                quote.amount in 1 until original.amount &&
                    original.tokensReceived - quote.tokensReceived <= config.maximumTokenLoss &&
                    (!config.requireAffordableContest ||
                        contest != null && quote.poolAfter >= contest)
            }
        if (!hasHistory) {
            val opening = smaller.filter { it.amount >= original.amount - config.openingSpread }
            return bidCommand(sampleBid(opening + original, seed))
        }
        val larger =
            if (config.maximumExtraTokens == 0) emptyList()
            else {
                view.tokenPurchaseOffers.filter { quote ->
                    quote.amount > original.amount &&
                        quote.tokensReceived - original.tokensReceived in
                            1..config.maximumExtraTokens &&
                        quote.cost - original.cost in 1..config.maximumExtraTokens
                }
            }
        val plans =
            if (config.planWeight > 0.0 && view.expansionForecast != null) {
                BotTokenPlanProjection(view, player)
            } else null
        val originalPlan = plans?.plan(original.poolAfter)?.opportunity ?: 0.0
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
                val cash = rival.cash.coerceAtLeast(0)
                val forecast = if (config.respectRivalCash) bid.coerceIn(-cash, cash) else bid
                forecast to
                    (requireNotNull(priority[rival.id]) { "Rivals need capital choice order." } >
                        priorityOfPlayer)
            }
        }
        val weights =
            DoubleArray(rows.size) { index -> config.historyDecay.pow(rows.lastIndex - index) }
        val totalWeight = weights.sum()
        val expectedCounters =
            if (config.counterAnticipation > 0.0) {
                BidResponseForecast.next(view.pastTokenPurchases, player, rivals)
            } else emptyMap()
        val responses =
            if (expectedCounters.isEmpty()) null
            else
                predictions.map { row ->
                    row.mapIndexed { index, (bid, tiePriority) ->
                        (expectedCounters[rivals[index].id] ?: bid) to tiePriority
                    }
                }
        fun meanBox(amount: Int, scenarios: List<List<Pair<Int, Boolean>>>): Double =
            scenarios.withIndex().sumOf { (index, row) ->
                weights[index] *
                    boxes[
                        row.count { (bid, tiePriority) ->
                            bid < amount || bid == amount && tiePriority
                        }]
            } / totalWeight
        fun expectedBox(amount: Int): Double {
            val historical = meanBox(amount, predictions)
            if (responses == null) return historical
            return historical +
                config.counterAnticipation * (meanBox(amount, responses) - historical)
        }
        val baselineBox = expectedBox(original.amount)
        fun gain(quote: TokenPurchaseOfferView): Double =
            baselineBox -
                expectedBox(quote.amount) -
                config.tokenPenalty * (original.tokensReceived - quote.tokensReceived) +
                config.planWeight *
                    ((plans?.plan(quote.poolAfter)?.opportunity ?: 0.0) - originalPlan) -
                config.extraCashPenalty * (quote.cost - original.cost).coerceAtLeast(0)
        val scored = (smaller + larger).map { it to gain(it) }.filter { it.second > 0.0 }
        val (best, bestScore) =
            scored.maxWithOrNull(compareBy({ it.second }, { it.first.amount })) ?: return normal
        if (config.bidScoreTolerance == 0.0) return bidCommand(best)
        val nearBest = scored.filter { (_, score) -> bestScore - score <= config.bidScoreTolerance }
        val shortlist =
            nearBest.map { (quote, _) -> quote } +
                listOfNotNull(original.takeIf { bestScore <= config.bidScoreTolerance })
        return bidCommand(sampleBid(shortlist, seed))
    }

    private fun bidCommand(quote: TokenPurchaseOfferView): String = "buy-tokens ${quote.amount}"

    private fun sampleBid(
        choices: List<TokenPurchaseOfferView>,
        seed: Int,
    ): TokenPurchaseOfferView {
        val draw = TerritoryPolicy.uniform(seed xor 0x6F7B4439)
        return choices[(draw * choices.size).toInt()]
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
