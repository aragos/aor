@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.FinalAdvanceOffer
import dev.aragos.aor.PlayerId
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.TokenPurchaseOfferView
import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport

/** Browser-owned policy. One worker retains the decoded model for its game's player count. */
@JsExport
class HardBotSelector(modelJson: String, playerCount: Int, market: Boolean = false) {
    private val policy: CheckpointPolicy

    init {
        val raw = JSON.parse<dynamic>(modelJson)
        require(raw.rulesVersion == RULES_VERSION && raw.dataVersion == DATA_VERSION) {
            "The Hard checkpoint requires the current rules and catalog."
        }
        val base =
            BotModels.fromSerializedWeights(
                numberMap(raw.weights),
                (raw.schemaVersion as Number).toInt(),
            )
        val profile = raw.marketProfile
        policy =
            CheckpointPolicy(
                base,
                playerCount,
                market,
                if (profile == null || !market) MarketProfile()
                else
                    MarketProfile(
                        (profile.markerScale as Number).toDouble(),
                        (profile.commodityScale as Number).toDouble(),
                        (profile.earlyShift as Number).toDouble(),
                        (profile.expansion as Array<Number>).map(Number::toDouble),
                    ),
            )
    }

    fun choose(
        observationJson: String,
        initiativeJson: String?,
        finishJson: String?,
        seed: Int,
    ): String =
        policy.choose(
            readBotObservation(observationJson),
            initiativeJson?.let { readInitiative(JSON.parse<dynamic>(it)) },
            finishJson?.let {
                val raw = JSON.parse<dynamic>(it)
                FinalAdvanceOffer(
                    raw.command as String,
                    (raw.currentMargin as Number).toLong(),
                    (raw.projectedMargin as Number).toLong(),
                )
            },
            seed,
        )

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
            exposedMarkerIncome = (raw.exposedMarkerIncome as Number?)?.toDouble() ?: 0.0,
        )
    }
}
