package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.FinalAdvanceOffer
import dev.aragos.aor.PlayerId
import dev.aragos.aor.RULES_VERSION

/** The November 20 published controller and the November 21 market finalist use the same assets. */
class CheckpointPolicy(
    base: BotModel,
    private val playerCount: Int,
    private val market: Boolean = false,
    private val profile: MarketProfile = MarketProfile(),
) {
    init {
        require(playerCount in 3..6) { "Hard checkpoints support three through six players." }
    }

    private val models =
        listOf(false, true).map { drawThree ->
            val adjustments = buildMap {
                put(BotPurchaseFeature.INDULGENCE_SWING.key, 6.0)
                put(BotPurchaseFeature.INDULGENCE_COUNTER.key, 8.0)
                put(BotPurchaseFeature.PATRONAGE_ACCESS.key, 20.0)
                put(BotPurchaseFeature.RELIEF_TIER.key, 20.0)
                if (playerCount == 5) put(BotPurchaseFeature.OVERSEAS_ACCESS.key, .6)
                if (market) {
                    put(
                        BotEconomicFeature.HELD_COMMODITY.key,
                        profile.commodityScale *
                            when (playerCount) {
                                3 -> .14
                                4 -> .08
                                else -> .20
                            },
                    )
                    put(
                        BotEconomicFeature.PUBLIC_COMMODITY.key,
                        profile.commodityScale *
                            when (playerCount) {
                                3 -> .35
                                4 -> .22617
                                else -> .27
                            },
                    )
                    put(
                        BotEconomicFeature.COMMODITY_DENIAL.key,
                        profile.commodityScale * (if (playerCount == 4) .12422 else .10),
                    )
                    put(
                        BotEconomicFeature.MARKET_MARKER_INCOME.key,
                        profile.markerScale *
                            when (playerCount) {
                                3 -> if (drawThree) .8 else 2.0
                                4 -> 3.0
                                5 -> 3.5
                                else -> 2.0
                            },
                    )
                    if (playerCount == 3 || playerCount == 6) {
                        put("opponentCash", -.55)
                        put(BotEconomicFeature.REACHABLE_RETENTION.key, .30)
                    }
                }
            }
            base.copy(weights = base.weights + adjustments)
        }

    private val initiatives =
        listOf(false, true).map { drawThree ->
            val early =
                when (playerCount) {
                    3 -> if (market) .324 else .30
                    4 -> if (market) .12 else .48
                    6 -> if (market) .24 else .48
                    else -> .30
                }
            PhaseInitiativePolicy.response(
                maximumTokenLoss = if (playerCount == 4 || playerCount == 6) 8 else 6,
                earlyBias = (early + profile.earlyShift).coerceIn(0.0, 2.0),
                lowBidResponse =
                    if (playerCount == 5) {
                        PersistentLowBidResponse(
                            1,
                            ((if (market && !drawThree) .12 else .48) + profile.earlyShift)
                                .coerceIn(0.0, 2.0),
                        )
                    } else null,
            )
        }

    fun choose(
        observation: BotObservation,
        initiative: PhaseInitiativeInput?,
        finish: FinalAdvanceOffer?,
        seed: Int,
    ): String {
        val config = requireNotNull(observation.configuration) { "Hard needs the table setup." }
        require(
            config.playerCount == playerCount &&
                observation.rulesVersion == RULES_VERSION &&
                observation.dataVersion == DATA_VERSION
        ) {
            "The deciding seat must match the Hard checkpoint's table, rules, and catalog."
        }
        val opening = if (config.drawThreeKeepTwo) 1 else 0
        val ordinary = BotPolicy.choose(observation, BotDifficulty.HARD, seed, models[opening])
        val selected =
            when {
                observation.decision == "bid-capital" && playerCount == 5 ->
                    PhaseInitiativePolicy.varyCapitalBid(observation, ordinary, seed)
                observation.decision == "buy-tokens" && initiative != null ->
                    PhaseInitiativePolicy.bid(
                        initiative,
                        PlayerId(observation.playerId),
                        ordinary,
                        seed,
                        initiatives[opening],
                        secureMarkerWeight =
                            if (market && playerCount == 5 && config.drawThreeKeepTwo) .25 else 0.0,
                    )
                observation.decision == "purchase-advances" &&
                    ordinary == "pass" &&
                    finish != null &&
                    finish.currentMargin >= 100 &&
                    finish.projectedMargin >= 250 -> finish.command
                observation.decision == "expand" && market && profile.expansion.isNotEmpty() ->
                    ExpansionResponse.choose(observation, ordinary, profile.expansion)
                else -> ordinary
            }
        return selected.takeIf { command -> observation.candidates.any { it.command == command } }
            ?: ordinary
    }
}
