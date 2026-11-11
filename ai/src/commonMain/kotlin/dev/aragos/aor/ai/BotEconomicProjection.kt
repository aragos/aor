package dev.aragos.aor.ai

import dev.aragos.aor.AccessRequirement
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardChoiceView
import dev.aragos.aor.Commodity
import dev.aragos.aor.ExpansionOfferView
import dev.aragos.aor.GameView
import dev.aragos.aor.PieceSide
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceDefinition
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage

/**
 * Additive observation features. Old models have no weights in this namespace and retain their
 * scores. Dollar features estimate economic opportunity, not guaranteed payments or legal plans.
 */
enum class BotEconomicFeature(val key: String) {
    /** Increase in the best payout of each physical own commodity card, counted once per card. */
    HELD_COMMODITY("planning.heldCommodity"),
    /** Dollar marginal multiplied by public unplayed-card counts outside the viewer's hand. */
    PUBLIC_COMMODITY("planning.publicCommodity"),
    /** Public commodity opportunity removed from a defending marker's owner, in dollars. */
    COMMODITY_DENIAL("planning.commodityDenial"),
    /** Negative dollar growth forfeited by consuming a card before nearby ordinary expansion. */
    RETAINED_COMMODITY("planning.retainedCommodity"),
    /** Minus one when consuming a card leaves unused future matching-market opportunities. */
    COMMODITY_OPTION("planning.commodityOption"),
    /** Negative payout growth from affordable peaceful markets using current routes and ships. */
    REACHABLE_RETENTION("planning.reachableRetention"),
    /** Minus one for a matching peaceful market reachable with current capabilities. */
    REACHABLE_COMMODITY_OPTION("planning.reachableCommodityOption"),
    /** Difference between capped and uncapped token-target distance, in signed bid units. */
    TOKEN_CAP_CORRECTION("planning.tokenCapCorrection"),
    /** Correct conditional income after an advance minus the legacy estimate, in dollars. */
    ADVANCE_INCOME_CORRECTION("planning.advanceIncomeCorrection"),
    /** Income correction multiplied by the observation's existing endgame fraction. */
    ENDGAME_ADVANCE_INCOME_CORRECTION("planning.endgameAdvanceIncomeCorrection"),
    /** Negative current Interest & Profit dollars lost to a quoted cash spend before Income. */
    FOREGONE_INTEREST("planning.foregoneInterest"),
    /** Final-turn printed points less quoted cash and immediate own numeric misery increase. */
    FINAL_ADVANCE_SCORE("planning.finalAdvanceScore"),
    /** Negative marker-control units lost by the actor; square holdings count fractionally. */
    AREA_OWN_LOSS("planning.areaOwnLoss"),
    /** Opponents' marker-control units lost; square holdings count fractionally. */
    AREA_OPPONENT_LOSS("planning.areaOpponentLoss"),
}

/**
 * Shared card/expansion estimates from the deciding seat's public facts and own hand only.
 * Expansion receives ordinary authoritative offers. Card retention includes a limited direct-land
 * estimate and a separate authoritative current-route forecast, both constrained by current pool
 * and marker stock. Neither predicts shipping purchases nor claims future actions are already
 * authorized. Public exposure uses published commodity counts; shared-choice cards count for both
 * commodities.
 */
internal class BotEconomicProjection(private val view: GameView, private val seat: PlayerId) {
    private val self = view.players.single { it.id == seat }
    private val catalog =
        checkNotNull(view.catalog) {
            "Economic bot features require the public catalog."
        }
    private val provinces = catalog.provinces.associateBy(ProvinceDefinition::id)
    private val occupied = view.board.associateBy { it.id }
    val expansionValues = BotExpansionValueInputs.from(view, seat)
    private val directLandMarkets by lazy(LazyThreadSafetyMode.NONE) { directLandMarkets() }

    /**
     * Public payout facts and own physical cards, independent of current legal expansion offers.
     */
    fun portfolio(): BotCommodityPortfolio = expansionValues.portfolio()

    /** Share the same public forecast and physical own cards with bounded bundle evaluation. */
    fun planning(candidates: List<BotCandidate>): BotExpansionPlanning {
        val offers = view.expansionOffers.associateBy { it.destination.value }
        val steps =
            candidates
                .filter { it.family == "expansion" }
                .mapNotNull { candidate ->
                    val destination = candidate.command.substringAfterLast(' ')
                    val offer = offers[destination] ?: return@mapNotNull null
                    val definition =
                        checkNotNull(provinces[offer.destination]) {
                            "An expansion budget requires its public destination definition."
                        }
                    val occupants = occupied[offer.destination]
                    val ownPresence =
                        occupants?.marker?.owner == seat ||
                            occupants?.tokens?.any { it.owner == seat } == true
                    val overseasCost = if (definition.overseas != null && !ownPresence) 1 else 0
                    val full =
                        offer.establishesMarker &&
                            offer.requiredTokens > 0 &&
                            candidate.tokenCost >= offer.requiredTokens
                    if (!full && overseasCost == 0) return@mapNotNull null
                    val certainLand =
                        full &&
                            !offer.uncertain &&
                            offer.shipping == null &&
                            offer.blockers.isEmpty()
                    candidate.command to
                        BotExpansionStep(
                            markerCost = if (full) 1 else 0,
                            commodities = if (certainLand) definition.commodities else emptySet(),
                            newOverseasCost = overseasCost,
                        )
                }
                .toMap()
        return BotExpansionPlanning(
            markerStock = self.stockMarkers,
            industry = AdvanceId.INDUSTRY in self.advances,
            commodities = expansionValues.commodityForecasts,
            heldCards = expansionValues.ownCards.values.toList(),
            steps = steps,
            overseasSlots = self.overseasSlots,
        )
    }

    fun expansion(
        province: ProvinceId,
        offer: ExpansionOfferView,
        tokens: Int,
    ): Map<String, Double> =
        expansionValues.economicFeatures(province, BotExpansionActionInputs.from(offer), tokens)

    /**
     * One lost marker is one control unit; removed square tokens contribute their fraction of the
     * printed market number. This values public occupation, not physical pieces destroyed or a
     * promised income loss. Optional replacement tokens remain unresolved and are not predicted.
     */
    fun area(choice: CardChoiceView): Map<String, Double> {
        val losses =
            choice.boardLosses.map { loss ->
                val units =
                    if (loss.markerControlLost) {
                        1.0
                    } else {
                        val province =
                            checkNotNull(provinces[loss.province]) {
                                "A projected area loss requires its public province definition."
                            }
                        loss.tokensRemoved.toDouble() / province.marketNumber
                    }
                loss.owner to units
            }
        return features(
            BotEconomicFeature.AREA_OWN_LOSS to
                -losses.filter { (owner, _) -> owner == seat }.sumOf { it.second },
            BotEconomicFeature.AREA_OPPONENT_LOSS to
                losses.filter { (owner, _) -> owner != seat }.sumOf { it.second },
        )
    }

    fun card(reference: String): Map<String, Double> {
        val choices = expansionValues.ownCards[reference] ?: return emptyMap()
        if (view.stage == Stage.FINAL_CARD_PLAY) return emptyMap()
        val before = choices.maxOf { expansionValues.payout(seat, it) }
        val after = choices.maxOf { commodity ->
            expandedPayout(
                commodity,
                directLandMarkets
                    .filter { commodity in it.commodities }
                    .map { MarketCost(remainingTokens(it)) },
            )
        }
        val reachableAfter = choices.maxOf { commodity ->
            expandedPayout(
                commodity,
                view.expansionForecast
                    ?.peacefulMarkets
                    .orEmpty()
                    .filter { commodity in provinces[it.destination]?.commodities.orEmpty() }
                    .map { MarketCost(it.requiredTokens, it.usesOverseasSlot) },
                overseasSlots = view.expansionForecast?.overseasSlots ?: 0,
            )
        }
        val futureOption =
            !view.endingTriggered &&
                self.stockMarkers > 0 &&
                self.poolTokens + self.stockTokens > 0 &&
                catalog.provinces.any { province ->
                    province.commodities.any { it in choices } &&
                        peacefulUnowned(province) &&
                        province.access.all { it.isSatisfiedBy(self.advances) }
                }
        val reachableOption =
            !view.endingTriggered &&
                self.poolTokens + self.stockTokens > 0 &&
                view.expansionForecast?.peacefulMarkets.orEmpty().any { prospect ->
                    provinces[prospect.destination]?.commodities.orEmpty().any { it in choices }
                }
        return features(
            BotEconomicFeature.RETAINED_COMMODITY to -(after - before).coerceAtLeast(0.0),
            BotEconomicFeature.COMMODITY_OPTION to if (futureOption) -1.0 else 0.0,
            BotEconomicFeature.REACHABLE_RETENTION to -(reachableAfter - before).coerceAtLeast(0.0),
            BotEconomicFeature.REACHABLE_COMMODITY_OPTION to if (reachableOption) -1.0 else 0.0,
        )
    }

    private data class MarketCost(val tokens: Int, val overseas: Boolean = false)

    private fun expandedPayout(
        commodity: Commodity,
        costs: List<MarketCost>,
        overseasSlots: Int = Int.MAX_VALUE,
    ): Double {
        var pool = self.poolTokens
        var acquired = 0
        var slots = overseasSlots
        for (cost in costs.sortedBy(MarketCost::tokens)) {
            if (cost.tokens > pool || acquired == self.stockMarkers) break
            if (cost.overseas && slots == 0) continue
            pool -= cost.tokens
            if (cost.overseas) slots--
            acquired++
        }
        return expansionValues.payout(
            seat,
            commodity,
            expansionValues.count(seat, commodity) + acquired,
        )
    }

    private fun directLandMarkets(): List<ProvinceDefinition> {
        val origins =
            view.board
                .filter { province ->
                    province.marker?.let { it.owner == seat && it.side == PieceSide.COLOURED } ==
                        true || province.tokens.any { it.owner == seat && it.coloured > 0 }
                }
                .map { it.id }
                .toSet()
        val adjacent =
            catalog.landBorders
                .flatMap { border ->
                    listOfNotNull(
                        border.second.takeIf { border.first in origins },
                        border.first.takeIf { border.second in origins },
                    )
                }
                .toSet()
        return catalog.provinces.filter { province ->
            province.id in adjacent &&
                peacefulUnowned(province) &&
                province.overseas == null &&
                AccessRequirement.THE_HEAVENS_OR_SEAWORTHY_VESSELS !in province.access &&
                province.access.all { it.isSatisfiedBy(self.advances) }
        }
    }

    private fun peacefulUnowned(province: ProvinceDefinition): Boolean {
        if (province.isSatellite) return false
        val existing = occupied[province.id] ?: return true
        return existing.marker == null && existing.tokens.all { it.owner == seat }
    }

    private fun remainingTokens(province: ProvinceDefinition): Int =
        province.marketNumber -
            (occupied[province.id]
                ?.tokens
                ?.filter { it.owner == seat }
                ?.sumOf { it.coloured + it.white } ?: 0)

    private fun features(vararg values: Pair<BotEconomicFeature, Double>): Map<String, Double> =
        values.filter { it.second != 0.0 }.associate { it.first.key to it.second }
}
