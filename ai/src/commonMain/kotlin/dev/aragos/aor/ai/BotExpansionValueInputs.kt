package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardEffectData
import dev.aragos.aor.CardEffectSpec
import dev.aragos.aor.CardRules
import dev.aragos.aor.Commodity
import dev.aragos.aor.ExpansionOfferView
import dev.aragos.aor.GameView
import dev.aragos.aor.IncomeQuote
import dev.aragos.aor.PlayerEconomy
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerIncomeInputs
import dev.aragos.aor.ProvinceDefinition
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.ProvincePieces

/** Nominal action facts from an actual offer, not a realized transition or future legal offer. */
internal data class BotExpansionActionInputs(
    val requiredTokens: Int,
    val establishesMarker: Boolean,
    val uncertain: Boolean,
    val winProbability: Double?,
    val incomeBefore: Int?,
    val incomeAfter: Int?,
) {
    companion object {
        fun from(offer: ExpansionOfferView): BotExpansionActionInputs =
            BotExpansionActionInputs(
                offer.requiredTokens,
                offer.establishesMarker,
                offer.uncertain,
                offer.competitionWinProbability,
                offer.incomeBefore,
                offer.incomeAfter,
            )
    }
}

/** Public owner facts; null income denotes Chaos. Counts include current white markers. */
internal data class BotExpansionOwnerInputs(
    val industry: Boolean,
    val income: PlayerIncomeInputs?,
    val markets: Map<Commodity, Int>,
)

/**
 * Economic facts from one real actor view, independent of its offers and phase driver. Only the
 * actor's own physical commodity cards are retained. Replacing board counts does not assert action
 * readiness, resolve victory interruptions or award future cash/cards. Existing nominal features
 * deliberately retain their historical estimates; they are not a result-conditioned search utility.
 */
@ConsistentCopyVisibility
internal data class BotExpansionValueInputs
private constructor(
    val seat: PlayerId,
    val initialPlayerCount: Int,
    val owners: Map<PlayerId, BotExpansionOwnerInputs>,
    val provinces: Map<ProvinceId, ProvinceDefinition>,
    val markerOwners: Map<ProvinceId, PlayerId>,
    val bases: Map<Commodity, Int>,
    val adjustments: Map<Commodity, Int>,
    val ownCards: Map<String, Set<Commodity>>,
    val unplayedCards: Map<Commodity, Int>,
) {
    private val outsideCardCounts = unplayedCards.mapValues { (commodity, unplayed) ->
        (unplayed - ownCards.values.count { commodity in it }).coerceAtLeast(0)
    }

    val commodityForecasts: List<BotPlanningCommodity> by
        lazy(LazyThreadSafetyMode.NONE) {
            Commodity.entries.map { commodity ->
                BotPlanningCommodity(
                    commodity,
                    count(seat, commodity),
                    checkNotNull(bases[commodity]) {
                        "Every planning commodity requires its public base value."
                    },
                    adjustments[commodity] ?: 0,
                    outsideCardCounts[commodity] ?: 0,
                )
            }
        }

    fun portfolio(): BotCommodityPortfolio =
        BotCommodityPortfolio(
            owner(seat).industry,
            commodityForecasts,
            ownCards.values.toList(),
        )

    /**
     * Counts change through the board owner; cash, capabilities and first adjustments stay fixed.
     */
    fun withBoard(board: BoardState): BotExpansionValueInputs =
        copy(
            owners =
                owners.mapValues { (id, owner) ->
                    owner.copy(
                        markets =
                            Commodity.entries.associateWith {
                                if (owner.income == null) 0 else board.commodityCount(id, it)
                            }
                    )
                },
            markerOwners =
                board.pieces
                    .mapNotNull { (id, pieces) ->
                        (pieces as? ProvincePieces.Marker)?.let { id to it.owner }
                    }
                    .toMap(),
        )

    fun income(board: BoardState, player: PlayerId = seat): IncomeQuote? =
        owner(player).income?.let {
            PlayerEconomy.incomeQuote(it, initialPlayerCount, board.markerCount(player))
        }

    fun count(player: PlayerId, commodity: Commodity): Int = owner(player).markets[commodity] ?: 0

    fun payout(
        player: PlayerId,
        commodity: Commodity,
        markets: Int = count(player, commodity),
    ): Double {
        val owner = owner(player)
        if (owner.income == null) return 0.0
        val box = CardRules.commodityPayoutBox(markets, owner.industry, adjustments[commodity] ?: 0)
        return (bases[commodity] ?: 0).toDouble() * box * box
    }

    /** Legacy raw marginal intentionally excludes Industry and current commodity adjustments. */
    fun commodityMarginal(commodity: Commodity): Double =
        (bases[commodity] ?: 0) * (2.0 * count(seat, commodity) + 1.0)

    fun nominalFeatures(
        province: ProvinceId,
        action: BotExpansionActionInputs,
        tokens: Int,
    ): Map<String, Double> {
        val definition =
            checkNotNull(provinces[province]) { "Expansion requires a known province." }
        val complete = tokens >= action.requiredTokens
        val chance = if (action.uncertain) action.winProbability ?: 0.5 else 1.0
        val fraction =
            if (complete) 1.0 else tokens.toDouble() / action.requiredTokens.coerceAtLeast(1)
        return linkedMapOf(
            "tokens" to -tokens.toDouble(),
            "progress" to fraction,
            "reach" to if (definition.isSatellite) 1.5 * chance else fraction * chance,
            "commodity" to
                definition.commodities.sumOf(::commodityMarginal) * fraction * chance * 0.25,
            "income" to
                when {
                    !complete || definition.isSatellite -> 0.0
                    action.incomeAfter != null && action.incomeBefore != null ->
                        (action.incomeAfter - action.incomeBefore).toDouble()
                    else -> 2.0 * chance
                },
            "risk" to if (action.uncertain) (1.0 - chance) * tokens else 0.0,
        )
    }

    fun economicFeatures(
        province: ProvinceId,
        action: BotExpansionActionInputs,
        tokens: Int,
    ): Map<String, Double> {
        if (!action.establishesMarker || action.requiredTokens <= 0) return emptyMap()
        val definition =
            checkNotNull(provinces[province]) {
                "An economic expansion feature requires a public destination."
            }
        val fraction = (tokens.toDouble() / action.requiredTokens).coerceAtMost(1.0)
        val chance = if (action.uncertain) action.winProbability ?: 0.5 else 1.0
        val scale = fraction * chance
        val gains =
            definition.commodities.associateWith { commodity ->
                payout(seat, commodity, count(seat, commodity) + 1) - payout(seat, commodity)
            }
        val held =
            ownCards.values.sumOf { choices ->
                val before = choices.maxOf { payout(seat, it) }
                val after = choices.maxOf { payout(seat, it) + (gains[it] ?: 0.0) }
                after - before
            }
        val public =
            gains.entries.sumOf { (commodity, gain) -> gain * (outsideCardCounts[commodity] ?: 0) }
        val defender = markerOwners[province]?.takeUnless { it == seat }
        val denied =
            if (defender == null) {
                0.0
            } else {
                definition.commodities.sumOf { commodity ->
                    val before = payout(defender, commodity)
                    val after =
                        payout(
                            defender,
                            commodity,
                            (count(defender, commodity) - 1).coerceAtLeast(0),
                        )
                    (before - after) * (unplayedCards[commodity] ?: 0)
                }
            }
        return listOf(
                BotEconomicFeature.HELD_COMMODITY to held * scale,
                BotEconomicFeature.PUBLIC_COMMODITY to public * scale,
                BotEconomicFeature.COMMODITY_DENIAL to denied * scale,
            )
            .filter { it.second != 0.0 }
            .associate { it.first.key to it.second }
    }

    private fun owner(player: PlayerId): BotExpansionOwnerInputs =
        checkNotNull(owners[player]) { "Expansion economics requires a registered public owner." }

    companion object {
        fun from(view: GameView, seat: PlayerId): BotExpansionValueInputs {
            val catalog =
                checkNotNull(view.catalog) { "Economic bot features require the public catalog." }
            require(view.players.any { it.id == seat }) {
                "Expansion economics requires the actor's public profile."
            }
            val forecasts = view.commodityPayouts.associateBy { it.commodity }
            val ownCards =
                view.hand
                    .mapNotNull { card ->
                        val choices =
                            (CardEffectData.forCard(card.definition.id)
                                    as? CardEffectSpec.CommodityChoices)
                                ?.choices
                                .orEmpty()
                        choices.takeIf { it.isNotEmpty() }?.let { card.reference to it }
                    }
                    .toMap()
            return BotExpansionValueInputs(
                seat = seat,
                initialPlayerCount = view.players.size,
                owners =
                    view.players.associate { player ->
                        player.id to
                            BotExpansionOwnerInputs(
                                industry = AdvanceId.INDUSTRY in player.advances,
                                income =
                                    if (player.misery.value == null) {
                                        null
                                    } else {
                                        PlayerIncomeInputs(
                                            cash = player.cash,
                                            middleClass = AdvanceId.MIDDLE_CLASS in player.advances,
                                            interestProfit =
                                                AdvanceId.INTEREST_PROFIT in player.advances,
                                            newWorld = AdvanceId.NEW_WORLD in player.advances,
                                        )
                                    },
                                markets =
                                    Commodity.entries.associateWith { commodity ->
                                        forecasts[commodity]
                                            ?.owners
                                            ?.firstOrNull { it.player == player.id }
                                            ?.marketCount ?: 0
                                    },
                            )
                    },
                provinces = catalog.provinces.associateBy(ProvinceDefinition::id),
                markerOwners =
                    view.board
                        .mapNotNull { province -> province.marker?.let { province.id to it.owner } }
                        .toMap(),
                bases = catalog.commodities.associate { it.id to it.baseValue },
                adjustments =
                    forecasts.mapValues { (_, forecast) ->
                        forecast.adjustments.firstOrNull()?.payoutOffset ?: 0
                    },
                ownCards = ownCards,
                unplayedCards =
                    view.commodityCards.associate { it.commodity to (it.unplayed ?: 0) },
            )
        }
    }
}
