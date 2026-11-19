package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceData
import dev.aragos.aor.CardEffectData
import dev.aragos.aor.CardEffectSpec
import dev.aragos.aor.Commodity
import dev.aragos.aor.HandCard
import dev.aragos.aor.LeaderData
import dev.aragos.aor.ProvinceDefinition
import dev.aragos.aor.PublicCatalog

const val OPENING_HAND_FEATURE_PREFIX = "openingHand."

/** Features for the two cards a player could keep before bidding for a capital. */
internal class OpeningHandProjection(catalog: PublicCatalog, private val hand: List<HandCard>) {
    private val capitalMarkets = catalog.provinces.filter { it.capital != null }
    private val marketsById = catalog.provinces.associateBy(ProvinceDefinition::id)
    private val commodityValue = catalog.commodities.associate { it.id to it.baseValue }
    private val effectByReference = hand.associate {
        it.reference to CardEffectData.forCard(it.definition.id)
    }
    private val marketsByCapital = capitalMarkets.map { capital ->
        val neighbors =
            catalog.landBorders.mapNotNull { border ->
                when (capital.id) {
                    border.first -> marketsById[border.second]
                    border.second -> marketsById[border.first]
                    else -> null
                }
            }
        buildMap<Commodity, Double> {
            for (commodity in capital.commodities) {
                put(commodity, 1.0)
            }
            for (market in neighbors) {
                if (market.access.isNotEmpty()) continue
                for (commodity in market.commodities) {
                    val projected = 1.0 / market.marketNumber
                    put(commodity, maxOf(this[commodity] ?: 0.0, projected))
                }
            }
        }
    }

    fun afterReturning(reference: String): Map<String, Double> {
        val kept = hand.filterNot { it.reference == reference }
        val effects = kept.mapNotNull { effectByReference[it.reference] }
        val commodities = effects.filterIsInstance<CardEffectSpec.CommodityChoices>()
        val opportunity = marketsByCapital.map { local ->
            commodities.sumOf { effect ->
                effect.choices.maxOf { choice ->
                    (local[choice] ?: 0.0) * (commodityValue[choice] ?: 0)
                }
            }
        }
        val leaderCredit =
            effects.filterIsInstance<CardEffectSpec.Leader>().sumOf { effect ->
                LeaderData.definition(effect.leader)
                    .discounts
                    .filter { AdvanceData.definition(it.advance).cost <= 100 }
                    .sumOf { it.amount }
            }
        val sharedCommodity =
            commodities.size == 2 && commodities[0].choices.any { it in commodities[1].choices }
        return buildMap {
            for (card in kept) {
                val identity = "${OPENING_HAND_FEATURE_PREFIX}card.${card.definition.id}"
                put(identity, (this[identity] ?: 0.0) + 1.0)
            }
            put(
                "${OPENING_HAND_FEATURE_PREFIX}capitalMaximum",
                (opportunity.maxOrNull() ?: 0.0) / 20.0,
            )
            put(
                "${OPENING_HAND_FEATURE_PREFIX}capitalMean",
                if (opportunity.isEmpty()) 0.0 else opportunity.average() / 20.0,
            )
            put("${OPENING_HAND_FEATURE_PREFIX}leaderCredit", leaderCredit / 60.0)
            put("${OPENING_HAND_FEATURE_PREFIX}sharedCommodity", if (sharedCommodity) 1.0 else 0.0)
        }
    }
}
