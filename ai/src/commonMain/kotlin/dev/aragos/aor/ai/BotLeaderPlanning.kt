package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.AppliedLeaderDiscount
import dev.aragos.aor.CardKind
import dev.aragos.aor.GameView
import dev.aragos.aor.LegalChoices
import dev.aragos.aor.PlayerEconomy
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.ViolationCode

/** Forecast stages, separate from legal card-stage commands. */
enum class BotPurchaseForecastStage(val decision: String) {
    SHIPPING("purchase-shipping"),
    ADVANCES("purchase-advances"),
}

/** One finite purchase forecast; candidate indices refer to its owner's shared pool. */
data class BotPurchaseForecast(
    val stage: BotPurchaseForecastStage,
    val cash: Int,
    val stabilization: Double,
    val candidates: List<Int>,
    val reserveShortfallBasis: Map<String, Double>? = null,
    val advancePlanning: BotAdvancePlanning? = null,
) {
    init {
        require(cash >= 0 && stabilization.isFinite() && stabilization >= 0.0) {
            "Purchase forecasts require nonnegative cash and stabilization dollars."
        }
        require(candidates.size in 1..27 && candidates.distinct().size == candidates.size) {
            "A purchase forecast requires unique pass and at most twenty-six advance candidates."
        }
        requireReserveShortfallBasis(reserveShortfallBasis)
        require(
            stage == BotPurchaseForecastStage.ADVANCES ||
                reserveShortfallBasis == null && advancePlanning == null
        ) {
            "Only advance forecasts may carry advance or stabilization planning metadata."
        }
    }
}

/**
 * Shipping is scored first; the selected shipping command identifies the following advance plan.
 */
data class BotLeaderContinuation(val shipping: Int, val advances: Map<String, Int>) {
    init {
        require(
            shipping >= 0 &&
                advances.keys.all { it in setOf("pass", "upgrade-ships") } &&
                "pass" in advances &&
                advances.values.all { it >= 0 }
        ) {
            "A leader continuation requires bounded shipping-command forecast references."
        }
    }
}

/**
 * Both continuations start with identical post-play cash and hand costs. Only this leader's
 * authorized credit differs. Null continuations mean no Purchase remains. [retainedDiscount] is an
 * unowned nominal dollar option, scaled by the remaining horizon, not guaranteed future cash.
 */
data class BotLeaderAlternative(
    val withoutLeader: BotLeaderContinuation?,
    val withLeader: BotLeaderContinuation?,
    val retainedDiscount: Double,
) {
    init {
        require((withoutLeader == null) == (withLeader == null)) {
            "Leader purchase forecasts must provide both comparison arms or neither."
        }
        require(retainedDiscount.isFinite() && retainedDiscount in 0.0..1000.0) {
            "A retained leader option requires bounded nonnegative dollar value."
        }
        require(withLeader != null || retainedDiscount == 0.0) {
            "A leader without a future purchase window has no retained discount option."
        }
    }
}

/**
 * Flat, bounded observation-only forecasts. Candidate and scenario pooling shares identical
 * baselines across leaders and protection choices. No hidden hand, future draw or saved state is
 * included. Unusually large hands that exceed these limits retain the ordinary policy instead.
 */
data class BotLeaderPlanning(
    val candidates: List<BotCandidate>,
    val forecasts: List<BotPurchaseForecast>,
    val leaders: Map<String, BotLeaderAlternative>,
    val cashCards: Set<String>,
) {
    init {
        require(
            candidates.size <= 512 &&
                forecasts.size <= 64 &&
                leaders.size in 1..128 &&
                cashCards.size <= 128
        ) {
            "Leader planning exceeds its finite candidate, forecast or card bounds."
        }
        for (forecast in forecasts) {
            require(forecast.candidates.all { it in candidates.indices }) {
                "A purchase forecast must reference existing pooled candidates."
            }
            val offered = forecast.candidates.map { candidates[it] }
            require(
                offered.map(BotCandidate::command).distinct().size == offered.size &&
                    offered.any { it.command == "pass" } &&
                    offered.all {
                        it.command == "pass" ||
                            when (forecast.stage) {
                                BotPurchaseForecastStage.SHIPPING -> it.command == "upgrade-ships"
                                BotPurchaseForecastStage.ADVANCES -> it.family == "advance"
                            }
                    }
            ) {
                "Purchase forecast commands must match their declared stage and include pass."
            }
            require(
                forecast.advancePlanning == null ||
                    forecast.advancePlanning.steps.keys.all { command ->
                        offered.any { it.command == command && it.family == "advance" }
                    }
            ) {
                "Advance planning facts must belong to this forecast's offered advance commands."
            }
        }
        for (leader in leaders.values) {
            for (continuation in listOfNotNull(leader.withoutLeader, leader.withLeader)) {
                require(
                    continuation.shipping in forecasts.indices &&
                        forecasts[continuation.shipping].stage ==
                            BotPurchaseForecastStage.SHIPPING &&
                        continuation.advances.values.all {
                            it in forecasts.indices &&
                                forecasts[it].stage == BotPurchaseForecastStage.ADVANCES
                        }
                ) {
                    "Leader continuations must reference forecasts of the correct purchase stage."
                }
                require(
                    continuation.advances.keys ==
                        forecasts[continuation.shipping]
                            .candidates
                            .map { candidates[it].command }
                            .toSet()
                ) {
                    "Each forecast shipping choice requires its corresponding advance continuation."
                }
            }
            val without = leader.withoutLeader
            val with = leader.withLeader
            if (without != null && with != null) {
                val baseline = forecasts[without.shipping]
                val discounted = forecasts[with.shipping]
                require(
                    baseline.cash == discounted.cash &&
                        baseline.stabilization == discounted.stabilization
                ) {
                    "Leader comparison arms must start with identical post-play cash and hand costs."
                }
            }
        }
    }
}

/** Shared purchase valuation, enabled only for HARD and an exact unit gate. */
internal object BotLeaderTiming {
    const val GATE = "planning.leaderTiming"
    const val RETENTION = "planning.retainedLeader"

    fun scores(
        observation: BotObservation,
        model: BotModel,
        ordinary: Map<String, Double>,
    ): Map<String, Double> {
        if (model.weights[GATE] != 1.0) return ordinary
        val planning = observation.leaderPlanning ?: return ordinary
        val cached = mutableMapOf<Int, BotPlanEvaluation>()
        fun evaluate(index: Int): BotPlanEvaluation =
            cached.getOrPut(index) {
                val forecast = planning.forecasts[index]
                BotPolicy.evaluatePlan(
                    observation.copy(
                        decision = forecast.stage.decision,
                        context =
                            observation.context +
                                mapOf(
                                    "cash" to forecast.cash.toDouble(),
                                    "stabilization" to forecast.stabilization,
                                ),
                        availableCash = forecast.cash,
                        candidates = forecast.candidates.map { planning.candidates[it] },
                        expansionPlanning = null,
                        reserveShortfallBasis = forecast.reserveShortfallBasis,
                        advancePlanning = forecast.advancePlanning,
                        leaderPlanning = null,
                    ),
                    model,
                )
            }
        fun value(continuation: BotLeaderContinuation?): Double {
            if (continuation == null) return 0.0
            val shipping = evaluate(continuation.shipping)
            val advance =
                checkNotNull(continuation.advances[shipping.candidate.command]) {
                    "Every forecast shipping choice requires an advance continuation."
                }
            return shipping.value + evaluate(advance).value
        }
        val byCommand = observation.candidates.associateBy(BotCandidate::command)
        return ordinary.mapValues { (command, score) ->
            val leader = planning.leaders[command] ?: return@mapValues score
            val candidate =
                checkNotNull(byCommand[command]) {
                    "Leader planning requires its offered card command."
                }
            val nominal =
                candidate.features.entries.sumOf { (name, amount) ->
                    if (
                        name == "discount" ||
                            name.endsWith(".discount") && !BotTokenValue.reserved(name)
                    )
                        amount * (model.weights[name] ?: 0.0)
                    else 0.0
                }
            score - nominal + value(leader.withLeader) -
                value(leader.withoutLeader) -
                leader.retainedDiscount * (model.weights[RETENTION] ?: 0.0)
        }
    }

    /**
     * A worthwhile deterministic own commodity receipt can be taken before committing a leader. The
     * next observation then revalues actual cash. No other player's future play is assumed.
     */
    fun deferForCash(
        observation: BotObservation,
        chosen: BotCandidate,
        model: BotModel,
        ordinary: Map<String, Double>,
    ): BotCandidate {
        if (model.weights[GATE] != 1.0) return chosen
        val planning = observation.leaderPlanning ?: return chosen
        if (planning.leaders[chosen.command]?.withLeader == null) return chosen
        val pass = ordinary["pass"] ?: return chosen
        return observation.candidates
            .filter { it.command in planning.cashCards && (ordinary[it.command] ?: pass) > pass }
            .sortedWith(
                compareByDescending<BotCandidate> { ordinary[it.command] }.thenBy { it.command }
            )
            .firstOrNull() ?: chosen
    }
}

/**
 * Quotes and hypothetical candidate features use only the ordinary owner view. Current eligible
 * advances and the existing Research/Interest planner define the horizon; new prerequisites, future
 * Written Record bonuses, Patronage claims and other players' card receipts are excluded. Forecasts
 * never authorize a card-stage purchase or promise that later play will follow a plan.
 */
internal object BotLeaderProjection {
    fun from(
        view: GameView,
        seat: PlayerId,
        candidates: List<BotCandidate>,
        endgame: Double,
    ): BotLeaderPlanning? {
        // Older or incomplete public projections cannot establish the next shipping budget.
        // Final Card Play is different: its absent forecast represents a known closed window.
        if (view.stage == Stage.PLAY_CARDS && view.purchaseShippingForecast == null) return null
        val references =
            view.hand.filter { it.definition.kind == CardKind.LEADER }.map { it.reference }.toSet()
        if (references.isEmpty()) return null
        val leaders = candidates.filter { it.group.removePrefix("card.") in references }
        if (leaders.isEmpty() || leaders.size > 128) return null
        return Builder(view, seat).build(leaders, endgame)
    }

    private data class ForecastKey(
        val stage: BotPurchaseForecastStage,
        val cash: Int,
        val handCount: Int,
        val prices: List<Int?>,
    )

    private class Builder(private val original: GameView, private val seat: PlayerId) {
        private val self = original.players.single { it.id == seat }
        private val candidatePool = mutableListOf<BotCandidate>()
        private val candidateIndices = mutableMapOf<BotCandidate, Int>()
        private val forecasts = mutableListOf<BotPurchaseForecast>()
        private val forecastIndices = mutableMapOf<ForecastKey, Int>()

        fun build(leaders: List<BotCandidate>, endgame: Double): BotLeaderPlanning? {
            val alternatives = mutableMapOf<String, BotLeaderAlternative>()
            for (candidate in leaders) {
                val reference = candidate.group.removePrefix("card.")
                val offer = original.cardOffers.single { it.reference == reference }
                val credits = offer.leaderDiscounts.filter { it.advance !in self.advances }
                val retained = (credits.maxOfOrNull { it.effectiveDiscount } ?: 0) * (1.0 - endgame)
                if (original.stage != Stage.PLAY_CARDS) {
                    alternatives[candidate.command] = BotLeaderAlternative(null, null, 0.0)
                    continue
                }
                val afterCash = BotLeaderCash.from(offer, seat, self.cash, candidate.cashCost).after
                val hand = original.hand.filterNot { it.reference == reference }
                val baseline = withCash(original.copy(hand = hand), afterCash, hand.size)
                val cardId = original.hand.single { it.reference == reference }.definition.id
                val discounted =
                    withCash(
                        baseline.copy(
                            advanceOffers =
                                baseline.advanceOffers.map { quote ->
                                    val credit =
                                        credits.firstOrNull { it.advance == quote.advance }
                                            ?: return@map quote
                                    val breakdown = quote.priceBreakdown ?: return@map quote
                                    val after =
                                        breakdown.copy(
                                            leaders =
                                                breakdown.leaders +
                                                    AppliedLeaderDiscount(
                                                        cardId,
                                                        credit.conditionalAmount,
                                                        credit.writtenRecordBonus,
                                                    )
                                        )
                                    quote.copy(
                                        price = after.price,
                                        priceBreakdown = after,
                                        leaderDiscounts = after.leaders,
                                    )
                                }
                        ),
                        afterCash,
                        hand.size,
                    )
                val without = sequence(baseline) ?: return null
                val with = sequence(discounted) ?: return null
                alternatives[candidate.command] = BotLeaderAlternative(without, with, retained)
            }
            val cashCards =
                original.cardOffers
                    .flatMap { offer ->
                        val card = original.hand.single { it.reference == offer.reference }
                        if (card.definition.kind != CardKind.COMMODITY) return@flatMap emptyList()
                        offer.choices
                            .filter { choice ->
                                choice.miseryUncertainPlayers.isEmpty() &&
                                    choice.cashChanges.any {
                                        it.player == seat && it.after > it.before
                                    }
                            }
                            .mapNotNull { it.command }
                    }
                    .toSet()
            if (cashCards.size > 128) return null
            return BotLeaderPlanning(
                candidatePool.toList(),
                forecasts.toList(),
                alternatives,
                cashCards,
            )
        }

        private fun withCash(view: GameView, cash: Int, handCount: Int): GameView =
            view.copy(
                stage = Stage.PURCHASE,
                players =
                    view.players.map { player ->
                        if (player.id != seat) player
                        else
                            player.copy(
                                cash = cash,
                                handCount = handCount,
                                income =
                                    player.income?.let { income ->
                                        income.copy(
                                            interestProfit =
                                                if (AdvanceId.INTEREST_PROFIT in player.advances)
                                                    minOf(cash, income.ordinary)
                                                else 0
                                        )
                                    },
                            )
                    },
                stabilizationCost =
                    PlayerEconomy.stabilizationCost(
                        handCount,
                        AdvanceId.MIDDLE_CLASS in self.advances,
                    ),
                advanceOffers =
                    view.advanceOffers.map { offer ->
                        val price = offer.price
                        if (
                            offer.unavailableReason != null &&
                                offer.unavailableReason != ViolationCode.INSUFFICIENT_CASH.name
                        )
                            offer
                        else
                            offer.copy(
                                unavailableReason =
                                    ViolationCode.INSUFFICIENT_CASH.name.takeIf {
                                        price != null && price > cash
                                    }
                            )
                    },
            )

        private fun sequence(view: GameView): BotLeaderContinuation? {
            val cash = view.players.single { it.id == seat }.cash
            val quote = original.purchaseShippingForecast
            val shipping =
                quote?.copy(
                    cashAfter = (cash - quote.cost).takeIf { it >= 0 },
                    blockers =
                        quote.blockers.filterNot { it == ViolationCode.INSUFFICIENT_CASH } +
                            listOfNotNull(
                                ViolationCode.INSUFFICIENT_CASH.takeIf { cash < quote.cost }
                            ),
                )
            val shippingView = view.copy(shippingOffer = shipping)
            val shippingCommands =
                listOfNotNull(
                    "pass",
                    "upgrade-ships"
                        .takeIf {
                            shipping != null &&
                                shipping.ships != null &&
                                shipping.blockers.isEmpty()
                        },
                )
            val shippingIndex =
                forecast(shippingView, BotPurchaseForecastStage.SHIPPING, shippingCommands)
                    ?: return null
            val advances = mutableMapOf<String, Int>()
            for (command in shippingCommands) {
                val after =
                    if (command == "pass") view
                    else {
                        val upgrade =
                            checkNotNull(shipping) { "A shipping upgrade requires its quote." }
                        withCash(
                            view.copy(
                                players =
                                    view.players.map { player ->
                                        if (player.id != seat) player
                                        else
                                            player.copy(
                                                ships =
                                                    checkNotNull(upgrade.ships) {
                                                        "An offered shipping upgrade requires its resulting capability."
                                                    }
                                            )
                                    }
                            ),
                            cash - upgrade.cost,
                            view.hand.size,
                        )
                    }
                val commands =
                    listOf("pass") +
                        after.advanceOffers
                            .filter { offer ->
                                val price = offer.price
                                price != null &&
                                    price <= after.players.single { it.id == seat }.cash &&
                                    (offer.unavailableReason == null ||
                                        offer.unavailableReason ==
                                            ViolationCode.INSUFFICIENT_CASH.name)
                            }
                            .map { "buy-advance ${it.advance.letter}" }
                advances[command] =
                    forecast(after, BotPurchaseForecastStage.ADVANCES, commands) ?: return null
            }
            return BotLeaderContinuation(shippingIndex, advances)
        }

        private fun forecast(
            view: GameView,
            stage: BotPurchaseForecastStage,
            commands: List<String>,
        ): Int? {
            val owner = view.players.single { it.id == seat }
            val key =
                ForecastKey(stage, owner.cash, owner.handCount, view.advanceOffers.map { it.price })
            forecastIndices[key]?.let {
                return it
            }
            if (forecasts.size >= 64) return null
            val observation =
                BotObservations.from(view, LegalChoices(commands), seat, stage.decision)
            val indices =
                observation.candidates.map { candidate ->
                    candidateIndices.getOrPut(candidate) {
                        candidatePool.add(candidate)
                        candidatePool.lastIndex
                    }
                }
            if (candidatePool.size > 512) return null
            val index = forecasts.size
            forecasts.add(
                BotPurchaseForecast(
                    stage,
                    observation.availableCash,
                    observation.context["stabilization"] ?: 0.0,
                    indices,
                    observation.reserveShortfallBasis,
                    observation.advancePlanning,
                )
            )
            forecastIndices[key] = index
            return index
        }
    }
}
