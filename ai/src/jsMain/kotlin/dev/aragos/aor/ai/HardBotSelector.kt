@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.PlayerId
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.TokenPurchaseOfferView
import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport

/** Browser-owned policy. One worker retains the decoded model for its game's player count. */
@JsExport
class HardBotSelector(modelJson: String, private val playerCount: Int) {
    private val model: BotModel

    init {
        require(playerCount in 3..6) { "The Hard checkpoint supports three through six players." }
        val raw = JSON.parse<dynamic>(modelJson)
        require(raw.rulesVersion == RULES_VERSION && raw.dataVersion == DATA_VERSION) {
            "The Hard checkpoint requires the current rules and catalog."
        }
        val weights = numberMap(raw.weights)
        model =
            BotModels.fromSerializedWeights(
                schemaVersion = (raw.schemaVersion as Number).toInt(),
                weights =
                    weights +
                        buildMap {
                            put(BotPurchaseFeature.INDULGENCE_SWING.key, 6.0)
                            put(BotPurchaseFeature.INDULGENCE_COUNTER.key, 8.0)
                            put(BotPurchaseFeature.PATRONAGE_ACCESS.key, 20.0)
                            put(BotPurchaseFeature.RELIEF_TIER.key, 20.0)
                            if (playerCount == 5) put(BotPurchaseFeature.OVERSEAS_ACCESS.key, .6)
                        },
            )
    }

    private val initiative =
        when (playerCount) {
            4,
            6 -> PhaseInitiativePolicy.response(maximumTokenLoss = 8, earlyBias = .48)
            5 ->
                PhaseInitiativePolicy.response(
                    maximumTokenLoss = 6,
                    earlyBias = .30,
                    lowBidResponse = PersistentLowBidResponse(tokenMargin = 1, earlyBias = .48),
                )
            else -> PhaseInitiativePolicy.response(maximumTokenLoss = 6, earlyBias = .30)
        }

    fun choose(
        observationJson: String,
        initiativeJson: String?,
        finishJson: String?,
        seed: Int,
    ): String {
        val observation = readBotObservation(observationJson)
        require(
            observation.configuration?.playerCount == playerCount &&
                observation.rulesVersion == RULES_VERSION &&
                observation.dataVersion == DATA_VERSION
        ) {
            "The deciding seat must match the Hard checkpoint's table, rules, and catalog."
        }
        val ordinary = BotPolicy.choose(observation, BotDifficulty.HARD, seed, model)
        val selected =
            when (observation.decision) {
                "bid-capital" ->
                    if (playerCount == 5) {
                        PhaseInitiativePolicy.varyCapitalBid(observation, ordinary, seed)
                    } else ordinary
                "buy-tokens" ->
                    if (initiativeJson == null) ordinary
                    else
                        PhaseInitiativePolicy.bid(
                            readInitiative(JSON.parse<dynamic>(initiativeJson)),
                            PlayerId(observation.playerId),
                            ordinary,
                            seed,
                            initiative,
                        )
                "purchase-advances" ->
                    if (ordinary != "pass" || finishJson == null) ordinary
                    else {
                        val offer = JSON.parse<dynamic>(finishJson)
                        if (offer.currentMargin >= 100 && offer.projectedMargin >= 250) {
                            offer.command as String
                        } else ordinary
                    }
                else -> ordinary
            }
        return selected.takeIf { command -> observation.candidates.any { it.command == command } }
            ?: ordinary
    }

    private fun readInitiative(raw: dynamic): PhaseInitiativeInput {
        val bids = raw.bids
        return PhaseInitiativeInput(
            bids =
                InitiativeBidInput(
                    offers =
                        (bids.offers as Array<dynamic>).map { offer ->
                            TokenPurchaseOfferView(
                                amount = (offer.amount as Number).toInt(),
                                cost = (offer.cost as Number).toInt(),
                                tokensReceived = (offer.tokensReceived as Number).toInt(),
                                cashAfter = (offer.cashAfter as Number).toInt(),
                                stockAfter = (offer.stockAfter as Number).toInt(),
                                poolAfter = (offer.poolAfter as Number).toInt(),
                            )
                        },
                    pastPurchases =
                        (bids.pastPurchases as Array<dynamic>).map { row ->
                            numberMap(row)
                                .mapKeys { (id, _) -> PlayerId(id) }
                                .mapValues { (_, amount) -> amount.toInt() }
                        },
                    capitalOrder = (bids.capitalOrder as Array<String>).map(::PlayerId),
                    rivals =
                        (bids.rivals as Array<dynamic>).map { rival ->
                            InitiativeRival(
                                PlayerId(rival.id as String),
                                (rival.cash as Number).toInt(),
                                rival.inChaos as Boolean,
                            )
                        },
                    turnNumber = (bids.turnNumber as Number?)?.toInt(),
                    contestCost = (bids.contestCost as Number?)?.toInt(),
                    opportunityByPool =
                        numberMap(bids.opportunityByPool).mapKeys { (pool, _) -> pool.toInt() },
                ),
            earlyPayout = (raw.earlyPayout as Number).toDouble(),
            buyingCard = (raw.buyingCard as Number).toDouble(),
            earlyCompetition = (raw.earlyCompetition as Number).toDouble(),
            secureMarkets = (raw.secureMarkets as Number).toDouble(),
        )
    }
}
